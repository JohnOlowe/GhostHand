# Headless Python GUI snapshots and properties

`toolchain/python-ui-inspect.sh` is a tested way to capture a **PySide6 / Qt Widgets**
window in this headless sandbox. It uses Qt's `offscreen` platform plugin, renders a
PNG, and writes a JSON tree with geometry, styles, fonts, palette colors, layout
margins/spacing, visibility, accessibility text, and common widget state.

This is a Qt Widgets probe, **not a universal Python-GUI inspector**. The repository
has no Python GUI application to point it at, so the built-in fixture and a separate
throwaway `module:function` app were used to verify the path.

## Smoke test

```bash
bash toolchain/python-ui-inspect.sh test
```

The smoke test downloads pinned PySide6 Essentials 6.12.0 and shiboken6 6.12.0
wheels, checks their SHA-256 hashes, makes temporary runtime files under `/tmp`,
renders a 720×460 `QMainWindow`, and verifies both the PNG and JSON report. It
self-cleans; it does not install system packages, change the Python environment, or
leave the downloaded wheels, compiled shims, screenshot, or report in the checkout.
Qt may emit `This plugin does not support propagateSizeHints()` on stderr; that is an
offscreen-platform warning, and the test still verifies a nonblank PNG and widget JSON.

Verified here on Python 3.11.2 / Linux x86_64: **Qt 6.12.0, Fusion style, 24 widgets,
720×460 PNG**. The bootstrap requires Python 3.10+, glibc 2.34+, and Linux x86_64;
the sandbox's shimmed path also requires `gcc` and `objdump`. The downloaded PySide
wheel is about 84 MB (below the repo's 128 MB artifact limit); it exists only in the
temporary work directory and is removed at exit. The helper verifies both pinned
wheel hashes before extracting them.

## Inspect a project window

Expose a factory that returns the top-level `QWidget` or `QMainWindow` to preview:

```python
# myapp/ui.py
from PySide6.QtWidgets import QApplication, QMainWindow

def build_window(app: QApplication) -> QMainWindow:
    window = QMainWindow()
    window.setWindowTitle("My application")
    # Build the rest of the UI here; don't call app.exec().
    return window
```

Then run the probe from the project root. The factory may take **no required
arguments** or one required `QApplication` argument. Its module must not create a
second QApplication or enter the event loop during import.

```bash
PYTHONPATH="$PWD/src${PYTHONPATH:+:$PYTHONPATH}" \
  bash toolchain/python-ui-inspect.sh --cpu-only-shims \
  --factory myapp.ui:build_window \
  --output /tmp/myapp.png \
  --properties /tmp/myapp.json \
  --size 1280x800 --style Fusion
```

`PYTHON=/path/to/venv/bin/python` selects the application's interpreter for imports.
The application and its non-Qt dependencies must be installed there. The wrapper
supplies the pinned PySide6 runtime temporarily; for the application's own installed
PySide6 version, run `toolchain/python_ui_inspect.py` directly with that environment:

```bash
QT_QPA_PLATFORM=offscreen python3 toolchain/python_ui_inspect.py \
  --factory myapp.ui:build_window --output /tmp/myapp.png --size 1280x800
```

The JSON report describes the Qt version, active style, virtual screen metrics,
window size, widget classes and parent links, geometry (parent-relative and global),
size hints/limits, margins, fonts, palette colors, stylesheets, layout details,
tooltips/accessibility labels, and common control states. Editable text and numeric
values are redacted in JSON by default; use `--include-input-values` only when those
values are safe to write to the report. The screenshot itself still shows whatever
the application displays, so choose output paths accordingly.

## Sandbox-specific limit: CPU-raster widgets only

This sandbox has no display server/Xvfb and lacks the host `libGL`, `libEGL`,
`libxkbcommon`, and `libdbus` libraries required to load the PySide wheel. A plain
`QT_QPA_PLATFORM=offscreen` import therefore fails here. `--cpu-only-shims` builds
small, **temporary no-op link shims** for those missing libraries. That allowed the
real Qt `QApplication`, widget layout, and `QPainter` raster path to produce and save
a PNG; it is not an OpenGL implementation or a general replacement for those host
libraries.

Consequently, use this sandbox route for ordinary raster-painted `QWidget` controls.
It does **not** validate `QOpenGLWidget`, Qt Quick/scene graphs, real keyboard/input,
D-Bus, native file dialogs, window-manager decorations, native desktop themes, or
target-machine scaling/fonts. The offscreen image is the client area only. The report
marks shim use and warns if Qt Quick/OpenGL widgets appear in the tree. For those
features—or a screenshot that must exactly match Windows/macOS/a particular Linux
desktop—run with the real Qt libraries and display stack on that target environment.

Tkinter/Tk, PyQt, Kivy, wxPython and other frameworks are not covered by this tested
PySide6 path. Tkinter in particular is absent here and requires a display server (or
Xvfb); an `xvfbwrapper` Python package alone would not supply the X server.
