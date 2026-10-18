# Moondust Engine on Android

Android port of the Moondust Engine (PGE Project) runtime. The target device is the AYN Odin 2 Portal (arm64-v8a, Android 13, 1080p, built-in gamepad). Only the engine is covered here (`Engine/`), not the editor.

## Status

- **Experimental.** Untested on hardware, built by CI which has not yet run for the current code. Everything below about device behaviour comes from reading the source.
- The Gradle and CMake project is set up to build the APK. The debug APK is the one to install.
- **Scope:** this is the Moondust (PGE) Engine. It plays SMBX64-format levels and episodes through config packs. It is not SMBX2 and does not include LunaLua. Some Lua API functions are LunaLua-compatible (for example the `Text` namespace), but scripts that depend on the LunaLua runtime or on Windows-only `ffi` will not work.
- The window is landscape only (`sensorLandscape` in the manifest).

## Known limitations

- **Untested on hardware.** Not run on an Odin 2 Portal or any other Android device yet.
- **No OpenGL renderer on Android.** Both GL renderers are compiled out for `__ANDROID__` (`Engine/graphics/render/render_platform_support.h`), so the engine uses the software SDL renderer.
- **Archives:** `.rar` and `.7z` are not supported. The mod browser tells the user to extract them with a file manager. Mod `.zip` files placed in `mods/` by hand are skipped (see `MODS_ANDROID.md`).
- **SMBX2 content** that relies on LunaLua or Windows `ffi` will not work.
- **Case-sensitive filesystem:** file names inside config packs must match case exactly. Only the folder names `episodes`, `configs`, `mods` and `script` (and custom files inside episode folders) are matched case-insensitively.
- **Submodules are not fully registered.** `.gitmodules` lists the native dependencies, but their gitlinks are not yet in the git index, so a plain clone does not fetch them. Until that is fixed, the native build has no SDL2 or other dependency sources.
- **Touch layout** is hard-coded for a 1024x600 canvas (`Engine/controls/controller_touchscreen.cpp`). The JNI hooks `setKeyPos` and `setCanvasSize` exist, but no Java code calls them. Hit boxes on a 1080p screen are unverified.
- **No content ships with the APK.** There is no config pack, episode, SMBX graphics or music. `Content/` is not bundled.

## Layout

- `Engine/android-project/` - Gradle project (`build.gradle`, `settings.gradle`, `gradlew`) with one module, `moondust/`.
- `moondust/src/main/java/ru/wohlsoft/moondust/moondustActivity.java` - `SDLActivity` subclass that loads `libpge_engine.so`, shows the touch overlay and handles the Select+Start shortcut.
- `moondust/src/main/java/ru/wohlsoft/moondust/ModBrowserActivity.java` and `ModInstaller.java` - in-app mod browser and installer (see `MOD_BROWSER.md`).
- `moondust/src/main/java/org/libsdl/app/` - SDL 2 Java glue. `SDLActivity.java` hard-codes the SDL version it expects (2.29.0); it must match the native SDL2 in `_Libs/AudioCodecs/SDL2`. CI checks this.
- `Engine/CMakeLists.txt` - builds the `pge_engine` shared library when `ANDROID` is set. Gradle's `externalNativeBuild` points at it.
- `Engine/common_features/app_path.cpp` - user folder logic (see "Where content goes").

## Build

### Prerequisites

| Item | Version | Where it is set |
|---|---|---|
| JDK | 17 | CI uses Temurin 17 |
| Gradle | 8.2.1 | `Engine/android-project/gradle/wrapper/gradle-wrapper.properties` (downloaded by `gradlew`) |
| Android Gradle Plugin | 8.2.2 | `Engine/android-project/build.gradle` |
| Android SDK | `compileSdk 34`, `targetSdk 34`, `minSdk 24`, build-tools 34.0.0 | `moondust/build.gradle` |
| NDK | `26.1.10909125` | `moondust/build.gradle` (`ndkVersion`); CI installs the same version |
| CMake | `3.22.1` | `moondust/build.gradle` (`externalNativeBuild { cmake { version } }`); CI installs the same version |
| Ninja | any recent | CI installs `ninja-build` |
| git | on `PATH` | `moondust/build.gradle` runs `git rev-parse` for the version name. Without git it falls back to `nogit`. |

