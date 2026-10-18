/*
 * Moondust, a free game engine for platform game making
 * Copyright (c) 2014-2023 Vitaly Novichkov <admin@wohlnet.ru>
 *
 * This software is licensed under a dual license system (MIT or GPL version 3 or later).
 * This means you are free to choose with which of both licenses (MIT or GPL version 3 or later)
 * you want to use this software.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You can see text of MIT license in the LICENSE.mit file you can see in Engine folder,
 * or see https://mit-license.org/.
 *
 * You can see text of GPLv3 license in the LICENSE.gpl3 file you can see in Engine folder,
 * or see <http://www.gnu.org/licenses/>.
 */

#include "lua_engine.h"

#include "lua_event.h"
#include "lua_utils.h"
#include "lua_global.h"

#include <SDL2/SDL_rwops.h>

//Core libraries:
#include "bindings/core/globalfuncs/luafuncs_core_audio.h"
#include "bindings/core/globalfuncs/luafuncs_core_effect.h"
#include "bindings/core/globalfuncs/luafuncs_core_logger.h"
#include "bindings/core/globalfuncs/luafuncs_core_renderer.h"
#include "bindings/core/globalfuncs/luafuncs_core_settings.h"
#include "bindings/core/globalfuncs/luafuncs_core_paths.h"
#include "bindings/core/events/luaevents_core_engine.h"

#include "bindings/core/classes/luaclass_core_simpleevent.h"
#include "bindings/core/classes/luaclass_core_graphics.h"

#include "bindings/core/classes/luaclass_core_scene_effects.h"

#include <Utils/files.h>
#include <Utils/sdl_file.h>
#include <Logger/logger.h>
#include <fmt_format_ne.h>

#include <sstream>

#if defined(__ANDROID__)
#include <set>
#include <vector>
#include <algorithm>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <data_configs/config_manager.h>
#include <common_features/app_path.h>
#endif

#ifdef ANDROID
#include <string>
#include <sstream>
template <typename T>
std::string to_string(T value)
{
    std::ostringstream os ;
    os << value ;
    return os.str() ;
}
#define NUM2STR(x) to_string(x)
#else
#define NUM2STR(x) std::to_string(x)
#endif

#ifdef USE_LUA_JIT
extern "C" {
#include <lua_includes/luajit.h>
}
#endif

LuaEngine::LuaEngine() : LuaEngine(nullptr)
{}

LuaEngine::LuaEngine(Scene *scene) : m_lateShutdown(false), m_luaScriptPath(""), m_baseScene(scene), L(nullptr), m_coreFile("")
{}

LuaEngine::~LuaEngine()
{
    //Clear all user caches
    Binding_Core_Graphics::clearCache();

    if(isValid())
        shutdown();
}

const char *LuaEngine::getEngineInfo()
{
#ifdef USE_LUA_JIT
    return LUAJIT_VERSION;
#else
    return "PUC-Rio " LUA_RELEASE;
#endif
}

static void splitErrorMsg(const std::string &inMsg, std::string &msg1, std::string &msg2)
{
    std::string::size_type lp = inMsg.find('\n');
    if(lp != std::string::npos)
    {
        msg1 = inMsg.substr(0, lp);
        std::string::size_type lp2 = inMsg.find('\n', lp);
        if(lp2 != std::string::npos)
            msg2 = inMsg.substr(lp + 1, lp2);
        else
            msg2 = inMsg.substr(lp + 1);
    }
    else
        msg1 = inMsg;
}

#if defined(__ANDROID__)
/* ========================================================================
 * Android Lua sandbox for untrusted (downloaded) mods/episodes/config packs.
 * See LuaSandbox_PathPolicy() for the single place that decides file access.
 * ======================================================================== */

// Canonical (realpath) form of a directory root with no trailing '/'. Empty if it doesn't exist or is "/".
static std::string LuaSandbox_CanonRoot(const std::string &dir)
{
    if(dir.empty() || dir[0] == ':') // ':' = embedded resource path, not on disk
        return std::string();
    char buf[PATH_MAX];
    if(!::realpath(dir.c_str(), buf))
        return std::string();
    std::string r(buf);
    while(r.size() > 1 && r.back() == '/')
        r.pop_back();
    if(r == "/")
        return std::string();
    return r;
}

