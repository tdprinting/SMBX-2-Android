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

#include <DirManager/dirman.h>
#include <Utils/files.h>
#include <IniProcessor/ini_processing.h>
#define FMT_NOEXCEPT
#include <fmt/fmt_format.h>

#ifdef __APPLE__
#include <CoreFoundation/CoreFoundation.h>
#include <CoreServices/CoreServices.h>
#include <PGE_File_Formats/pge_file_lib_private.h>//It's only exception for macOS here to get URL-Decode. Never include this!
#include "apple/app_path_macosx.h"
#endif

#ifdef __gnu_linux__
#include <unistd.h>
#include <sys/types.h>
#include <pwd.h>
#endif

#ifdef _WIN32
#include <windows.h>
#include <winreg.h>
#include <algorithm> // std::replace from \\ into /
#endif

#ifdef __EMSCRIPTEN__
#include <emscripten.h>

static bool loadingLocked = false;

extern "C" void unlockLoadingCustomState()
{
    loadingLocked = false;
}

static void loadCustomState()
{
    loadingLocked = true;
    EM_ASM(
        FS.mkdir('/settings');
        FS.mount(IDBFS, {}, '/settings');

        // sync from persisted state into memory and then
        // run the 'test' function
        FS.syncfs(true, function (err) {
            assert(!err);
            ccall('unlockLoadingCustomState', 'v');
        });
    );

    while(loadingLocked)
        emscripten_sleep(10);
}

static void saveCustomState()
{
    loadingLocked = true;
    EM_ASM(
        FS.syncfs(function (err) {
            assert(!err);
            ccall('unlockLoadingCustomState', 'v');
        });
    );

    while(loadingLocked)
        emscripten_sleep(10);
}
#endif

#include "app_path.h"
#include "../version.h"
#include <Utils/dir_list_ci.h>

#include <SDL2/SDL.h>
#include <algorithm>
#include <cstdio>

#ifdef __ANDROID__
#include <SDL2/SDL_system.h> // SDL_AndroidGetExternalStoragePath()
#endif

std::string  ApplicationPathSTD;

std::string AppPathManager::m_settingsPath;
std::string AppPathManager::m_userPath;

#if defined(__ANDROID__) || defined(__APPLE__) || defined(__HAIKU__)
#define UserDirName "/PGE Project"
#else
#define UserDirName "/.PGE_Project"
#endif

/**
 * @brief Adds a trailing '/' to a non-empty path if it is missing
 */
static std::string withSlash(const std::string &path)
{
    if(!path.empty() && (path.back() != '/'))
        return path + "/";
    return path;
}

/**
 * @brief Finds a sub-folder of 'parent' ignoring the letter case (Android storage is case-sensitive).
 * @return Full path with trailing '/' if the folder exists, empty string otherwise
 */
static std::string findSubDirCI(const std::string &parent, const std::string &name)
{
    if(parent.empty())
        return std::string();
    DirListCI ci(parent);
    std::string full = withSlash(parent) + ci.resolveDirCase(name) + "/";
    return DirMan::exists(full) ? full : std::string();
}

/**
 * @brief Lists names of the mod entries (sub-folders and .zip archives) in the mods folder.
 *        Hidden entries (starting with '.') are skipped.
 */
static void listModEntries(const std::string &modsPath, std::vector<std::string> &folders, std::vector<std::string> &zips)
{
    folders.clear();
    zips.clear();
    if(modsPath.empty())
        return;
    DirMan modsDir(modsPath);
    modsDir.getListOfFolders(folders);
    modsDir.getListOfFiles(zips, {".zip"});
    auto isHidden = [](const std::string &n) { return n.empty() || n[0] == '.'; };
    folders.erase(std::remove_if(folders.begin(), folders.end(), isHidden), folders.end());
    zips.erase(std::remove_if(zips.begin(), zips.end(), isHidden), zips.end());
}

#ifdef __ANDROID__
//! Shared-storage user folder. Writable only with the "All files access" permission on Android 11+.
#define MOONDUST_ANDROID_SHARED_DIR "/sdcard/Moondust"

/**
 * @brief Counts sub-folders which contain at least one episode file (.wld/.wldx)
 */
static size_t countEpisodeFolders(const std::string &containerDir)
{
    size_t count = 0;
    if(containerDir.empty())
        return count;
    std::vector<std::string> folders;
    DirMan(containerDir).getListOfFolders(folders);
    for(const std::string &f : folders)
    {
        std::vector<std::string> worlds;
        DirMan(containerDir + f).getListOfFiles(worlds, {".wld", ".wldx"});
        if(!worlds.empty())
            count++;
    }
    return count;
}

