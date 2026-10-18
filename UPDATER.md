# In-app updater

The game can update itself from the rolling `nightly` prerelease of
`tdprinting/SMBX-2-Android` on GitHub. Code: `Updater.java` and `UpdateInstallReceiver.java` in
`Engine/android-project/moondust/src/main/java/ru/wohlsoft/moondust/`. Owner, repo, tag and file names are
constants at the top of `Updater`.

## How it works

1. Fetch `https://github.com/tdprinting/SMBX-2-Android/releases/download/nightly/version.json`
   (https only, redirects followed but never downgraded to http, 10 s timeouts, worker thread).
   Fields: `versionCode`, `versionName`, `sha256`, `apk`, `commit`, `notes`.
2. If `versionCode` is strictly greater than the installed one, show **Update available** (new and installed
   versionName/build, short commit, notes) with **Update** / **Later**. The dialog starts on **Later**, so a
   stray A press while playing does nothing.
3. **Update** downloads `moondust-release.apk` (same path as `version.json`) to `cache/updates/` with a
   segmented progress bar and **Cancel**. 300 MB cap, https->http redirects refused. The SHA-256 is checked
   against `version.json`; on mismatch the file is deleted and nothing is installed.
4. The APK is written into a `PackageInstaller` session (restricted to this package) and committed. Old
   downloads in `cache/updates/` are removed before each download and at the next launch.

### When it runs

* Automatic: once per launch, after the storage-permission flow, in the background, and at most once per
  6 hours (a check that failed because the device was offline does not count). Silent unless an update exists;
  offline or any error shows nothing.
* Manual: **UPDATE** button on the second row of the Mod Browser toolbar (Select+Start in game, or the Mods
  button on touch). Always runs, says "You are up to date" when current, and shows a message on errors
  (for example offline).

## One-time permission: "Install unknown apps"

Android requires this per app. Before installing, the updater checks `canRequestPackageInstalls()`. If it is
off, an explanation dialog opens the Settings page for this app; switch on **Allow from this source**, press
Back, and the update continues by itself.

## What is and is not silent

* Android 12+ (API 31+): the session asks for `USER_ACTION_NOT_REQUIRED`. The platform honours it only when
  its own conditions are met (roughly: this app is the installer of record, i.e. the previous version was
  installed by this updater, and it is a plain update). Then no prompt appears.
* Otherwise, including the very first update of a copy installed with a file manager or adb, and Android
  before 12, the system shows its normal one-tap "Do you want to update this app?" confirmation. This is
  expected; press Install.
* Either way the OS then replaces the package and **kills the running game**. The app does not relaunch
  itself (Android blocks background launches); open it again from the launcher.

## Requirements for an update to install

* The new APK must be signed with the **same certificate** as the installed app.
* Its `versionCode` must be **higher** than the installed one (the app also refuses to start an install
  otherwise).

## Troubleshooting

* "installed copy was signed with a different certificate": the installed app came from a build signed with
  another key (for example a debug build vs. the CI release). Android cannot update it in place: uninstall
  once, install the new build, and later updates work.
* "Checksum mismatch": the download did not match `version.json` (a partial upload, or the release changed
  while downloading). It was deleted; try again later.
* "No update has been published yet (HTTP 404)": the `nightly` release or its assets do not exist yet.
* "Could not reach GitHub": offline, or DNS/proxy trouble.
* Nothing happens after Update: check that "Install unknown apps" is allowed for the game, and that storage
  is not full.
* Update dialog never shows automatically: it checks at most every 6 h; use UPDATE in the Mod Browser.
