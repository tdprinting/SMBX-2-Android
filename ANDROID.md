# Moondust Engine on Android

Android port of the Moondust Engine (PGE Project) runtime. The target is the AYN Odin 2 Portal (arm64-v8a, Android 13, 1080p, built-in gamepad). Only the engine is covered here (`Engine/`), not the editor.

## Status

- **Experimental.** The Android project is set up to build with Gradle and CMake, and is meant to run on GitHub Actions. The APK has not been run on an Odin 2 Portal or any other device.
- **Not yet verified:** a local build from this checkout, a passing CI run for the current code, and on-device behavior (startup, storage access, touch layout, gamepad input, audio).
- **Scope:** this is the Moondust (PGE) Engine, which plays SMBX64-format levels and episodes through config packs. It is not SMBX2 and does not include LunaLua. The Lua API has some LunaLua-compatible functions (for example the `Text` namespace), but scripts that depend on the LunaLua runtime will not work.
- Android uses a landscape-only window and hides the fullscreen toggle (`Engine/scenes/title/ttl_main_menu.cpp`).

## Layout

- `Engine/android-project/` - Gradle project (`build.gradle`, `settings.gradle`, `gradlew`) with one module, `moondust/`.
- `moondust/src/main/java/ru/wohlsoft/moondust/moondustActivity.java` - `SDLActivity` subclass that loads `libpge_engine.so`.
- `moondust/src/main/java/org/libsdl/app/` - SDL 2 Java glue.
- `Engine/CMakeLists.txt` - builds `libpge_engine.so` when `ANDROID` is set. Gradle's `externalNativeBuild` points at it.
- `Engine/common_features/app_path.cpp` - data path logic (see below).

## Build

### Prerequisites

| Item | Required version | Where it is set |
|---|---|---|
| JDK | 17 | CI uses 17. AGP 8.0.1 needs JDK 17 (not checked by running it). |
| Gradle | 8.0 | `gradle/wrapper/gradle-wrapper.properties` (downloaded by `gradlew`) |
| Android Gradle Plugin | 8.0.1 | `Engine/android-project/build.gradle` |
| Android SDK platform | `compileSdkVersion 33`, `targetSdkVersion 33`, `minSdkVersion 16` | `moondust/build.gradle` |
| NDK | `23.2.8568313` | `moondust/build.gradle` |
| CMake | Not pinned. Install the version Android Studio's SDK Manager offers. Engine needs 3.5 or newer. | `Engine/CMakeLists.txt` |
| git | On `PATH` | `moondust/build.gradle` runs `git rev-parse` for the version code |

The disabled upstream workflow `.github/workflows/android-ci.yml.off` installs NDK `22.1.7171670`, which does not match `build.gradle`. Local builds use the `build.gradle` value. Keep CI in sync with it.

### Get the source with submodules

Build from a git checkout, not a source archive. Two reasons:

- `moondust/build.gradle` runs `git rev-parse` to build the version code.
- The native dependencies are git submodules (see `.gitmodules`). SDL2 is built from `_Libs/AudioCodecs/SDL2`. LuaJIT, FreeImage, SDL_Mixer_X, luabind, libjpeg, libpng and PGE_File_Formats are also submodules. Without them the native build has no sources.

```sh
git clone <repo-url> SMBX-2-Android
cd SMBX-2-Android
git submodule update --init --recursive
```

For an existing clone, run only the last line.

### Android Studio

Open the `Engine/android-project` folder (not the repository root). Let Gradle sync, install the SDK, NDK and CMake versions above, select the `debug` variant, and run.

### Command line

```sh
cd Engine/android-project
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Gradle needs the Android SDK location. Android Studio writes it to `local.properties`. Otherwise set `ANDROID_HOME`.

Output: `Engine/android-project/moondust/build/outputs/apk/debug/moondust-debug.apk`.

- The debug build uses application ID `ru.wohlsoft.moondust.debug` and version suffix `-DEVDEBUG`. It installs next to a release build and is signed with the default debug key, so no extra setup is needed.
- `assembleRelease` makes an unsigned APK, which cannot be installed as-is.
- `assembleReleaseci` expects `release-key.jks` in `Engine/android-project/` and the signing environment variables that `moondust/build.gradle` reads (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`, and the `PIN_ALIAS` / `DB_PASS_ALIAS` values). Never commit keystores. The root `.gitignore` excludes `*.jks` and `*.keystore`.
- `abiFilters` builds four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). This makes the build slower. The Odin 2 Portal needs only `arm64-v8a`.

## Install on the Odin 2 Portal

Standard sideloading. Menu names vary by Android skin, so check the labels on your device.