static bool LuaSandbox_InsideRoot(const std::string &canon, const std::string &root)
{
    return canon.size() > root.size() &&
           canon.compare(0, root.size(), root) == 0 &&
           canon[root.size()] == '/';
}

// Logs the first time each category is blocked, silently counts afterwards.
static void LuaSandbox_LogOnce(const std::string &category, const std::string &detail)
{
    static std::set<std::string> seen;
    if(seen.insert(category).second)
        pLogWarning("Lua sandbox: blocked '%s' (%s). Further blocked calls of this kind will not be logged.",
                    category.c_str(), detail.c_str());
}

/*
 * THE FILE ACCESS POLICY. Tighten/loosen here (or edit the root lists in LuaSandbox_Apply()).
 *  - mode: C fopen-style mode requested by the script.
 *  - Read  ("r","rb"):           canonical path must be inside one of readRoots.
 *  - Write ("w","wb","a","ab"):  canonical path must be inside one of writeRoots.
 *  - Anything else ("r+", "w+", ...) is denied.
 * On success returns true and sets canonOut to the path that must be opened (realpath-resolved,
 * which defeats '..' and symlink escapes). On failure sets why.
 */
static bool LuaSandbox_PathPolicy(const std::string &path, const std::string &mode,
                                  const std::vector<std::string> &readRoots,
                                  const std::vector<std::string> &writeRoots,
                                  std::string &canonOut, std::string &why)
{
    bool write;
    if(mode == "r" || mode == "rb")
        write = false;
    else if(mode == "w" || mode == "wb" || mode == "a" || mode == "ab")
        write = true;
    else
    {
        why = "file mode '" + mode + "' is not permitted";
        return false;
    }

    if(path.empty())
    {
        why = "empty path";
        return false;
    }

    char buf[PATH_MAX];
    std::string canon;
    if(::realpath(path.c_str(), buf))
        canon = buf;
    else if(write)
    {
        // New file: canonicalise the parent directory, then append a plain file name.
        std::string::size_type sl = path.find_last_of('/');
        std::string dir = (sl == std::string::npos) ? std::string(".") : (sl == 0 ? std::string("/") : path.substr(0, sl));
        std::string base = (sl == std::string::npos) ? path : path.substr(sl + 1);
        if(base.empty() || base == "." || base == ".." || !::realpath(dir.c_str(), buf))
        {
            why = "cannot create file";
            return false;
        }
        canon = buf;
        if(canon.back() != '/')
            canon.push_back('/');
        canon += base;
    }
    else
    {
        why = path + ": No such file or directory";
        return false;
    }

    const std::vector<std::string> &roots = write ? writeRoots : readRoots;
    for(const std::string &r : roots)
    {
        if(LuaSandbox_InsideRoot(canon, r))
        {
            canonOut = canon;
            return true;
        }
    }

    why = path + ": access denied by sandbox (" + (write ? "write" : "read") + " outside allowed folders)";
    return false;
}

static void LuaSandbox_ReadStringTable(lua_State *L, int idx, std::vector<std::string> &out)
{
    int n = (int)lua_objlen(L, idx);
    for(int i = 1; i <= n; ++i)
    {
        lua_rawgeti(L, idx, i);
        if(lua_isstring(L, -1))
            out.push_back(lua_tostring(L, -1));
        lua_pop(L, 1);
    }
}

// check(path, mode [, quiet]) -> canonicalPath | nil, errmsg   (upvalues: readRoots, writeRoots tables)
static int LuaSandbox_lua_check(lua_State *L)
{
    size_t plen = 0;
    const char *p = lua_isstring(L, 1) ? lua_tolstring(L, 1, &plen) : nullptr;
    const char *m = lua_isstring(L, 2) ? lua_tostring(L, 2) : "r";
    bool quiet = lua_toboolean(L, 3) != 0;

    std::string why, canon;
    if(!p || ::strlen(p) != plen)
        why = "invalid path";
    else
    {
        std::vector<std::string> rr, wr;
        LuaSandbox_ReadStringTable(L, lua_upvalueindex(1), rr);
        LuaSandbox_ReadStringTable(L, lua_upvalueindex(2), wr);
        if(LuaSandbox_PathPolicy(p, m, rr, wr, canon, why))
        {
            lua_pushstring(L, canon.c_str());
            return 1;
        }
    }

    if(!quiet)
        LuaSandbox_LogOnce("io.open/file access", why);
    lua_pushnil(L);
    lua_pushstring(L, why.c_str());
    return 2;
}