/**
 * @brief Counts sub-folders which contain a config pack's main.ini
 */
static size_t countConfigPacks(const std::string &containerDir)
{
    size_t count = 0;
    if(containerDir.empty())
        return count;
    std::vector<std::string> folders;
    DirMan(containerDir).getListOfFolders(folders);
    for(const std::string &f : folders)
    {
        if(Files::fileExists(containerDir + f + "/main.ini"))
            count++;
    }
    return count;
}

/**
 * @brief Counts *.lua files in the mod's script/ folder (informational only, these are NOT loaded yet)
 */
static size_t countLuaScripts(const std::string &modDir)
{
    std::string scriptDir = findSubDirCI(modDir, "script");
    if(scriptDir.empty())
        return 0;
    std::vector<std::string> files;
    DirMan(scriptDir).getListOfFiles(files, {".lua"});
    return files.size();
}

/**
 * @brief Creates a folder if it does not exist yet
 */
static void ensureDir(const std::string &path)
{
    if(!DirMan::exists(path))
        DirMan::mkAbsPath(path);
}

/**
 * @brief Chooses the user folder on Android.
 *        1) /sdcard/Moondust/ if it can be created (needs the "All files access" permission on Android 11+);
 *        2) the app-specific external folder, which needs no permission but is not reachable over USB on Android 11+;
 *        3) empty string: the caller falls back to the app's internal files folder.
 */
static std::string androidUserDirectory()
{
    const std::string shared = MOONDUST_ANDROID_SHARED_DIR;
    ensureDir(shared);
    if(DirMan::exists(shared))
        return shared;

    const char *extDir = SDL_AndroidGetExternalStoragePath();
    if(extDir && (*extDir != '\0'))
    {
        std::string appDir = extDir;
        while(!appDir.empty() && (appDir.back() == '/'))
            appDir.pop_back();
        ensureDir(appDir);
        if(DirMan::exists(appDir))
        {
            std::fprintf(stderr, "== Mods: shared folder %s is not writable, using app-specific folder %s\n",
                         shared.c_str(), appDir.c_str());
            std::fflush(stderr);
            return appDir;
        }
    }

    std::fprintf(stderr, "== Mods: no writable user folder found, falling back to the app files folder\n");
    std::fflush(stderr);
    return std::string();
}

/**
 * @brief Creates the user-visible layout on first run: episodes/, configs/, mods/
 */
static void createAndroidLayout(const std::string &userDir)
{
    const std::string root = withSlash(userDir);
    ensureDir(root + "episodes");
    ensureDir(root + "configs");
    ensureDir(root + "mods");
}

/**
 * @brief Prints the discovered mods to stderr (Android: visible in logcat)
 */
static void logModsOnStartup(const std::string &userDir)
{
    const std::string root = withSlash(userDir);
    std::fprintf(stderr, "== Mods: user folder is %s\n", root.c_str());

    std::string episodes = findSubDirCI(root, "episodes");
    std::fprintf(stderr, "== Mods: episodes/ contains %zu episode folder(s)\n",
                 episodes.empty() ? size_t(0) : countEpisodeFolders(episodes));

    std::string configs = findSubDirCI(root, "configs");
    std::fprintf(stderr, "== Mods: configs/ contains %zu config pack(s)\n",
                 configs.empty() ? size_t(0) : countConfigPacks(configs));

    std::string mods = findSubDirCI(root, "mods");
    std::vector<std::string> folders, zips;
    listModEntries(mods, folders, zips);

    std::fprintf(stderr, "== Mods: discovered %zu mod folder(s) and %zu zip archive(s) in %s\n",
                 folders.size(), zips.size(), mods.c_str());
    for(const std::string &f : folders)
    {
        std::string modDir = mods + f + "/";
        std::fprintf(stderr, "== Mods:   [%s] episodes=%zu configs=%zu lua=%zu (Lua is not loaded yet)\n",
                     f.c_str(),
                     countEpisodeFolders(findSubDirCI(modDir, "episodes")),
                     countConfigPacks(findSubDirCI(modDir, "configs")),
                     countLuaScripts(modDir));
    }
    for(const std::string &z : zips)
        std::fprintf(stderr, "== Mods:   [%s] skipped: zip archives are not supported, extract it into a folder\n", z.c_str());
    std::fflush(stderr);
}
#endif

/**
 * @brief Retreive User Home directory with appending of the PGE user data directory
 * @return Absolute directory path
 */
