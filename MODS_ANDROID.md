# Mods on Android (Moondust / SMBX2 engine)

This page describes the user-installable content folder on Android, written for the AYN Odin 2 Portal (Android 13, arm64), and what is and is not wired up yet.

## Status

| Item | State |
| --- | --- |
| Shared folder `/sdcard/Moondust/` created on first run, with fallback | Implemented (`Engine/common_features/app_path.cpp`) |
| Startup log listing discovered episodes, config packs and mods | Implemented, `== Mods:` lines in logcat |
| Config packs in `configs/` shown on the config-select screen | Works through the engine's existing scan, once the shared folder is writable |
| Episodes in `episodes/` and `mods/<name>/episodes/` shown in the Episode menu | Discovered and logged, **not yet listed in the menu** (needs a small patch to `Engine/scenes/title/ttl_main_menu.cpp`) |
| Config packs in `mods/<name>/configs/` shown on config-select | Discovered, **not yet listed** (needs a patch to `Engine/data_configs/config_select_scene/scene_config_select.cpp`) |
| Mod `.zip` archives | **Not supported**. Extract them into a folder. They are logged as skipped. |
| Lua scripts from mods | **Not loaded.** Lua in `script/` is only counted in the log. See the Lua risk section. |

## Folder layout

```
/sdcard/Moondust/                      user folder (shown as "Moondust" in the file manager)
  configs/<PackName>/main.ini          config packs (scanned by the engine today)
  episodes/<EpisodeName>/*.wld         shared episodes (pending menu wiring)
  mods/<ModName>/episodes/<Episode>/   episodes shipped by a mod (pending menu wiring)
  mods/<ModName>/configs/<PackName>/   config packs shipped by a mod (pending menu wiring)
  mods/<ModName>/script/*.lua          reserved, not loaded
  settings/                            created by the engine (pge_engine.ini)
  gamesaves/                           created by the engine
  .nomedia                             created by the engine, keeps Android media scanners out
```

Folder names are matched case-insensitively for `episodes`, `configs`, `mods` and `script`. Files inside an episode folder (custom graphics, `folders.ini`, etc.) are also matched case-insensitively by the engine's existing custom-data lookup. Files that come from the config pack itself are not, so their names must match the config pack's case exactly.

An episode is a folder containing a `.wld` or `.wldx` file. Levels (`.lvl`/`.lvlx`) sit in the same folder as the episode's world map.

## Where the folder lives

1. **Preferred:** `/sdcard/Moondust/`. This needs the "All files access" permission (Android 11+ scoped storage blocks creating folders at the root of shared storage otherwise). Grant it in Settings > Apps > Special app access > All files access > Moondust.
2. **Fallback:** if `/sdcard/Moondust/` cannot be created, the app uses its app-specific external folder, `/storage/emulated/0/Android/data/ru.wohlsoft.moondust/files/` (`.debug` suffix for debug builds). This works without permission, but Android 11+ does not allow USB/MTP copies into `Android/data`. Use this path only for on-device testing.
3. **Last resort:** if neither is writable, the app uses its internal files folder. Mods cannot be installed from outside in that case.

The chosen folder is printed to logcat at startup.

## Installing a mod on the Odin 2 Portal

1. Grant Moondust "All files access" (see above). Launch the app once so it creates the folders.
2. Connect the Odin 2 Portal to a PC over USB and set USB mode to "File transfer".
3. Open `Moondust/mods/`. Create a folder for the mod, for example `mods/MyMod/`, and copy the mod's `episodes/` and/or `configs/` folders into it.
4. Disconnect and fully restart Moondust. Mods are scanned once at startup.
5. Check the result in logcat (filter `== Mods`). You should see one line per mod with the number of episodes, config packs and Lua files found.

You can also use an on-device file manager that has All-files access. Copy the same folders there.

## Limitations

- **Zip archives are not supported.** Extract them into a folder first.
- **Lua from mods is not executed.** The count in the log is informational only.
- **Episode and config discovery in `episodes/` and `mods/*` is pending menu wiring.** Until that patch is applied, only `configs/` in the user folder is actually visible in the app.
- **Mod assets are only used through the engine's existing per-episode mechanisms.** The engine loads custom assets from the episode folder and the `folders.ini` list. Mod-level asset overrides are not implemented.
- **The old settings folder is not migrated.** Previous builds stored settings in `/sdcard/PGE Project/`. The new folder is `/sdcard/Moondust/`. Copy `settings/` over by hand if needed.
- **The `episodes/` folder name is new.** The engine's default worlds folder is `<config_id>_worlds`, derived from the config pack. Whatever `worlds` key a config pack sets in its `main.ini` still controls where that pack looks for episodes.
- **Not run on a device yet.** The code was checked by reading and by host syntax checks only.

## Lua risk

**Important:** config packs already run Lua today. The `main_*.lua` scripts named in a config pack's `engine.ini`, and the level/world scripts it references, are executed by the engine. So a config pack in `configs/`, including one shipped inside a mod or installed by the mod browser, runs with the full privileges described below. Only install config packs from sources you trust.

The Lua state in `Engine/script/lua_engine.cpp` (around lines 146-193) is not a sandbox. Today it opens:

- `io` in full (`io.open`, `io.popen`, `io.lines`, `io.output`). A script can read and write any file the app can access, including `/sdcard`, and can run shell commands through `io.popen`.
- `os`, with only `execute`, `exit`, `remove`, `rename`, `setlocal` and `tmpname` removed. `os.getenv` remains.
- `package` in full, including `package.loadlib`, which loads native code from a path.
- `loadfile`/`dofile` from the base library. These read arbitrary files.
- `debug` in full.
- `ffi` and `jit` when built with LuaJIT (`PGE_USE_LUAJIT`). `ffi.C` and `ffi.load` run arbitrary native code.

Before Lua from mods is loaded, remove or restrict these in `lua_engine.cpp`. At minimum: drop `io`, `ffi`, `jit` and `debug`, remove `loadfile`, `dofile` and `package.loadlib`, and keep only a safe `os` subset (`time`, `clock`, `date`). Mod scripts should also be loaded only from the mod's own folder.
