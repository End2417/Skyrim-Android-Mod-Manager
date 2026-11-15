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
- **Plugins follow mods.** After installing a mod, the app offers to activate its plugins. Disabling a mod deactivates them, enabling it restores the ones that were active, and uninstalling it removes them from `Plugins.txt` and `loadorder.txt`. A backup is saved before every change.
- **Conflict viewer.** Mods that overwrite the same files are marked, and tapping the warning shows which files, which mod wins, and which loses. The mod installed last wins. Disabling or uninstalling an overlapping mod warns you first, and the app no longer deletes a file that a newer mod now owns.
- **Update a mod.** The refresh button on a mod installs a new archive over it and removes only the files the new version no longer has. The mod keeps its on or off state and its place in your load order. If the new install fails, the old version stays.
- **Dependency check.** The Load order tab reads each plugin’s header and flags missing, inactive, or misordered masters. Fix order rearranges plugins so every plugin loads after its masters; you review the result before saving. It is not a full LOOT sort.
- **Single plugins and batches.** Install a bare `.esp`, `.esm` or `.esl` on its own, pick several files at once, or open files from your file manager or the share menu. Files queue up and install one after another.
- **Mod details.** Rename a mod, add a version, notes and a source link, search the list, and sort by name, newest or largest.
- **Restore load order backups.** The Backups button on the Load order tab lists every saved version and restores the one you choose, after backing up the current files.
- **Profiles and setups.** Save the enabled mods and load order as a profile and switch between them. Share a setup as text; the other person imports it from the clipboard and sees which mods they still need.
- **Verify mods.** Checks that every file of every mod is present and still has its original size.
- **Free-space check.** The app checks there is room before it copies or extracts an archive.
- **Skyrim.ini helper.** Turns loose file loading on or off, with a backup of the ini first.
- **Troubleshooting.** Switches mods off in steps to find the one causing a problem, and puts everything back when you stop.
- **SKSE warning.** Before installing a mod that appears to need SKSE, the app explains why it was flagged. Detection checks file names, compiled scripts, FOMOD configs and readmes.
- **Progress window.** Installing, uninstalling, and switching a mod on or off show what is happening and how far along it is.
- **Fast mode.** With Android’s All files access enabled, the app works on files directly instead of through the folder picker, which is much faster.

## Changes

- **Load order files are edited more often.** Besides Save, the app now updates `Plugins.txt` and `loadorder.txt` when you accept the activation prompt, or enable, disable or uninstall a mod. It saves a timestamped backup first every time.
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
- The dependency check reads plugin headers only. It does not sort the whole load order the way LOOT does.
- Conflicts are decided by install order. Mods installed before conflict tracking existed get an estimated install time, which can be wrong.
- An updated mod counts as the newest mod for conflict purposes.
- Mod priority is install order. There is no drag-to-reorder priority list.
- There are no downloads from inside the app.
- The troubleshooting helper can be fooled by two mods that only misbehave together.
- Profiles match mods by name.
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
