#!/usr/bin/env bash
# Ephemeral PySide6 bootstrap + headless Qt widget screenshot/property probe.
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PYTHON=${PYTHON:-python3}

usage() {
  cat <<'EOF'
Usage:
  bash toolchain/python-ui-inspect.sh test
  bash toolchain/python-ui-inspect.sh [--cpu-only-shims] \
    --factory dotted.module:make_window --output /tmp/window.png \
    [--properties /tmp/window.json] [--size 1024x768] [--style Fusion]

The test mode downloads a pinned PySide6 runtime into /tmp, renders a sample
QMainWindow, verifies its PNG/property report, then removes all downloaded and
compiled files. Inspect mode has the same temporary bootstrap; choose output
paths outside the repository if you do not want generated screenshots tracked.

--cpu-only-shims is a sandbox-only escape hatch for standard QWidget/QPainter
raster previews when host EGL/OpenGL/xkbcommon/D-Bus libraries are missing. It
is not suitable for OpenGL, Qt Quick, keyboard, D-Bus, or desktop integration tests.
Set PYTHON=/path/to/python to use an application virtualenv's interpreter.
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  usage
  exit 0
fi
if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "Python interpreter not found: $PYTHON" >&2
  exit 127
fi

MODE=inspect
CPU_SHIMS=0
if [[ ${1:-} == "test" ]]; then
  MODE=test
  CPU_SHIMS=1
  shift
  if (($#)); then
    echo "test mode takes no extra arguments" >&2
    usage >&2
    exit 2
  fi
else
  filtered=()
  for arg in "$@"; do
    if [[ $arg == "--cpu-only-shims" ]]; then
      CPU_SHIMS=1
    else
      filtered+=("$arg")
    fi
  done
  set -- "${filtered[@]}"
  if (($# == 0)); then
    usage >&2
    exit 2
  fi
fi

"$PYTHON" - <<'PY'
import platform
import re
import sys
if sys.version_info < (3, 10):
    raise SystemExit("PySide6 6.12 bootstrap requires Python 3.10 or newer")
if platform.machine().lower() not in ("x86_64", "amd64"):
    raise SystemExit("the pinned PySide6 wheel is for Linux x86_64")
version = platform.libc_ver()[1]
match = re.match(r"(\d+)\.(\d+)", version)
if not match or tuple(map(int, match.groups())) < (2, 34):
    raise SystemExit(f"the pinned wheel requires glibc 2.34 or newer (found {version or 'unknown'})")
PY

WORK=$(mktemp -d "${TMPDIR:-/tmp}/gh-python-ui.XXXXXXXX")
cleanup() { rm -rf -- "$WORK"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

WHEELS="$WORK/wheels"
SITE="$WORK/site"
mkdir -p "$WHEELS" "$SITE"
PIP_CACHE_DIR="$WORK/pip-cache" PIP_DISABLE_PIP_VERSION_CHECK=1 \
  "$PYTHON" -m pip download --no-deps --only-binary=:all: --dest "$WHEELS" \
  pyside6-essentials==6.12.0 shiboken6==6.12.0

"$PYTHON" - "$WHEELS" "$SITE" <<'PY'
import hashlib
from pathlib import Path
import sys
import zipfile

wheels = Path(sys.argv[1])
site = Path(sys.argv[2])
expected = {
    "pyside6_essentials-6.12.0-cp310-abi3-manylinux_2_34_x86_64.whl": (
        84_346_433,
        "16f4b5e41daf49235ae048458243da9efe5f3d8040f9cc5aa454ce69893d3bd9",
    ),
    "shiboken6-6.12.0-cp310-abi3-manylinux_2_34_x86_64.whl": (
        279_268,
        "3bde565bb0890044b5c7f63808adddede5abc241e9395a2bfed61919884d6240",
    ),
}
found = {path.name: path for path in wheels.glob("*.whl")}
if set(found) != set(expected):
    raise SystemExit(f"unexpected PyPI wheel set: {sorted(found)}")
for name, (size, digest) in expected.items():
    path = found[name]
    if path.stat().st_size != size:
        raise SystemExit(f"wheel size mismatch for {name}: {path.stat().st_size}")
    hasher = hashlib.sha256()
    with path.open("rb") as wheel_file:
        for block in iter(lambda: wheel_file.read(1024 * 1024), b""):
            hasher.update(block)
    actual = hasher.hexdigest()
    if actual != digest:
        raise SystemExit(f"wheel SHA-256 mismatch for {name}")
    with zipfile.ZipFile(path) as archive:
        archive.extractall(site)
    print(f"verified {name} ({size:,} bytes, SHA-256 OK)")
PY

QT_LIB="$SITE/PySide6/Qt/lib"
QT_PLUGINS="$SITE/PySide6/Qt/plugins"
if ((CPU_SHIMS)); then
  SHIMS="$WORK/cpu-shims"
  mkdir -p "$SHIMS"
  "$PYTHON" "$ROOT/toolchain/python_ui_shims.py" \
    --qt-lib "$QT_LIB" --qt-plugins "$QT_PLUGINS" --out "$SHIMS"
  export GHOSTHAND_QT_CPU_SHIMS=1
  export LD_LIBRARY_PATH="$SHIMS:$QT_LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
else
  unset GHOSTHAND_QT_CPU_SHIMS || true
  export LD_LIBRARY_PATH="$QT_LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
export PYTHONPATH="$SITE${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONDONTWRITEBYTECODE=1
export QT_QPA_PLATFORM=offscreen
export QT_OPENGL=software
export QT_QUICK_BACKEND=software
export QT_PLUGIN_PATH="$QT_PLUGINS${QT_PLUGIN_PATH:+:$QT_PLUGIN_PATH}"

if [[ $MODE == test ]]; then
  "$PYTHON" "$ROOT/toolchain/python_ui_inspect.py" \
    --self-test --style Fusion --settle-ms 0 \
    --output "$WORK/smoke.png" --properties "$WORK/smoke.json"
  "$PYTHON" - "$WORK/smoke.png" "$WORK/smoke.json" <<'PY'
import json
from pathlib import Path
import struct
import sys

png = Path(sys.argv[1]).read_bytes()
if png[:8] != b"\x89PNG\r\n\x1a\n":
    raise SystemExit("self-test output is not a PNG")
width, height = struct.unpack(">II", png[16:24])
if (width, height) != (720, 460):
    raise SystemExit(f"unexpected PNG size: {width}x{height}")
report = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
if report["runtime"]["qtVersion"] != "6.12.0":
    raise SystemExit("unexpected Qt runtime version")
if report["runtime"]["style"].casefold() != "fusion":
    raise SystemExit("Fusion style was not applied")
if report["widgetCount"] < 10:
    raise SystemExit("widget-property tree looks incomplete")
if not report["runtime"]["cpuOnlyLinkShims"]:
    raise SystemExit("CPU-only runtime limitations were not reported")
if not report["warnings"]:
    raise SystemExit("CPU-only shim warning is missing")
print(
    f"Python UI offscreen smoke test: PASS — {width}x{height} PNG, "
    f"{report['widgetCount']} widgets, Qt {report['runtime']['qtVersion']}; "
    "temporary dependencies and outputs removed on exit."
)
PY
else
  "$PYTHON" "$ROOT/toolchain/python_ui_inspect.py" "$@"
fi