// note(category, detail): log-once helper used by the Lua-side stubs
static int LuaSandbox_lua_note(lua_State *L)
{
    LuaSandbox_LogOnce(luaL_optstring(L, 1, "?"), luaL_optstring(L, 2, ""));
    return 0;
}

// Lua-side part of the sandbox. Receives: check, note, io, os, package, _G
static const char s_luaSandboxShim[] =
"local check, note, io_, os_, package_, G = ...\n"
"local type, tostring, error, select, setfenv, unpack = type, tostring, error, select, setfenv, unpack\n"
"local sbyte, ssub, gsub, gmatch, concat = string.byte, string.sub, string.gsub, string.gmatch, table.concat\n"
"local open, lines, input, output = io_.open, io_.lines, io_.input, io_.output\n"
"local loadstring_ = loadstring\n"
"\n"
"local function guarded_open(name, mode)\n"
"  local canon, err = check(name, mode or 'r')\n"
"  if not canon then return nil, err end\n"
"  return open(canon, mode or 'r')\n"
"end\n"
"io_.open = guarded_open\n"
"io_.lines = function(name, ...)\n"
"  if name == nil then return lines() end\n"
"  local f, err = guarded_open(name, 'r')\n"
"  if not f then error(err, 2) end\n"
"  return f:lines(...)\n"
"end\n"
"io_.input = function(x)\n"
"  if type(x) == 'string' then\n"
"    local f, err = guarded_open(x, 'r')\n"
"    if not f then error(err, 2) end\n"
"    return input(f)\n"
"  end\n"
"  return input(x)\n"
"end\n"
"io_.output = function(x)\n"
"  if type(x) == 'string' then\n"
"    local f, err = guarded_open(x, 'w')\n"
"    if not f then error(err, 2) end\n"
"    return output(f)\n"
"  end\n"
"  return output(x)\n"
"end\n"
"local function stub(tbl, key, cat, retval)\n"
"  tbl[key] = function() note(cat, 'called ' .. cat) return retval, 'disabled by sandbox' end\n"
"end\n"
"stub(io_, 'popen', 'io.popen')\n"
"stub(io_, 'tmpfile', 'io.tmpfile')\n"
"stub(os_, 'execute', 'os.execute')\n"
"stub(os_, 'exit', 'os.exit')\n"
"stub(os_, 'remove', 'os.remove')\n"
"stub(os_, 'rename', 'os.rename')\n"
"stub(os_, 'tmpname', 'os.tmpname')\n"
"os_.getenv = function() note('os.getenv', 'called os.getenv') return nil end\n"
"\n"
"-- text-only chunk loading\n"
"local function compile(src, chunkname)\n"
"  if sbyte(src, 1) == 27 then\n"
"    note('load(binary chunk)', 'bytecode chunks are not allowed')\n"
"    return nil, 'binary chunks are not allowed'\n"
"  end\n"
"  return loadstring_(src, chunkname)\n"
"end\n"
"local function prep_file_source(src)\n"
"  if ssub(src, 1, 3) == '\\239\\187\\191' then src = ssub(src, 4) end\n"
"  if sbyte(src, 1) == 35 then src = gsub(src, '^[^\\n]*', '') end\n"
"  return src\n"
"end\n"
"G.loadstring = function(s, name)\n"
"  if type(s) ~= 'string' then error(\"bad argument #1 to 'loadstring' (string expected, got \" .. type(s) .. ')', 2) end\n"
"  return compile(s, name or s)\n"
"end\n"
"G.load = function(chunk, name, mode, env)\n"
"  local src\n"
"  if type(chunk) == 'string' then src = chunk\n"
"  elseif type(chunk) == 'function' then\n"
"    local parts = {}\n"
"    while true do\n"
"      local p = chunk()\n"
"      if p == nil or p == '' then break end\n"
"      if type(p) ~= 'string' then return nil, 'reader function must return a string' end\n"
"      parts[#parts + 1] = p\n"
"    end\n"
"    src = concat(parts)\n"
"  else error(\"bad argument #1 to 'load' (function expected)\", 2) end\n"
"  local fn, err = compile(src, name or (type(chunk) == 'string' and chunk or '=(load)'))\n"
"  if fn and env ~= nil then setfenv(fn, env) end\n"
"  return fn, err\n"
"end\n"
"G.loadfile = function(name)\n"
"  if name == nil then return nil, 'loading from stdin is disabled' end\n"
"  local f, err = guarded_open(name, 'rb')\n"
"  if not f then return nil, err end\n"
"  local src = f:read('*a'); f:close()\n"
"  return compile(prep_file_source(src or ''), '@' .. name)\n"
"end\n"
"G.dofile = function(name)\n"
"  local fn, err = G.loadfile(name)\n"
"  if not fn then error(err, 2) end\n"
"  return fn()\n"
"end\n"
"string.dump = nil\n"
"\n"
"-- require(): only preload + plain-text .lua files inside allowed roots; no C loaders\n"
"package_.loadlib = nil\n"
"package_.cpath = ''\n"
"local function lua_searcher(name)\n"
"  local fname = gsub(name, '%.', '/')\n"
"  local tried = {}\n"
"  for templ in gmatch(package_.path, '[^;]+') do\n"
"    local p = gsub(templ, '%?', function() return fname end)\n"
"    local canon = check(p, 'r', true)\n"
"    local f = canon and open(canon, 'rb')\n"
"    if f then\n"
"      local src = f:read('*a'); f:close()\n"
"      local fn, err = compile(prep_file_source(src or ''), '@' .. p)\n"
"      if not fn then error(\"error loading module '\" .. name .. \"' from file '\" .. p .. \"':\\n\\t\" .. tostring(err), 0) end\n"
"      return fn\n"
"    end\n"
"    tried[#tried + 1] = \"\\n\\tno file '\" .. p .. \"'\"\n"
"  end\n"
"  return concat(tried)\n"
"end\n"
"local pre = package_.loaders and package_.loaders[1]\n"
"package_.loaders = { pre, lua_searcher }\n"
"if package_.searchers then package_.searchers = package_.loaders end\n"
"\n"
"-- native / introspection libraries\n"
"for _, k in ipairs({'debug', 'ffi', 'jit'}) do G[k] = nil; package_.loaded[k] = nil; package_.preload[k] = nil end\n";

