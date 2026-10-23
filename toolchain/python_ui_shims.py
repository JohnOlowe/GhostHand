#!/usr/bin/env python3
"""Build temporary no-op Qt link shims for CPU-only offscreen QWidget snapshots.

These are not OpenGL, EGL, xkbcommon, or D-Bus implementations. They only export
symbols needed to load the pinned PySide6 wheel when those host libraries are
missing. Do not use them to test graphics APIs, keyboard input, or desktop services.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import subprocess


LIBRARIES = {
    "egl": "libEGL.so.1",
    "gl": "libGL.so.1",
    "xkb": "libxkbcommon.so.0",
    "dbus": "libdbus-1.so.3",
}


def _undefined_symbols(paths: list[Path]) -> dict[str, set[str]]:
    symbols = {key: set() for key in LIBRARIES}
    patterns = {
        "egl": lambda name: name.startswith("egl"),
        "gl": lambda name: len(name) > 2 and name.startswith("gl") and name[2].isupper(),
        "xkb": lambda name: name.startswith("xkb_"),
        "dbus": lambda name: name.startswith("dbus_"),
    }
    for path in paths:
        result = subprocess.run(
            ["objdump", "-T", str(path)],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        if result.returncode:
            continue
        for line in result.stdout.splitlines():
            if "*UND*" not in line:
                continue
            name = line.split()[-1].split("@", 1)[0]
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                continue
            for key, predicate in patterns.items():
                if predicate(name):
                    symbols[key].add(name)
    return symbols


def _versioned_script(version: str, symbols: set[str]) -> str:
    entries = "\n".join(f"    {symbol};" for symbol in sorted(symbols))
    return f"{version} {{\n  global:\n{entries}\n}};\n"


def _build_stub(out_dir: Path, key: str, symbols: set[str]) -> None:
    soname = LIBRARIES[key]
    source = out_dir / f"{key}.c"
    functions = "\n".join(
        f"__attribute__((visibility(\"default\"))) uintptr_t {name}(void) {{ return 0; }}"
        for name in sorted(symbols)
    )
    source.write_text(
        "#include <stdint.h>\n" + functions + "\n", encoding="utf-8"
    )
    command = [
        "gcc",
        "-shared",
        "-fPIC",
        "-fno-stack-protector",
        "-Wl,-soname," + soname,
        "-o",
        str(out_dir / soname),
        str(source),
    ]
    if key == "xkb":
        version_file = out_dir / "xkb.map"
        version_file.write_text(_versioned_script("V_0.5.0", symbols), encoding="utf-8")
        command.append(f"-Wl,--version-script={version_file}")
    elif key == "dbus":
        version_file = out_dir / "dbus.map"
        version_file.write_text(
            _versioned_script("LIBDBUS_1_3", symbols), encoding="utf-8"
        )
        command.append(f"-Wl,--version-script={version_file}")
    subprocess.run(command, check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qt-lib", required=True, type=Path)
    parser.add_argument("--qt-plugins", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)

    if shutil.which("gcc") is None or shutil.which("objdump") is None:
        raise SystemExit("--cpu-only-shims requires gcc and objdump on PATH")
    if not args.qt_lib.is_dir() or not args.qt_plugins.is_dir():
        raise SystemExit("PySide6 Qt library or plugin directory was not found")
    args.out.mkdir(parents=True, exist_ok=True)
    candidates = list(args.qt_lib.glob("*.so*")) + list(args.qt_plugins.rglob("*.so"))
    symbols = _undefined_symbols(candidates)
    for key, soname in LIBRARIES.items():
        if not symbols[key]:
            raise SystemExit(
                f"no unresolved {key} symbols were found in the pinned Qt wheel; "
                "refusing to build an empty shim"
            )
        _build_stub(args.out, key, symbols[key])
        print(f"temporary CPU-only shim: {soname} ({len(symbols[key])} symbols)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
