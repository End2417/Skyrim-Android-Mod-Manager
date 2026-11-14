# Skyrim Mod Manager

**Skyrim Legendary Edition mod management for Android, with complete uninstall and fomod support.**

Skyrim Mod Manager installs mods straight from archives into your Skyrim `Data` folder and tracks every file each mod adds. That lets it uninstall a mod completely later: plugins, textures, meshes, scripts, and anything else it added. It also manages your plugin list and load order through `Plugins.txt` and `loadorder.txt`.

> [!WARNING]
> This is beta software that writes to your game folder. Back up your Skyrim folder before installing mods, and back up `Plugins.txt` and `loadorder.txt` before saving a load order for the first time.

## Current support

Skyrim Mod Manager targets Skyrim Legendary Edition running through an Android port or compatibility setup, with the game folder in shared storage.

Current capabilities include:

- installing mods from `.zip`, `.7z`, and `.rar` archives;
- FOMOD installer wizards, including option images, conditions, and selection rules;
- complete uninstall, with a preview of exactly which files will be removed and restored;
- enabling and disabling mods without deleting them;
- per-mod file lists and total size;
- plugin activation and load order editing through `Plugins.txt` and `loadorder.txt`;
- automatic backups of both files whenever you save a load order;
- a warning before installing a mod that appears to need SKSE; and
- a progress window during installs, uninstalls, and mod toggles.

Archive support depends on the actual archive format. Zips using LZMA, Deflate64, or BZip2 compression are supported. RAR support covers RAR4 only. Password-protected, multi-part, and RAR5 archives are rejected with an error message.

## How uninstall works

When a mod is installed, the app records every file it writes and every folder it creates. If a mod overwrites an existing file, the original is saved first. Uninstalling then:

1. deletes every file the mod added, including disabled copies;
2. restores any files the mod had overwritten; and
3. removes folders the mod created, if they are empty.

Before you confirm, **Show details** lists exactly what will be removed and restored.

The app only tracks mods it installed itself. Mods copied into `Data` by hand will not appear in the list.

## Compatibility environments

The app works with Skyrim setups that keep the game folder in shared storage. When your game folder contains the port folder (`port/save/AppData/Local/Skyrim`), the app finds `Plugins.txt` and `loadorder.txt` automatically. Otherwise you can select `plugins.txt` manually.

Android may block access to another application's protected `Android/data` directory, even when all files access is enabled.

## Requirements

- Android 8.0 or newer.
- Skyrim Legendary Edition game files in shared storage.
- Recommended: Android 11 or newer with **All files access** enabled. The app then works on files directly, which is much faster. Without it, the app still works through Android's folder picker.

## Get started

1. Download the latest APK from the [releases page](../../releases).
2. Back up the game folder you plan to modify.
3. Open the app, tap **Choose folder**, and select your Skyrim game folder (the one that contains `Data`).
4. When the app offers it, allow **All files access** for faster installs.
5. Tap **Install mod** and select an archive.

## Updating

Every build is signed with the same key, so a new APK installs over the old one and keeps your mod list. Uninstalling the app erases its record of installed mods, so uninstall mods inside the app first if you ever need to reinstall it.

## Known limitations

- SKSE mods are not supported. The SKSE warning is a best guess based on file names, compiled scripts, installer configs, and readmes, so it can be wrong in both directions.
- FOMOD conditions that check for files outside `Data` (such as `skse_loader.exe`) see those files as missing.
- If two mods overwrite the same file, uninstall or disable them in reverse order of installation.
- Load order is edited manually. There is no automatic sorting.
- Disabling a mod does not remove its plugin from `Plugins.txt`. Untick it on the Load order tab.

## Building

The project builds with GitHub Actions. Before the first run, add two repository secrets, `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`, which hold the app's signing key. The key is never stored in the repository. Run the **Build Skyrim Mod Manager APK** workflow from the **Actions** tab, or push a commit. Each successful run publishes a signed APK as a pre-release named after its version (for example `v1.2.5-beta`), with the release notes filled in.

The source files in this repository are written by the workflow on every run, from the copy kept inside `.github/workflows/build.yml`. To change the app, edit that file. Changes made directly to the other files are overwritten by the next run.

## Contributing

Bug reports, compatibility notes, and documentation corrections are welcome. When reporting a problem, include your Android version, the mod's name and a link to its page, and the exact error message shown by the app.

## License

This project does not have a license yet. Until one is added, all rights are reserved by the author.

## Disclaimer

Skyrim Mod Manager is an independent fan project. It is not affiliated with or endorsed by Bethesda Softworks, and it contains no game files and no mods. Users remain responsible for the licenses and permissions of the games, mods, and tools they use.