// Applies the sandbox to a freshly opened state (after base/io/os/package were opened, package.path set).
static void LuaSandbox_Apply(lua_State *L, const std::vector<std::string> &scriptPaths)
{
    // ---- allowed roots (edit here to loosen/tighten) ----
    std::vector<std::string> readRoots, writeRoots;
    std::vector<std::string> readCandidates = scriptPaths; // config-pack script dir, episode dir, level custom dir
    readCandidates.push_back(ConfigManager::config_dirSTD); // current config pack
    readCandidates.push_back(AppPathManager::userAppDirSTD()); // Moondust user dir (episodes, mods, configs, ...)
    for(const std::string &c : readCandidates)
    {
        std::string r = LuaSandbox_CanonRoot(c);
        if(!r.empty() && std::find(readRoots.begin(), readRoots.end(), r) == readRoots.end())
            readRoots.push_back(r);
    }
    {
        std::string w = LuaSandbox_CanonRoot(AppPathManager::gameSaveRootDir());
        if(!w.empty())
            writeRoots.push_back(w);
    }

    int top = lua_gettop(L);

    // check closure
    lua_newtable(L);
    for(size_t i = 0; i < readRoots.size(); ++i)
    {
        lua_pushstring(L, readRoots[i].c_str());
        lua_rawseti(L, -2, (int)i + 1);
    }
    lua_newtable(L);
    for(size_t i = 0; i < writeRoots.size(); ++i)
    {
        lua_pushstring(L, writeRoots[i].c_str());
        lua_rawseti(L, -2, (int)i + 1);
    }
    lua_pushcclosure(L, LuaSandbox_lua_check, 2);   // [check]
    lua_pushcfunction(L, LuaSandbox_lua_note);       // [check, note]
    lua_getfield(L, LUA_GLOBALSINDEX, "io");
    lua_getfield(L, LUA_GLOBALSINDEX, "os");
    lua_getfield(L, LUA_GLOBALSINDEX, "package");
    lua_pushvalue(L, LUA_GLOBALSINDEX);              // [check, note, io, os, package, _G]

    bool ok = true;
    {
        // Run the shim with the 6 arguments that are already on the stack.
        if(luaL_loadbuffer(L, s_luaSandboxShim, sizeof(s_luaSandboxShim) - 1, "=sandbox") != 0)
            ok = false;
        else
        {
            lua_insert(L, top + 1); // function below its 6 arguments
            ok = (lua_pcall(L, 6, 0, 0) == 0);
        }
    }
    if(!ok)
    {
        // Fail closed: remove the dangerous libraries entirely
        pLogCritical("Lua sandbox setup FAILED (%s); disabling io/os/package/load functions",
                     lua_isstring(L, -1) ? lua_tostring(L, -1) : "?");
        lua_settop(L, top);
        const char *kill[] = {"io", "os", "debug", "ffi", "jit", "dofile", "loadfile", "load", "loadstring", "require", "module"};
        for(const char *k : kill)
        {
            lua_pushnil(L);
            lua_setfield(L, LUA_GLOBALSINDEX, k);
        }
        return;
    }
    lua_settop(L, top);

    pLogInfo("Lua sandbox active: %d read root(s), %d write root(s)", (int)readRoots.size(), (int)writeRoots.size());
}
#endif // __ANDROID__

