# PinRecipe

PinRecipe is a small Windows-only Java overlay for Icarus. It lets you capture a recipe tooltip with a global hotkey and keep it pinned above the game while you gather materials.

## Requirements

- Windows 10/11
- Java 8 or newer
- Icarus running in borderless or windowed fullscreen mode
- `lib\jna.jar` before building

## Build

```powershell
.\build.ps1
```

The runnable jar is created at:

```text
dist\PinRecipe.jar
```

## Run

```powershell
java -jar dist\PinRecipe.jar
```

PinRecipe starts in the system tray. When it detects `Icarus.exe` or `Icarus-Win64-Shipping.exe`, it shows the pin icon in the top-left corner.

## Usage

- Press `F8` to capture.
- Drag the main pin icon to place it anywhere on screen.
- Drag over the recipe area and release the mouse.
- Move a pin by dragging it.
- Resize a pin from the bottom-right triangle.
- Use `_` to minimize a pin.
- Use `x` to close a pin.
- Minimized pins stack below the main pin icon.
- Click the main pin icon to open Settings and change the capture keybind.
- Hide or show the main pin icon from Settings or the system tray.

## Notes

Exclusive fullscreen games can block desktop overlays or return black screenshots. Use borderless or windowed fullscreen if capture does not work correctly.
