# In-app Mod Browser (Android)

Opens a WebView browser (`ModBrowserActivity`) to find, download and install SMBX 2 mods. Installation is handled by `ModInstaller`. Both are in `Engine/android-project/moondust/src/main/java/ru/wohlsoft/moondust/`.

## Opening it

- **Gamepad:** hold SELECT + START for about 1.2 s. This is handled in `moondustActivity.dispatchKeyEvent`, and the keys still reach the game.
- **Touch (no gamepad connected):** the "Mods" button at the top-right of the screen. It is shown only with the touch overlay.
- **Engine/JNI:** instance method `openModBrowser()` `()V` on the activity, or static `moondustActivity.openModBrowserStatic()` `()V`.

## Controls inside the browser

Touch, or gamepad: D-pad moves focus and links, A clicks, B goes back (and closes the browser at the first page), SELECT jumps between the toolbar and the page, Y focuses the address bar, and the left stick scrolls.

## Configuration (constants)

Top of `ModBrowserActivity.java`: `HOME_URL` (a search page for SMBX2 episodes), `SEARCH_URL` and `MAX_DOWNLOAD_BYTES` (512 MB).

Top of `ModInstaller.java`: `ROOT_DIR_NAME = "Moondust"`, `EPISODES_SUBDIR = "episodes"`, `CONFIGS_SUBDIR = "configs"`, and the limits in the Security section.

Installs go to `/sdcard/Moondust/episodes/` and `/sdcard/Moondust/configs/`, which is the same user folder described in `ANDROID.md`. They do not go into `mods/`.

## Install rules

- **`.zip`:**
  - contains `.wld` / `.wldx` files: installed to `episodes/<name>/`;
  - only `.lvl` / `.lvlx` files: installed to `episodes/<name>/` as a single level;
  - config-pack markers (`main.ini`, `lvl_blocks.ini`, ...): installed to `configs/<name>/`.
  - A single top-level folder in the zip is used as `<name>` and is not nested twice.
- **Single `.lvl`, `.lvlx`, `.wld` or `.wldx` file:** installed to `episodes/<file name>/`.
- **Existing destination:** choose Replace, Keep both, or Cancel.
- **`.rar` and `.7z`:** not supported. The user is told to extract the archive with a file manager. No extraction library is included.
- **After install:** the engine is not notified. The Episode menu and the config-select screen scan their folders when they open, so the new content appears the next time you open them.

## Security

- **Archive extraction:** zip-slip protection (rejects `..`, absolute paths and entries that resolve outside the staging folder), at most 20000 entries, 1 GB per file and 2 GB total on real (uncompressed) bytes. Entries are always written as regular files, so archive symlinks cannot create links.
- **Blocked file types:** native and executable files (`.so`, `.dll`, `.exe`, `.apk`, `.dex`, `.jar`, `.sh`, `.bat`, `.cmd`, `.msi`, `.dylib`, `.com`, `.scr`) are skipped.
- **Atomic install:** files are extracted to a `.staging-*` folder inside `/sdcard/Moondust` and then renamed into place, so a failed install leaves no half-written folder.
- **Downloads:** written only to the app cache (`mod_downloads`), capped at 512 MB. Redirects from https to http are refused. Third-party cookies are disabled.
- **WebView:** file and content access disabled, mixed content never allowed, only http and https links are followed. Remote debugging is enabled only in debuggable builds. SSL errors are not overridden, so the WebView default (cancel the load) applies.
- **Lua warning:** a one-time dialog warns that mod Lua scripts run inside the engine. Mod Lua runs in the restricted Android sandbox (see the "Mods and security" section of `ANDROID.md`).

## Known limitations

- Not tested on the device.
- `.rar` and `.7z` archives must be extracted first.
- Downloads are limited to 512 MB. Archives are limited to 20000 entries, 1 GB per file and 2 GB total.
- The browser follows the sites you open. It is not a curated mod catalogue.

## UI theme

The native Android screens (mod browser, install dialogs and the touch-overlay "Mods" button) use a console-inspired look: charcoal background, light-gray panels, chunky beveled buttons and a red/green/blue/yellow accent strip. It is original styling with no logos or fonts. The engine's own SDL menus are not affected.

- **Colours:** `n64_*` entries in `res/values/colors.xml`. Blue = confirm/action (Go, Install, Mods), green = back, red = cancel/close/destructive (Replace), yellow = focus.
- **Focus:** every button and the address field draws a thick yellow ring when focused, so D-pad navigation is always visible. Pressed buttons sink.
- **Code:** `N64Ui.java` holds the shared helpers; drawables are `res/drawable/n64_*.xml`; themes are `N64.Activity` and `N64Dialog` in `res/values/styles.xml`.
- **Not themed:** the storage-permission dialogs in `moondustActivity`.