void LuaEngine::init()
{
    //First check if lua engine is already active
    if(isValid())
    {
        pLogWarning("LuaEngine: Called init(), while engine is already initialized!");
        return;
    }

    //Add error reporter
    if(!m_errorReporterFunc)
    {
        m_errorReporterFunc = [this](const std::string & errMsg, const std::string & stacktrace)
        {
            pLogWarning("Lua-Error: ");
            pLogWarning("Error Message: %s", errMsg.c_str());
            if(!stacktrace.empty())
                pLogWarning("Stacktrace: \n%s", stacktrace.c_str());
        };
    }

    //Create our new lua state
    L = luaL_newstate();
    if(!L)
    {
        m_errorReporterFunc("Failed to initialize Lua Engine!", "");
        m_lateShutdown = true;
        shutdown();
        return;
    }

    //Add it as global
    LuaGlobal::add(L, this);

    //Open up "safe" standard lua libraries
    // FIXME: Add more accurate sandbox
    lua_pushcfunction(L, luaopen_base);
    lua_call(L, 0, 0);
    lua_pushcfunction(L, luaopen_math);
    lua_call(L, 0, 0);
    lua_pushcfunction(L, luaopen_string);
    lua_call(L, 0, 0);
    lua_pushcfunction(L, luaopen_table);
    lua_call(L, 0, 0);
#if !defined(__ANDROID__) // Android sandbox: no debug library
    lua_pushcfunction(L, luaopen_debug);
    lua_call(L, 0, 0);
#endif
    lua_pushcfunction(L, luaopen_os);
    lua_call(L, 0, 0);
    lua_pushcfunction(L, luaopen_package);
    lua_call(L, 0, 0);
    #ifdef USE_LUA_JIT
    lua_pushcfunction(L, luaopen_bit);
    lua_call(L, 0, 0);
    #if !defined(__ANDROID__) // Android sandbox: no ffi / jit libraries
    lua_pushcfunction(L, luaopen_ffi);
    lua_call(L, 0, 0);
    lua_pushcfunction(L, luaopen_jit);
    lua_call(L, 0, 0);
    #endif
    #endif

    //SOCKET TESTING STUFF
    lua_pushcfunction(L, luaopen_io);
    lua_call(L, 0, 0);

    lua_getfield(L, LUA_GLOBALSINDEX, "package");
    lua_getfield(L, -1, "preload");

    //lua_pushcfunction(L, luaopen_socket_core);
    //lua_setfield(L, -2, "socket.core");

    //lua_pushcfunction(L, luaopen_mime_core);
    //lua_setfield(L, -2, "mime.core");

    //Remove unsafe apis
    {
        luabind::object _G =    luabind::globals(L);
        luabind::object osTable = _G["os"];
        osTable["execute"] =    luabind::object();
        osTable["exit"] =       luabind::object();
        //osTable["getenv"] =   object();
        osTable["remove"] =     luabind::object();
        osTable["rename"] =     luabind::object();
        osTable["setlocal"] =   luabind::object();
        osTable["tmpname"] =    luabind::object();
    }

    //Activate Luabind for out state
    try
    {
        luabind::open(L);
    }
    catch(const std::runtime_error &e)
    {
        // Must not happen: the possible exception
        // may be thrown when "open()" called in a different thread
        pLogCritical("Can't start LuaBind engine: %s", e.what());
        m_lateShutdown = true;
        shutdown();
        return;
    }

    //Add error handler
    luabind::set_pcall_callback(&push_pcall_handler);

    //Complete the lua script path
    std::string fullPaths;
    for(size_t i = 0; i < m_luaScriptPaths.size(); i++)
    {
        std::string luaPath = m_luaScriptPaths[i];
        if(!luaPath.empty() && luaPath.back() != '/')
            luaPath += "/";
        fullPaths += luaPath + "?.lua";
        if(i < (m_luaScriptPaths.size() - 1))
            fullPaths.push_back(';');
    }

    //Add config package path
    if(!fullPaths.empty())
    {
        luabind::object _G = luabind::globals(L);
        luabind::object package = _G["package"];
        std::string allPaths;
        try
        {
            allPaths = luabind::object_cast<std::string>(package["path"]);
        }
        catch(const luabind::cast_failed &e)
        {
            pLogCritical("LuaEngine fail: Exception thrown on attempt to cast 'package[\"path\"]': %s", e.what());
            allPaths = ".";
        }
        //allPaths += std::string(";") +  m_luaScriptPath.toStdString() + "?.lua";
        allPaths += std::string(";") +  fullPaths;
        package["path"] = allPaths;
    }

#if defined(__ANDROID__)
    //Harden the state against untrusted mod scripts (after package.path is final)
    LuaSandbox_Apply(L, m_luaScriptPaths);
#endif

    //Now let's bind our functions
    bindCore();

    //The rest of the functions
    onBindAll();

    //Now read the core file. This file should manage incoming events
    //and process them to all other files.
    std::string coreFilePath = util::resolveRelativeOrAbsolute(m_coreFile, {m_luaScriptPath});
    std::string userFilePath = util::resolveRelativeOrAbsolute(m_userFile, {m_luaScriptPath});

    SdlFile luaCoreFile(coreFilePath);
    if(!luaCoreFile.open(SdlFile::Read))
    {
        pLogWarning("Failed to load up \"%s\"! Wrong path or insufficient access?", coreFilePath.c_str());
        m_lateShutdown = true;
        shutdown();
        return;
    }

    loadMultiRet(&luaCoreFile);
    luaCoreFile.close();

    // OPTIONAL: User code file
    SdlFile luaUserFile(userFilePath);
    if(luaUserFile.open(SdlFile::Read))
    {
        loadMultiRet(&luaUserFile);
        luaUserFile.close();
    }

    LuaEvent initEvent = BindingCore_Events_Engine::createInitEngineEvent(this);
    dispatchEvent(initEvent);
}

