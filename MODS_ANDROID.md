# Mods on Android (Moondust / SMBX2 engine)

This page describes the user-installable content folder on Android, written for the AYN Odin 2 Portal (Android 13, arm64), and what is and is not wired up. The status below was checked against the source, and it has not been run on a device.

## Status

| Item | State |
| --- | --- |
| Shared folder `/sdcard/Moondust/` created on first run, with fallbacks | Implemented (`Engine/common_features/app_path.cpp`) |
| Startup log summary of episodes, config packs and mods | Implemented, `== Mods:` lines on stdout/stderr (see `ANDROID.md`, "Logs") |
| Config packs in `configs/` shown on the config-select screen | Implemented |
| Config packs in `mods/<name>/configs/` shown on the config-select screen | Implemented (`AppPathManager::modConfigDirs()`) |
| Episodes in `episodes/` and `mods/<name>/episodes/` shown in the Episode menu | Implemented (`AppPathManager::modEpisodeDirs()`, used by `ttl_main_menu.cpp`) |
| Standalone levels in those folders shown in the level menu | Implemented (one folder deep, skipping folders that contain a world map) |
| Mod `.zip` archives placed in `mods/` | **Not supported.** They are logged as skipped. Extract them into a folder. The in-app mod browser extracts zips into `episodes/` and `configs/` itself. |
| Lua scripts in `mods/<name>/script/` | **Not loaded.** They are only counted in the log. |
| Lua in config packs | Runs in the restricted Android sandbox (see "Lua security") |
| Tested on hardware | No |

## Folder layout

```
/sdcard/Moondust/                      user folder (shown as "Moondust" in the file manager)
  configs/<PackName>/main.ini          config packs
  episodes/<EpisodeName>/*.wld         shared episodes
  mods/<ModName>/episodes/<Episode>/   episodes shipped by a mod
  mods/<ModName>/configs/<PackName>/   config packs shipped by a mod
  mods/<ModName>/script/*.lua          not loaded
  settings/                            created by the engine (pge_engine.ini, gamesaves/)
  screenshots/                         screenshots folder used by the engine
  .nomedia                             created by the engine, keeps Android media scanners out
```

The engine creates `episodes/`, `configs/` and `mods/` on first run.

Folder names `episodes`, `configs`, `mods` and `script` are matched case-insensitively. Files inside an episode folder (custom graphics, `folders.ini`, and so on) are also matched case-insensitively by the engine's custom-data lookup. Files that come from the config pack itself are not, so their names must match the config pack's case exactly.

An episode is a folder containing a `.wld` or `.wldx` file. Levels (`.lvl` / `.lvlx`) sit in the same folder as the episode's world map. A folder with `.lvl` / `.lvlx` files and no world map is listed as a standalone level.

## Where the folder lives

1. **Preferred:** `/sdcard/Moondust/`. This needs the "All files access" permission (Android 11+ scoped storage blocks creating folders at the root of shared storage otherwise). Grant it in Settings > Apps > Special app access > All files access > Moondust.
2. **Fallback:** if `/sdcard/Moondust/` is not writable, the app uses its app-specific external folder under `Android/data/` (the exact path is printed at startup; the `.debug` package has its own folder). This works without the permission, but Android 11+ does not allow USB or MTP copies into `Android/data`, so use this path only for on-device testing.
3. **Last resort:** if neither is writable, the app uses its internal files folder. Mods cannot be installed from outside in that case.

The chosen folder is printed at startup.

## Installing a mod on the Odin 2 Portal

1. Grant Moondust "All files access" (see `ANDROID.md`). Launch the app once so it creates the folders.
2. Connect the Odin 2 Portal to a PC over USB and set USB mode to "File transfer".
3. Open `Moondust/mods/`. Create a folder for the mod, for example `mods/MyMod/`, and copy the mod's `episodes/` and/or `configs/` folders into it. Mods shipped as a zip must be extracted first.
4. Disconnect. You do not need to restart the game for the episode or config lists to update: the Episode menu and the config-select screen scan their folders when they open. The startup summary in the log is printed only at startup.
5. To check what was found, look at the `== Mods:` lines in the log, for example `adb logcat | grep "Mods:"`. You should see one line per mod with the number of episodes, config packs and Lua files found.

You can also use an on-device file manager that has All-files access. Copy the same folders there.

## Limitations

- **Zip archives in `mods/` are not supported.** Extract them into a folder first. (The in-app mod browser handles zips itself, see `MOD_BROWSER.md`.)
- **Lua from `mods/*/script/` is not executed.** The count in the log is informational only.
- **Mod assets are only used through the engine's existing per-episode mechanisms.** The engine loads custom assets from the episode folder and the `folders.ini` list. Mod-level asset overrides are not implemented.
- **Earlier builds used `/sdcard/PGE Project/`.** That folder is no longer the user folder. Copy `settings/` over by hand if you want to keep old settings.
- **Not run on a device yet.** The code was checked by reading and by host syntax checks only.

## Lua security

Config packs run Lua, including packs shipped inside a mod or installed by the mod browser. On Android that Lua runs in a restricted state (`Engine/script/lua_engine.cpp`, change in progress):

- `io.open`, `io.lines`, `io.input` and `io.output` are checked against a file access policy. Reads are allowed only inside the read roots, and writes only inside the write roots.
- `io.popen` and `io.tmpfile` are disabled. `os.execute`, `os.exit`, `os.remove`, `os.rename` and `os.tmpname` are disabled, and `os.getenv` returns nil.
- `package.loadlib` is removed. `loadfile` and `dofile` go through the same path check. Binary chunks are refused.
- The `debug`, `ffi` and `jit` libraries are not loaded on Android.

The exact file policy is in `LuaSandbox_PathPolicy()` and `LuaSandbox_Apply()` in `Engine/script/lua_engine.cpp`. That file is being changed, so check the code for the current rules. Only install config packs and mods from sources you trust.
