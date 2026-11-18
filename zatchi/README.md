# zatchi

A tiny Windows app that makes Discord show a game of your choice as your activity ("Playing ...").

## How it works

Discord recognises games by the **file name of running processes**, matched against its public list of detectable games. When you press **Play**, zatchi copies itself to a temporary folder under the game's executable name (for example `ZAT.exe`) and starts that copy in a silent "stub" mode: an invisible process that does nothing. Discord sees that process and shows the game. **Stop** (or exiting zatchi) ends the process and deletes the temporary copy. Nothing is injected into Discord and no real game is started.

The game list (name + executable) is generated at build time from `https://discord.com/api/v9/applications/detectable`, and embedded into `zatchi.exe`. Popular games (`data/popular.txt`) are listed first, marked with a star.

## Install

Run `zatchi-setup.exe`, pick the install folder, optionally add a desktop shortcut and "Start with Windows". Uninstall from Installed Apps (this removes only zatchi's own files and settings).

## Use

- Type in the search box to filter (name or executable, several words allowed).
- Click a game, then **Play** (or double-click, or press Enter). **Stop** ends it.
- Keys: Up/Down/PageUp/PageDown select, Enter plays, Esc clears the search (or hides the window).
- Closing the window keeps zatchi running in the tray. Right-click the tray icon: **Open zatchi**, **Stop playing**, **Start with Windows**, **Exit**.

## Notes

- Discord must be running and "Display current activity" enabled (Settings > Activity Privacy).
- Discord sometimes takes a few seconds to pick the process up.
- Only one game at a time. Starting another stops the current one.
- If a specific game is not detected, its entry in Discord's list may require something zatchi cannot imitate.

## Build

Requires MinGW-w64 (GCC), CMake, Ninja, Python 3, and Inno Setup 6 for the installer.

```
cd zatchi
python tools/gen_games.py          # downloads Discord's list -> res/games.tsv
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
mkdir dist && copy build\zatchi.exe dist\
iscc installer\zatchi.iss          # -> dist\zatchi-setup.exe
```

GitHub Actions (`.github/workflows/build-zatchi.yml`) does all of this and publishes a release.
