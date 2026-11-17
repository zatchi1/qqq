# zat Image Viewer

A fast, lightweight, native Windows image viewer (C++ / Win32 / WIC / GDI+). No Electron, no runtime to install, no background services. Statically linked: `zat.exe` is a single file.

## Downloads

Built automatically by GitHub Actions (`.github/workflows/build-zat.yml`). Get `zat.exe` and `zat-setup.exe` from the repository's **Releases** page (or the workflow run's artifacts).

## Install

1. Run `zat-setup.exe`, choose the install folder, and follow the wizard.
2. Optional tasks: desktop shortcut, "make zat the default image viewer".
3. zat appears in the Start Menu, in Installed Apps, and in the **Open with** list.

On Windows 10/11, apps cannot silently take over default file types. If you ticked the default-viewer task and an image still opens elsewhere, open **Settings > Apps > Default apps > zat Image Viewer** and confirm the formats.

Uninstall from Installed Apps. Only zat's own files and registry keys are removed, never your images.

You can also use `zat.exe` portably: just double-click it (it asks for an image) or drag an image onto it.

## Usage

- Double-click an image, or right-click > **Open with > zat**. Drag and drop onto the window also works. Several files can be passed at once.
- If no file is given, a file picker opens (there is no empty start screen).
- Previous/Next walk through the images in the same folder (natural sort order). Only the current image is kept in memory.

| Action | Control |
|---|---|
| Zoom toward cursor | Mouse wheel |
| Pan | Drag with left mouse (right/middle mouse while annotating) |
| Fit / actual size | Double-click, `0` / `1` |
| Rotate 90 degrees | `R` or button |
| Previous / next | `Left` / `Right`, `Space`, `Page Up/Down` |
| Theme (dark/light) | Moon/sun button top-left, `T` (remembered) |
| Fullscreen | `F11` |
| Annotate | Pen button top-right, `P` |
| Thickness | `-` / `+` buttons, `[` / `]`, `Ctrl+Wheel` |
| Undo / clear | Buttons, `Ctrl+Z` |
| Save | Save button (Save as new file / Overwrite original), `Ctrl+S` = save as |
| Quit | `Esc` (leaves annotation mode first) |

### Annotation

Click the pen, pick red, green or yellow, and draw with the mouse. Freehand drawing works in any direction, so Arabic handwriting (right to left) is natural. The original file is never touched until you choose **Overwrite original** (with confirmation); **Save as new file** (PNG/JPG/BMP/TIFF) is the default. Rotation is included in the saved image. Annotation is disabled for animated images (GIF/WebP).

## Formats

JPG, PNG, BMP, GIF (animated), TIFF, ICO, JPEG XR and WebP through Windows' built-in codecs (WebP needs Windows 10+ or the Microsoft WebP extension). SVG is rendered with nanosvg (downloaded at build time). HEIC/HEIF, AVIF and JPEG XL are decoded through the Microsoft Store codec extensions ("HEIF Image Extensions", "AV1 Video Extension", etc.); if they are not installed, Windows cannot decode those files and zat shows a message.

Images above about 28 megapixels are downscaled while decoding to protect RAM on old computers.

## Build from source

Requires MinGW-w64 (GCC), CMake and Ninja (MSYS2 `mingw64` is what CI uses) and [Inno Setup 6](https://jrsoftware.org/isinfo.php) for the installer.

```
cd zat-image-viewer
mkdir src/third_party   # put nanosvg.h and nanosvgrast.h from github.com/memononen/nanosvg here for SVG support
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
mkdir dist && copy build\zat.exe dist\
iscc installer\zat.iss          # produces dist\zat-setup.exe
```

## Layout

- `src/zat.cpp` - the whole application
- `res/` - icon, manifest, version resource
- `installer/zat.iss` - Inno Setup installer script