void LuaEngine::shutdown()
{
    if(!isValid())
    {
        pLogWarning("LuaEngine: Trying to shutdown invalid lua");
        return;
    }

    loadedFiles.clear();

    // FIXME: Add shutdown event

    forceShutdown();
}

void LuaEngine::forceShutdown()
{
    if(!isValid())
    {
        pLogWarning("LuaEngine: Trying to force shutdown invalid lua");
        return;
    }

    LuaGlobal::remove(L);
    lua_close(L);
    L = nullptr;
}

luabind::object LuaEngine::loadClassAPI(const std::string &path)
{
    ScriptsHash::iterator existFile = loadedFiles.find(path);
    if(existFile != loadedFiles.end())
        return existFile->second;

    SdlFile luaCoreFile(path);
    if(!luaCoreFile.open(SdlFile::Read))
    {
        pLogWarning("Failed to load up \"%s\"! Wrong path or insufficient access?", path.c_str());
        shutdown();
        #ifdef DEBUG_BUILD
        abort();
        #endif
        return luabind::object();
    }

    //Now read our code.
    std::string luaCoreCode = luaCoreFile.readAll();

    //Now load our code by lua and check for common compile errors
    int errorCode = luautil_loadclass(L,
                                      luaCoreCode.data(),
                                      luaCoreCode.length(),
                                      Files::basename(m_coreFile).c_str());
    //If we get an error, then handle it
    if(errorCode)
    {
        pLogWarning("Got lua error, reporting...");
        m_errorReporterFunc(std::string(lua_tostring(L, -1)), std::string(""));
        m_lateShutdown = true;
        return luabind::object();
    }

    luabind::object tReturn(luabind::from_stack(L, -1));
    if(luabind::type(tReturn) != LUA_TUSERDATA)
    {
        pLogWarning("Invalid return type of loading class");
        return luabind::object();
    }

    loadedFiles.insert({path, tReturn});
    return tReturn;
}

