# In-app Mod Browser (Android)

Opens a WebView browser (`ModBrowserActivity`) to find, download and install SMBX 2 mods.

## Opening it
- Gamepad: hold SELECT + START for about 1.2 s (handled in `moondustActivity.dispatchKeyEvent`; the keys still reach the game).
- Touch (no gamepad connected): "Mods" button at the top-right of the screen.
- Engine/JNI: instance method `openModBrowser()` `()V` on the activity from `SDL_AndroidGetActivity()`, or static `moondustActivity.openModBrowserStatic()` `()V`.

## Controls inside the browser
Touch, or gamepad: D-pad moves focus/links, A clicks, B = back (closes at the first page), SELECT = jump between toolbar and page, Y = focus the address bar, left stick scrolls.

## Configuration (constants)
- Home page, search URL, download size cap: top of `ModBrowserActivity.java` (`HOME_URL`, `SEARCH_URL`, `MAX_DOWNLOAD_BYTES`).
- Install destination: `ModInstaller.ROOT_DIR_NAME / EPISODES_SUBDIR / CONFIGS_SUBDIR` (default `/sdcard/Moondust/{episodes,configs}`, matching `app_path.cpp`).

## Install rules
- `.zip`: contains `.wld/.wldx` -> `episodes/<name>/`; only `.lvl/.lvlx` -> `episodes/<name>/` (single level); config-pack markers (`main.ini`, `lvl_blocks.ini`, ...) -> `configs/<name>/`. A single top-level folder in the zip is used as `<name>` and not nested twice.
- Single `.lvl/.lvlx/.wld/.wldx`: put into `episodes/<file name>/`.
- Existing destination: Replace / Keep both / Cancel.
- `.rar`, `.7z`: not supported, the user is told to extract with a file manager (no dependency added).
- After install the engine is not notified; the new folder shows up when the game next scans (restart the game if needed).

## Security
Zip-slip protection (reject `..`, absolute paths, canonical-path check against a staging folder), at most 20000 entries, 1 GB/file and 2 GB total counted on real bytes, entries are always written as regular files (no symlinks), native/executable types (`.so .dll .exe .apk .dex .jar .sh ...`) are skipped, install goes to a staging folder then renamed (no half installs). Downloads: cache dir only, 512 MB cap, no https->http redirects, cookies only for the original host. WebView: no file/content access, mixed content blocked, SSL errors cancel, non-http(s) links blocked, remote debugging only in debuggable builds. A one-time dialog warns that mod Lua scripts run inside the engine.