static std::string getPgeUserDirectory()
{
    std::string path = "";
#if defined(__APPLE__)
    {
        char *base_path = getAppSupportDir();
        if(base_path)
        {
            path.append(base_path);
            SDL_free(base_path);
        }
    }
#elif defined(_WIN32)
    {
        wchar_t pathW[MAX_PATH];
        DWORD path_len = GetEnvironmentVariableW(L"UserProfile", pathW, MAX_PATH);
        assert(path_len);
        path.resize(path_len * 2);
        path_len = WideCharToMultiByte(CP_UTF8, 0,
                                       pathW,       path_len,
                                       &path[0],    path.size(),
                                       0, FALSE);
        path.resize(path_len);
    }
#elif defined(__ANDROID__)
    path = "/sdcard/";
#elif defined(__HAIKU__)
    {
        const char *home = SDL_getenv("HOME");
        path.append(home);
    }
#elif defined(__gnu_linux__)
    {
        passwd *pw = getpwuid(getuid());
        path.append(pw->pw_dir);
    }
#endif
    return path.empty() ? std::string(".") : (path + UserDirName);
}


void AppPathManager::initAppPath()
{
#ifdef __APPLE__
    {
        CFURLRef appUrlRef;
        appUrlRef = CFBundleCopyBundleURL(CFBundleGetMainBundle());
        CFStringRef filePathRef = CFURLGetString(appUrlRef);
        char temporaryCString[PATH_MAX];
        bzero(temporaryCString, PATH_MAX);
        CFStringGetCString(filePathRef, temporaryCString, PATH_MAX, kCFStringEncodingUTF8);
        ApplicationPathSTD = PGE_URLDEC(std::string(temporaryCString));
        {
            std::string::size_type i = ApplicationPathSTD.find_last_of(".app");
            i = ApplicationPathSTD.find_last_of('/', i);
            ApplicationPathSTD.erase(i, ApplicationPathSTD.size() - i);
            if(ApplicationPathSTD.compare(0, 7, "file://") == 0)
                ApplicationPathSTD.erase(0, 7);
            if(!ApplicationPathSTD.empty() && (ApplicationPathSTD.back() != '/'))
                ApplicationPathSTD.push_back('/');
        }
        CFRelease(appUrlRef);
    }
#else //__APPLE__
    char *path = SDL_GetBasePath();
    if(!path)
    {
        std::fprintf(stderr, "== Failed to recogonize application path by using of SDL_GetBasePath! Using current working directory \"./\" instead.\n");
        std::fflush(stderr);
        path = SDL_strdup("./");
    }
    ApplicationPathSTD = std::string(path);
#   if defined(_WIN32)
    std::replace(ApplicationPathSTD.begin(), ApplicationPathSTD.end(), '\\', '/');
#   endif
    SDL_free(path);
#endif

#ifdef __EMSCRIPTEN__
    loadCustomState();
#endif

    if(isPortable())
        return;

#ifdef __ANDROID__
    // Shared "/sdcard/Moondust" if writable, otherwise the app-specific external folder
    std::string userDirPath = androidUserDirectory();
#else
    std::string userDirPath = getPgeUserDirectory();
#endif
    if(!userDirPath.empty())
    {
        DirMan appDir(userDirPath);
        if(!appDir.exists())
        {
            appDir.mkpath(userDirPath);
            if(!appDir.exists()) // Re-check the existance
                goto defaultSettingsPath;
        }
#ifdef __APPLE__
        if(!DirMan::exists(ApplicationPathSTD + "/Data directory"))
            symlink((userDirPath).c_str(), (ApplicationPathSTD + "/Data directory").c_str());
#endif

#ifdef __ANDROID__
        std::string noMediaFile = userDirPath + "/.nomedia";
        if(!Files::fileExists(noMediaFile))
        {
            SDL_RWops *noMediaRWops = SDL_RWFromFile(noMediaFile.c_str(), "wb");
            if(noMediaRWops)
                SDL_RWclose(noMediaRWops);
        }
        createAndroidLayout(userDirPath);
#endif
        m_userPath = appDir.absolutePath();
        m_userPath.push_back('/');
        initSettingsPath();
#ifdef __ANDROID__
        logModsOnStartup(m_userPath);
#endif
    }
    else
    {
        goto defaultSettingsPath;
    }

    return;
defaultSettingsPath:
    m_userPath = ApplicationPathSTD;
    initSettingsPath();
#ifdef __EMSCRIPTEN__
    printf("== App Path is %s\n", ApplicationPathSTD.c_str());
    printf("== User Path is %s\n", m_userPath.c_str());
    fflush(stdout);
#endif
}