void LuaEngine::loadClassAPI(const std::string &nameInGlobal, const std::string &path)
{
    luabind::globals(L)[nameInGlobal] = loadClassAPI(path);
}

std::string LuaEngine::coreFile() const
{
    return m_coreFile;
}

void LuaEngine::setCoreFile(const std::string &coreFile)
{
    if(isValid())
    {
        pLogWarning("Trying to change core lua file while lua engine is already initialized!");
        return;
    }
    m_coreFile = coreFile;
}

void LuaEngine::dispatchEvent(LuaEvent &toDispatchEvent)
{
    if(m_lateShutdown)
        return;

    if(!isValid())
    {
        pLogWarning("Dispatching events while engine is invalid!");
        return;
    }

    lua_getglobal(L, "__native_event");
    if(!lua_isfunction(L, -1))
    {
        pLogWarning("Did not find __native_event function in core!");
        lua_pop(L, 1);
        return;
    }

    try
    {
        luabind::object(L, toDispatchEvent).push(L);

        int argsNum = 0;
        for(luabind::object &obj : toDispatchEvent.objList)
        {
            argsNum++;
            obj.push(L);
        }

        if(luabind::detail::pcall(L, argsNum + 1, 0) != 0)
            throw(std::runtime_error(lua_tostring(L, -1)));
    }
    catch(const std::runtime_error &e)
    {
        //Be sure to cleanup all objects
        toDispatchEvent.cleanupAllParams();
        std::string runtimeErrorMsg = e.what();
        std::string msg1, msg2;
        splitErrorMsg(runtimeErrorMsg, msg1, msg2);
        m_errorReporterFunc(msg1, msg2);
        m_lateShutdown = true;
    }
    catch(...)
    {
        //Be sure to cleanup all objects
        toDispatchEvent.cleanupAllParams();
        std::string runtimeErrorMsg = fmt::format_ne("Thrown unknown exception (Lua error: [{0}])", lua_tostring(L, -1));
        std::string msg1, msg2;
        splitErrorMsg(runtimeErrorMsg, msg1, msg2);
        m_errorReporterFunc(msg1, msg2);
        m_lateShutdown = true;
    }
}


void LuaEngine::bindCore()
{
    luabind::module(L)[
        LuaEvent::bindToLua(),
        Binding_Core_GlobalFuncs_Logger::bindToLua(),
        Binding_Core_GlobalFuncs_Settings::bindToLua(),
        Binding_Core_GlobalFuncs_Audio::bindToLua(),
        Binding_Core_GlobalFuncs_Paths::bindToLua(),
        Binding_Core_Class_SimpleEvent::bindToLua(),
        Binding_Core_Scene_Effects::bindToLua()
    ];
    if(m_baseScene)
    {
        luabind::module(L)[
            Binding_Core_GlobalFuncs_Renderer::bindToLuaFont(),
            Binding_Core_GlobalFuncs_Renderer::bindToLuaRenderer(),
            Binding_Core_GlobalFuncs_Renderer::bindToLuaText(),
            Binding_Core_Graphics::PGETexture_bindToLua(),
            Binding_Core_Graphics::bindToLua(),
            Binding_Core_GlobalFuncs_Effect::bindToLua()
        ];
    }

    //Bind constants
    Binding_Core_GlobalFuncs_Renderer::bindConstants(L);
    Binding_Core_GlobalFuncs_Audio::bindConstants(L);
}

