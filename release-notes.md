# Skyrim Mod Manager v1.2.X-beta

This release adds complete uninstall, FOMOD installers, 7z and RAR support, enable and disable, and load order editing with automatic backups for Skyrim Legendary Edition on Android.

**Beta software: back up your Skyrim folder before testing.**

## What’s new

- **Complete uninstall.** The app records every file a mod writes and every folder it creates. Uninstalling removes all of them, including plugins, textures, meshes and scripts, restores any files the mod overwrote, and removes created folders if they are empty.
- **Uninstall preview.** Before you confirm, Show details lists exactly which files will be removed, which will be restored to their original version, and which folders will be removed if left empty.
- **FOMOD installers.** Mods with an installer wizard show their option pages with images, required and recommended options, conditions, and selection rules.
- **Archive support.** Install from `.zip`, `.7z` and `.rar` archives. Zips using LZMA, Deflate64 or BZip2 compression are supported. The format is detected from the file contents, not its extension.
- **Enable and disable.** Each mod has a switch. Disabling keeps the files but stops the game loading them, and restores anything the mod had overwritten. Enabling reverses it.
- **Mod details.** Each mod shows its file count, total size and plugins. Tap the circle to list every file it installed.
- **Load order.** Activate or deactivate plugins and reorder them through `Plugins.txt` and `loadorder.txt`. When the game folder contains the port folder (`port/save/AppData/Local/Skyrim`), both files are found automatically. Otherwise you can select `plugins.txt` manually. Masters always stay above other plugins.
- **Load order backups.** Every time you save the load order, the current files are copied into `SkyrimMM Backups/Load order` in your game folder first. The newest 10 of each are kept. If a backup cannot be made, nothing is overwritten.
- **SKSE warning.** Before installing a mod that appears to need SKSE, the app explains why it was flagged. Detection checks file names, compiled scripts, FOMOD configs and readmes.
- **Progress window.** Installing, uninstalling, and switching a mod on or off show what is happening and how far along it is.
- **Fast mode.** With Android’s All files access enabled, the app works on files directly instead of through the folder picker, which is much faster.

## Changes

- **Choose the game folder.** Select the folder that contains `Data`. The app finds `Data` and the port folder inside it. Selecting `Data` directly still works.

## Fixes

- **LZMA zips.** Archives compressed with LZMA no longer fail with “bad compression method”.
- **Updates.** Every build is signed with the same key and has a rising version number, so a new APK installs over the previous one and keeps your mod list.
- **Speed.** Each folder is looked up once per install or uninstall, and archives are read once instead of several times.

## Known issues

- SKSE mods are not supported. The SKSE warning is a best guess and can be wrong in both directions.
- RAR5, password-protected, and multi-part archives are rejected.
- Only mods installed through the app are tracked. Mods copied into `Data` by hand do not appear.
- If two mods overwrite the same file, uninstall or disable them in reverse order of installation.
- FOMOD conditions that check for files outside `Data`, such as `skse_loader.exe`, see those files as missing.
- Load order is edited manually. There is no automatic sorting.
- Disabling a mod does not remove its plugin from `Plugins.txt`. Untick it on the Load order tab.
- Android does not allow access to another application’s protected `Android/data` directory.

## Upgrade notes

- Install this APK over the previous build. Do not uninstall first if you want to keep your mod list.
- If Android refuses the update (builds made before the signing fix used different keys), uninstall the app once and install again. Uninstall your mods inside the app first, because removing the app erases its record of installed mods.
- Enable All files access when prompted for faster installs and uninstalls.
- Back up `Plugins.txt` and `loadorder.txt` before saving a load order for the first time.

## Feedback

When reporting a problem, include:

- Android version and device model
- Mod name and a link to its page
- Archive format
- Steps that caused the problem
- The exact error message shown by the app
- Screenshots when available

Thank you to everyone testing Skyrim Mod Manager.