1. Enable Developer options (Settings > About, then tap the build number several times) and USB debugging.
2. Install over USB:
   ```sh
   adb install -r Engine/android-project/moondust/build/outputs/apk/debug/moondust-debug.apk
   ```
   Or copy the APK to the device and open it from a file manager. Allow that file manager to install unknown apps.
3. Start **Moondust Engine** (the `app_name` resource).
4. On Android 11 and newer, the activity opens the "All files access" settings page on first launch (`moondustActivity.hasManageAppFS()`). Turn it on for the app and return to the game. The engine needs it to read and write `/sdcard/PGE Project`. What happens if you deny it is not verified.

## Where content goes

On Android the engine's user directory is `/sdcard/PGE Project/` (`app_path.cpp`, `getPgeUserDirectory()` and `UserDirName`). The engine creates it on first run, along with a `.nomedia` file so media scanners skip it. Your file manager should show it as "PGE Project" in shared storage (not checked on the device).

```
/sdcard/PGE Project/
├── configs/
│   └── <PackName>/              config pack: main.ini, data/, ...
├── <PackName>_worlds/           worlds folder for that pack (default name)
│   ├── <Level>.lvl / .lvlx      standalone levels, listed directly here
│   └── <EpisodeName>/           one episode folder
│       └── *.wld / *.wldx       world map(s) for the episode
├── settings/
│   ├── pge_engine.ini
│   └── gamesaves/
└── screenshots/
```

- **Config pack (required).** At startup the engine lists the folders in `<user dir>/configs/` (`scene_config_select.cpp`). It reads `main.ini` and the `data/` directory from the chosen pack (`config_manager.cpp`). Config packs are not in this repository. Use an SMBX64-standard pack, as the Engine README describes.
- **Worlds folder.** Its name comes from the pack's `main.ini` key `worlds`. The default is `<PackName>_worlds` (`config_manager.cpp`, `dirs.worldsUser`). The title menu scans this folder for standalone levels and for episode subfolders with `.wld` / `.wldx` files (`ttl_main_menu.cpp`).
- **Translations** are not in the device filesystem. CMake copies `Engine/languages/*.qm` into the APK's `assets/languages`, and the translator reads them from there (`translator.cpp`). The `assets/languages` folder is generated, so a fresh checkout needs a build before it exists.
- **Logs.** Android logs go to logcat under the tag `TRACKERS`: `adb logcat -s TRACKERS`. A log file is also written under `logsDir()`, which is built from the SDL base path on Android. Its on-device location is not verified.

## Controls (Odin 2 Portal built-in gamepad)

Not tested on the device. What the code shows:

- Gamepads are read through SDL's joystick API (`Engine/controls/controller_joystick.cpp`). The built-in pad should appear as a joystick if Android exposes it as one. This is not verified.
- On Android the default input is the on-screen touch controller (`Engine/settings/global_settings.cpp`, `openController()`). The menu label for that default is "Keyboard", which is misleading on Android.
- To use the pad, open **Options > Controls > Player 1 controls**, set **Input** to `Joystick: <name>`, and bind each action in the same menu. Bindings can be buttons, hats or axes (`scenes/title/ttl_main_menu.cpp`, `controls/controller_joystick.cpp`).
- Android Back is handled in message and text-input boxes (`SDLK_AC_BACK` in `gui/pge_msgbox.cpp` and `gui/pge_textinputbox.cpp`).

## Known limitations

- Not tested on hardware. Everything above comes from reading the source.
- **Touch layout:** the on-screen button map is hard-coded for a 1024x600 canvas (`controls/controller_touchscreen.cpp`). The JNI hooks `setKeyPos` and `setCanvasSize` exist, but nothing in the Java code calls them. Touch hit boxes on a 1080p screen are unverified.
- **Storage:** depends on "All files access" (see Install). The engine also requests `WRITE_EXTERNAL_STORAGE` at startup (`engine.cpp`). Behavior on Android 13 has not been tested.
- **Version targets:** `minSdkVersion 16` is declared, but only Android 13 is the intended target. Other versions are untested.
- **Old toolchain:** Gradle 8.0, AGP 8.0.1, NDK 23.2.8568313.
- **No content ships with the APK.** It contains no config pack, no episodes and no SMBX graphics or music. The `Content/` folder is not bundled.
- The release signing path (`releaseci`) needs secrets that are not in the repository.

## Licensing

- Moondust Engine is dual-licensed under MIT or GPL version 3 or later, at your option. Full texts: `Engine/LICENSE.mit` and `Engine/LICENSE.gpl3`. The root `LICENSE` is the same GPLv3 text.
- `moondust/src/main/java/org/libsdl/app/` is SDL's Java glue. It is governed by SDL's license, not Moondust's dual license. The copies here do not carry a license header.
- SMBX assets (graphics, music, levels from the original games) are not part of this repository, and the APK does not include them. Use only content you have the rights to.