void LuaEngine::error()
{
    pLogWarning("Runtime Lua Error, shutting down");
    shutdown();
}
std::string LuaEngine::getUserFile() const
{
    return m_userFile;
}

void LuaEngine::setUserFile(const std::string &userFile)
{
    m_userFile = userFile;
}

void LuaEngine::loadMultiRet(SdlFile *file, const std::string &name)
{
    SDL_assert(file);
    //Now read our code.
    std::string luaCode = file->readAll();

    //Now load our code by lua and check for common compile errors
    int errorCode = luautil_loadlua(L,
                                    luaCode.data(),
                                    luaCode.length(),
                                    name.data());
    //If we get an error, then handle it
    if(errorCode)
    {
        pLogWarning("Got lua error, reporting... [loadMultiRet] %s", file->fileName().c_str());
        m_errorReporterFunc(std::string(lua_tostring(L, -1)), std::string(""));
        m_lateShutdown = true;
        shutdown();
        return;
    }
}

bool LuaEngine::shouldShutdown() const
{
    return m_lateShutdown;
}

void LuaEngine::setLateShutdown(bool value)
{
    m_lateShutdown = value;
}

void LuaEngine::postLateShutdownError(const char *what)
{
    std::string runtimeErrorMsg = what;
    std::string msg1, msg2;
    splitErrorMsg(runtimeErrorMsg, msg1, msg2);
    m_errorReporterFunc(msg1, msg2);
    m_lateShutdown = true;
}

void LuaEngine::postLateShutdownError(luabind::error &error)
{
    postLateShutdownError(error.what());
}

void LuaEngine::runGarbageCollector()
{
    lua_gc(L, LUA_GCCOLLECT, 0);
}

std::string LuaEngine::getLuaScriptPath() const
{
    return m_luaScriptPath;
}

void LuaEngine::setLuaScriptPath(const std::string &luaScriptPath)
{
    m_luaScriptPaths.clear();
    m_luaScriptPaths.push_back(luaScriptPath);
    m_luaScriptPath = luaScriptPath;
}

void LuaEngine::appendLuaScriptPath(const std::string &luaScriptPath)
{
    m_luaScriptPaths.push_back(luaScriptPath);
}

void LuaEngine::setFileSearchPath(const std::string &path)
{
    Binding_Core_Graphics::setRootPath(path);
}

Scene *LuaEngine::getBaseScene() const
{
    return m_baseScene;
}

void LuaEngine::setErrorReporterFunc(const std::function<void (const std::string &, const std::string &)> &value)
{
    m_errorReporterFunc = value;
}

int pcall_handler(lua_State *L)
{
    std::string msg = "";

    int level = 1;
    lua_Debug d;
    bool gotInfoStacktrace = false;
    while(lua_getstack(L, level, &d))
    {
        gotInfoStacktrace = true;
        lua_getinfo(L, "Sln", &d);
        if(level == 1)
        {
            std::string err = lua_tostring(L, -1);
            lua_pop(L, 1);
            msg += err + "\n";
        }
        else
        {
            std::string nextEntry = "\tat ";

            if(util::strempty(d.short_src))
                nextEntry += std::string(d.short_src) + " ";
            else
                nextEntry += "[unknown codefile] ";
            if(util::strempty(d.what))
                nextEntry += std::string(d.what) + " ";
            else
                nextEntry += "[unknown source] ";
            if(util::strempty(d.namewhat))
                nextEntry += std::string(d.namewhat) + " ";
            else
                nextEntry += "[unknown type] ";
            if(util::strempty(d.name))
                nextEntry += std::string(d.name) + " ";
            else
                nextEntry += "[unknown name] ";
            nextEntry += std::string("at line ") + NUM2STR(d.currentline) + std::string("\n");

            msg += nextEntry;
        }

        level++;
    }

    if(msg.size() > 0)
        msg.erase(msg.end() - 1, msg.end());

    if(!gotInfoStacktrace)
    {
        if(lua_gettop(L) > 0)
            if(lua_type(L, -1) == LUA_TSTRING)
                msg = lua_tostring(L, -1);
    }

    lua_pushstring(L, msg.c_str());
    return 1;
}


void push_pcall_handler(lua_State *L)
{
    lua_pushcfunction(L, pcall_handler);
}