std::string AppPathManager::settingsFileSTD()
{
    return m_settingsPath + "pge_engine.ini";
}

std::string AppPathManager::userAppDirSTD()
{
    return m_userPath;
}

std::string AppPathManager::languagesDir()
{
#if defined(__APPLE__)
    CFURLRef appUrlRef;
    appUrlRef = CFBundleCopyResourceURL(CFBundleGetMainBundle(), CFSTR("languages"), NULL, NULL);
    CFStringRef filePathRef = CFURLGetString(appUrlRef);
    char temporaryCString[PATH_MAX];
    bzero(temporaryCString, PATH_MAX);
    CFStringGetCString(filePathRef, temporaryCString, PATH_MAX, kCFStringEncodingUTF8);
    std::string path = PGE_URLDEC(std::string(temporaryCString));
    if(path.compare(0, 7, "file://") == 0)
        path.erase(0, 7);
    return path;
#elif defined(__ANDROID__)
    return "languages";
#else
    return ApplicationPathSTD + "languages";
#endif
}

std::string AppPathManager::logsDir()
{
    return ApplicationPathSTD + "logs";
}

std::string AppPathManager::screenshotsDir()
{
#ifndef __APPLE__
    return m_userPath + "screenshots";
#else
    std::string path = m_userPath;
    char *base_path = getScreenCaptureDir();
    if(base_path)
    {
        path = base_path;
        SDL_free(base_path);
    }
    return path + "/Moondust Game Screenshots";
#endif
}

std::string AppPathManager::gameSaveRootDir()
{
    return m_settingsPath + "gamesaves";
}

void AppPathManager::install()
{
    std::string path = getPgeUserDirectory();

    if(!path.empty())
    {
        DirMan appDir(path);
        if(!appDir.exists())
            appDir.mkpath(path);
    }
}

bool AppPathManager::isPortable()
{
    if(m_settingsPath.empty())
        m_settingsPath = ApplicationPathSTD;

    if(m_userPath.empty())
        m_userPath = ApplicationPathSTD;

    if(!Files::fileExists(settingsFileSTD()))
        return false;

    bool forcePortable = false;

    IniProcessing checkForPort(settingsFileSTD());
    checkForPort.beginGroup("Main");
    forcePortable = checkForPort.value("force-portable", false).toBool();
    checkForPort.endGroup();

    if(forcePortable)
        initSettingsPath();

    return forcePortable;
}

bool AppPathManager::userDirIsAvailable()
{
    return (m_userPath != ApplicationPathSTD);
}

std::string AppPathManager::episodesDir()
{
    return withSlash(m_userPath) + "episodes/";
}

std::string AppPathManager::modsDir()
{
    return withSlash(m_userPath) + "mods/";
}

std::vector<std::string> AppPathManager::modEpisodeDirs()
{
    std::vector<std::string> out;
    std::string episodes = findSubDirCI(m_userPath, "episodes");
    if(!episodes.empty())
        out.push_back(episodes);

    std::string mods = findSubDirCI(m_userPath, "mods");
    std::vector<std::string> folders, zips;
    listModEntries(mods, folders, zips);
    for(const std::string &mod : folders)
    {
        std::string modEpisodes = findSubDirCI(mods + mod, "episodes");
        if(!modEpisodes.empty())
            out.push_back(modEpisodes);
    }
    return out;
}

std::vector<std::string> AppPathManager::modConfigDirs()
{
    std::vector<std::string> out;
    std::string mods = findSubDirCI(m_userPath, "mods");
    std::vector<std::string> folders, zips;
    listModEntries(mods, folders, zips);
    for(const std::string &mod : folders)
    {
        std::string modConfigs = findSubDirCI(mods + mod, "configs");
        if(!modConfigs.empty())
            out.push_back(modConfigs);
    }
    return out;
}

#ifdef __EMSCRIPTEN__
void AppPathManager::syncFs()
{
    saveCustomState();
}
#endif


void AppPathManager::initSettingsPath()
{
    m_settingsPath = m_userPath + "settings/";

    if(Files::fileExists(m_settingsPath))
        Files::deleteFile(m_settingsPath);//Just in case, avoid mad jokes with making same-named file as settings folder

    if(!DirMan::exists(m_settingsPath))
        DirMan::mkAbsPath(m_settingsPath);

    // Also make the gamesaves root folder to be exist
    if(!DirMan::exists(gameSaveRootDir()))
        DirMan::mkAbsPath(gameSaveRootDir());
}