Target ABI is `arm64-v8a` only. `-PALL_ABIS` builds `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, which is slower.

### Get the source with submodules

Build from a git checkout, not a source archive, because `moondust/build.gradle` reads the git hash and the native dependencies are git submodules (see `.gitmodules`):

```sh
git clone <repo-url> SMBX-2-Android
cd SMBX-2-Android
git submodule update --init --recursive
```

For an existing clone, run only the last line. Note the caveat about unregistered gitlinks in "Known limitations".

### Android Studio

Open the `Engine/android-project` folder (not the repository root). Let Gradle sync, install the SDK, NDK and CMake versions above, select the `debug` variant, and run.

### Command line

```sh
cd Engine/android-project
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Gradle needs the Android SDK location. Android Studio writes it to `local.properties`; otherwise set `ANDROID_HOME`.

Output: `Engine/android-project/moondust/build/outputs/apk/debug/moondust-debug.apk`.

### Build variants

- **`debug`** (install this one): application ID `ru.wohlsoft.moondust.debug`, version suffix `-DEVDEBUG`, signed with the default debug key. It installs next to a release build.
- **`release`**: no signing config, so the APK is **unsigned and cannot be installed**. CI builds it as a best-effort check only.
- **`releaseci`**: expects `Engine/android-project/release-key.jks` and the environment variables that `moondust/build.gradle` reads (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`, `PIN_ALIAS`, `DB_PASS_ALIAS`). The keystore is not in the repository. The root `.gitignore` excludes `*.jks` and `*.keystore`. Never commit keystores.

### Continuous integration

`.github/workflows/android.yml` runs on pushes to `main` and the working branch, and on manual dispatch. It:

1. checks out the repository with `submodules: recursive`;
2. checks that the native SDL2 version (`_Libs/AudioCodecs/SDL2/include/SDL_version.h`) matches the constants in `SDLActivity.java`, and fails early with a message if they differ or the header is missing;
3. installs SDK platform 34, build-tools 34.0.0, CMake 3.22.1 and NDK 26.1.10909125;
4. builds `assembleDebug` (the primary artifact) and then `assembleRelease` (unsigned, `continue-on-error`);
5. uploads the APKs as the `moondust-android-apks` artifact.

## Install on the Odin 2 Portal

Standard sideloading. Menu names vary by Android skin, so check the labels on your device.

1. Enable Developer options (Settings > About, then tap the build number several times) and USB debugging.
2. Install over USB:
   ```sh
   adb install -r Engine/android-project/moondust/build/outputs/apk/debug/moondust-debug.apk
   ```
   Or copy the APK to the device and open it from a file manager. Allow that file manager to install unknown apps.
3. Start **Moondust Engine**.
4. **Storage permission ("All files access").** On Android 11 and newer, the first launch opens the "All files access" page for Moondust and then closes the game. Turn the permission on, then start the game again. The engine needs it to use `/sdcard/Moondust`.
   - The prompt is shown only once. If you decline, the game runs from the app-specific folder instead (see "Where content goes"). To switch to the shared folder later, grant the permission in Settings > Apps > Moondust > Special app access > All files access, then restart the game.

## Where content goes

The user folder is `/sdcard/Moondust/` (`MOONDUST_ANDROID_SHARED_DIR` in `app_path.cpp`). Earlier builds used `/sdcard/PGE Project/`; that path is no longer used. On first run the engine creates `episodes/`, `configs/` and `mods/`, plus a `.nomedia` file so media scanners skip the folder.

```
/sdcard/Moondust/
├── configs/<PackName>/           config packs (main.ini, data/, ...)
├── episodes/<EpisodeName>/       episode folders: *.wld / *.wldx, and levels *.lvl / *.lvlx
├── mods/<ModName>/               mods (see MODS_ANDROID.md)
│   ├── episodes/<EpisodeName>/
│   └── configs/<PackName>/
├── settings/                     pge_engine.ini, gamesaves/
├── screenshots/
└── .nomedia
```

- **Config pack (required).** The engine lists the folders in `configs/` and in `mods/<name>/configs/` on the config-select screen. A pack needs `main.ini` and a `data/` folder. Config packs are not in this repository. Use an SMBX64-standard pack.
- **Episodes.** The Episode menu scans the worlds folder named by the config pack's `worlds` key in `main.ini`, plus `episodes/` and `mods/<name>/episodes/`. Standalone levels can sit directly in those folders.
- **Fallback folders.** If `/sdcard/Moondust` cannot be created or written (for example, no All files access), the engine uses the app's own external folder under `Android/data/` (the exact path is printed at startup). If that is not writable either, it uses the app's internal files folder. Mods cannot be installed from outside in that case.
- **Translations.** The build copies `Engine/languages/*.qm` into the APK's `assets/languages` (Gradle task `copyEngineLanguages`, and CMake also copies there). Only `engine_en.qm` is committed. Other `.qm` files exist only when `lrelease` is configured, and then CMake writes them to the build directory instead.
- **Logs.** The engine writes its messages to stdout and stderr, which SDL forwards to logcat. Filter by text, for example `adb logcat | grep "Mods:"` for the startup mod summary. The exact logcat tag is not verified.

## Controls (Odin 2 Portal)

Not tested on the device. What the code does:

- **Touch is the default.** Without a gamepad, the on-screen touch controls are active (`controller_touchscreen.cpp`).
- **A connected gamepad takes over.** When a game controller is found through SDL, it replaces the touch controls and the touch overlay is hidden (`refreshPad()` and `processPad()` in `controller_touchscreen.cpp`). The Odin's built-in pad is expected to show up as a game controller, but this is not verified on a device.
- **Rebinding.** Options > Controls > Player 1 controls lets you change the input source (the `Joystick: <name>` entry in `ttl_main_menu.cpp`) and the bindings.
- **Mod browser shortcut.** Holding Select+Start for about 1.2 seconds on a gamepad opens the mod browser (see `MOD_BROWSER.md`). With touch, use the "Mods" button at the top-right of the screen.

## Mods and security

- Mods, config packs and the in-app browser are described in `MODS_ANDROID.md` and `MOD_BROWSER.md`.
- **Mod Lua is restricted on Android.** Scripts run in a sandbox (`Engine/script/lua_engine.cpp`, in progress):
  - `io.open`, `io.lines`, `io.input` and `io.output` are checked against a file access policy: reads are allowed only inside the read roots, and writes only inside the write roots.
  - `io.popen` and `io.tmpfile` are disabled. `os.execute`, `os.exit`, `os.remove`, `os.rename` and `os.tmpname` are disabled, and `os.getenv` returns nil.
  - `package.loadlib` is removed. `loadfile` and `dofile` go through the same path check. Binary chunks are refused.
  - The `debug`, `ffi` and `jit` libraries are not loaded on Android.
  - The exact policy is in `LuaSandbox_PathPolicy()` and `LuaSandbox_Apply()` in that file, which is being changed. Treat the code as the reference.
- Install config packs and mods only from sources you trust.

## Licensing

- Moondust Engine is dual-licensed under MIT or GPL version 3 or later, at your option. Full texts: `Engine/LICENSE.mit` and `Engine/LICENSE.gpl3`. The root `LICENSE` is the GPLv3 text.
- `moondust/src/main/java/org/libsdl/app/` is SDL's Java glue. It is governed by SDL's license, not Moondust's dual license. The copies here do not carry a license header.
- SMBX assets (graphics, music, levels from the original games) are not part of this repository, and the APK does not include them. Use only content you have the rights to.
