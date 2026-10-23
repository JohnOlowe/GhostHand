#!/usr/bin/env python3
"""Capture a PySide6 QWidget and report its rendered/layout properties."""

from __future__ import annotations

import argparse
import importlib
import inspect
import json
import os
from pathlib import Path
import re
import sys
import time
from typing import Any


def _rect(rect: Any) -> dict[str, int]:
    return {
        "x": int(rect.x()),
        "y": int(rect.y()),
        "width": int(rect.width()),
        "height": int(rect.height()),
    }


def _size(size: Any) -> dict[str, int]:
    return {"width": int(size.width()), "height": int(size.height())}


def _margins(margins: Any) -> dict[str, int]:
    return {
        "left": int(margins.left()),
        "top": int(margins.top()),
        "right": int(margins.right()),
        "bottom": int(margins.bottom()),
    }


def _enum(value: Any) -> dict[str, Any]:
    result: dict[str, Any] = {"value": str(value)}
    name = getattr(value, "name", None)
    raw_value = getattr(value, "value", None)
    if name is not None:
        result["name"] = str(name)
    if isinstance(raw_value, (int, float, str)):
        result["numericValue"] = raw_value
    return result


def _color_name(color: Any) -> str:
    try:
        from PySide6.QtGui import QColor

        return color.name(QColor.NameFormat.HexArgb)
    except (AttributeError, TypeError):
        return color.name()


def _font(font: Any) -> dict[str, Any]:
    point_size = float(font.pointSizeF())
    return {
        "family": font.family(),
        "pointSize": round(point_size, 2) if point_size >= 0 else None,
        "pixelSize": int(font.pixelSize()),
        "weight": _enum(font.weight()),
        "italic": bool(font.italic()),
        "bold": bool(font.bold()),
        "underline": bool(font.underline()),
        "strikeOut": bool(font.strikeOut()),
    }


def _palette(widget: Any) -> dict[str, dict[str, str]]:
    from PySide6.QtGui import QPalette

    roles = (
        ("window", QPalette.ColorRole.Window),
        ("windowText", QPalette.ColorRole.WindowText),
        ("base", QPalette.ColorRole.Base),
        ("text", QPalette.ColorRole.Text),
        ("button", QPalette.ColorRole.Button),
        ("buttonText", QPalette.ColorRole.ButtonText),
        ("highlight", QPalette.ColorRole.Highlight),
        ("highlightedText", QPalette.ColorRole.HighlightedText),
        ("toolTipBase", QPalette.ColorRole.ToolTipBase),
        ("toolTipText", QPalette.ColorRole.ToolTipText),
    )
    groups = (
        ("active", QPalette.ColorGroup.Active),
        ("disabled", QPalette.ColorGroup.Disabled),
    )
    palette = widget.palette()
    return {
        group_name: {
            role_name: _color_name(palette.color(group, role))
            for role_name, role in roles
        }
        for group_name, group in groups
    }


def _json_value(value: Any) -> Any:
    """Convert common Qt values and dynamic properties into JSON-safe values."""
    from PySide6.QtCore import QDate, QDateTime, QMargins, QPoint, QRect, QSize, QTime
    from PySide6.QtGui import QColor, QFont

    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    if isinstance(value, QColor):
        return _color_name(value)
    if isinstance(value, QFont):
        return _font(value)
    if isinstance(value, QRect):
        return _rect(value)
    if isinstance(value, QSize):
        return _size(value)
    if isinstance(value, QPoint):
        return {"x": int(value.x()), "y": int(value.y())}
    if isinstance(value, QMargins):
        return _margins(value)
    if isinstance(value, (QDate, QTime, QDateTime)):
        return value.toString()
    if hasattr(value, "value") and isinstance(value.value, (int, float, str)):
        return {"name": getattr(value, "name", None), "value": value.value}
    return str(value)


def _layout_info(layout: Any) -> dict[str, Any] | None:
    if layout is None:
        return None
    info: dict[str, Any] = {
        "class": type(layout).__name__,
        "geometry": _rect(layout.geometry()),
        "contentsMargins": _margins(layout.contentsMargins()),
        "spacing": int(layout.spacing()),
        "sizeConstraint": _enum(layout.sizeConstraint()),
        "itemCount": int(layout.count()),
        "items": [],
    }
    if hasattr(layout, "direction"):
        info["direction"] = _enum(layout.direction())
    for index in range(layout.count()):
        item = layout.itemAt(index)
        child_widget = item.widget()
        child_layout = item.layout()
        if child_widget is not None:
            info["items"].append(
                {
                    "kind": "widget",
                    "class": child_widget.metaObject().className(),
                    "objectName": child_widget.objectName(),
                    "geometry": _rect(child_widget.geometry()),
                }
            )
        elif child_layout is not None:
            info["items"].append(
                {"kind": "layout", "layout": _layout_info(child_layout)}
            )
        else:
            spacer = item.spacerItem()
            spacer_info: dict[str, Any] = {"kind": "spacer"}
            if spacer is not None:
                spacer_info["sizeHint"] = _size(spacer.sizeHint())
                spacer_info["minimumSize"] = _size(spacer.minimumSize())
                spacer_info["expandingDirections"] = _enum(
                    spacer.expandingDirections()
                )
            info["items"].append(spacer_info)
    return info


def _widget_record(
    widget: Any,
    index: int,
    parent_index: int | None,
    include_input_values: bool,
) -> dict[str, Any]:
    from PySide6.QtCore import QPoint
    from PySide6.QtWidgets import (
        QAbstractButton,
        QComboBox,
        QDoubleSpinBox,
        QLabel,
        QLineEdit,
        QPlainTextEdit,
        QProgressBar,
        QSlider,
        QSpinBox,
        QTextEdit,
    )

    meta = widget.metaObject().className()
    geometry = widget.geometry()
    global_origin = widget.mapToGlobal(QPoint(0, 0))
    record: dict[str, Any] = {
        "index": index,
        "parentIndex": parent_index,
        "qtClass": meta,
        "pythonClass": type(widget).__name__,
        "objectName": widget.objectName(),
        "geometry": _rect(geometry),
        "globalGeometry": {
            "x": int(global_origin.x()),
            "y": int(global_origin.y()),
            "width": int(geometry.width()),
            "height": int(geometry.height()),
        },
        "contentsRect": _rect(widget.contentsRect()),
        "visible": bool(widget.isVisible()),
        "enabled": bool(widget.isEnabled()),
        "tooltip": widget.toolTip(),
        "statusTip": widget.statusTip(),
        "accessibleName": widget.accessibleName(),
        "accessibleDescription": widget.accessibleDescription(),
        "focusPolicy": _enum(widget.focusPolicy()),
        "layoutDirection": _enum(widget.layoutDirection()),
        "isWindow": bool(widget.isWindow()),
        "sizePolicy": {
            "horizontal": _enum(widget.sizePolicy().horizontalPolicy()),
            "vertical": _enum(widget.sizePolicy().verticalPolicy()),
            "horizontalStretch": int(widget.sizePolicy().horizontalStretch()),
            "verticalStretch": int(widget.sizePolicy().verticalStretch()),
            "hasHeightForWidth": bool(widget.sizePolicy().hasHeightForWidth()),
            "retainSizeWhenHidden": bool(widget.sizePolicy().retainSizeWhenHidden()),
        },
        "font": _font(widget.font()),
        "palette": _palette(widget),
        "minimumSize": _size(widget.minimumSize()),
        "maximumSize": _size(widget.maximumSize()),
        "sizeHint": _size(widget.sizeHint()),
        "minimumSizeHint": _size(widget.minimumSizeHint()),
        "contentsMargins": _margins(widget.contentsMargins()),
        "styleSheet": widget.styleSheet(),
        "layout": _layout_info(widget.layout()),
        "dynamicProperties": {},
    }
    if index == 0:
        record["windowTitle"] = widget.windowTitle()
        record["frameGeometry"] = _rect(widget.frameGeometry())

    if isinstance(widget, (QLineEdit, QTextEdit, QPlainTextEdit)):
        getter = widget.text if isinstance(widget, QLineEdit) else widget.toPlainText
        current = getter()
        record["text"] = current if include_input_values else "<redacted>"
        record["textRedacted"] = not include_input_values and bool(current)
        if isinstance(widget, QLineEdit):
            record["placeholderText"] = widget.placeholderText()
            record["readOnly"] = bool(widget.isReadOnly())
            record["echoMode"] = _enum(widget.echoMode())
            record["maxLength"] = int(widget.maxLength())
        else:
            record["placeholderText"] = widget.placeholderText()
            record["readOnly"] = bool(widget.isReadOnly())
            record["wordWrapMode"] = _enum(widget.wordWrapMode())
    else:
        text_getter = getattr(widget, "text", None)
        if callable(text_getter):
            try:
                record["text"] = str(text_getter())
            except (RuntimeError, TypeError):
                pass

    if isinstance(widget, QLabel):
        record["alignment"] = _enum(widget.alignment())
        record["wordWrap"] = bool(widget.wordWrap())
        record["margin"] = int(widget.margin())
        record["indent"] = int(widget.indent())
    if isinstance(widget, QLineEdit):
        record["alignment"] = _enum(widget.alignment())
        record["frame"] = bool(widget.hasFrame())
        record["clearButtonEnabled"] = bool(widget.isClearButtonEnabled())
    if isinstance(widget, QAbstractButton):
        record["checkable"] = bool(widget.isCheckable())
        record["checked"] = bool(widget.isChecked())
        if hasattr(widget, "checkState"):
            record["checkState"] = _enum(widget.checkState())
        record["flat"] = bool(widget.isFlat()) if hasattr(widget, "isFlat") else None
        record["autoRepeat"] = bool(widget.autoRepeat())
        record["autoDefault"] = (
            bool(widget.autoDefault()) if hasattr(widget, "autoDefault") else None
        )
        record["default"] = bool(widget.isDefault()) if hasattr(widget, "isDefault") else None
    if isinstance(widget, QComboBox):
        record["editable"] = bool(widget.isEditable())
        record["currentIndex"] = int(widget.currentIndex())
        selected = widget.currentText()
        record["currentText"] = (
            selected
            if include_input_values or not widget.isEditable()
            else "<redacted>"
        )
        record["currentTextRedacted"] = (
            widget.isEditable() and not include_input_values and bool(selected)
        )
        record["itemCount"] = int(widget.count())
        record["maxVisibleItems"] = int(widget.maxVisibleItems())
        record["minimumContentsLength"] = int(widget.minimumContentsLength())
    if isinstance(widget, (QSpinBox, QDoubleSpinBox)):
        record["value"] = widget.value() if include_input_values else "<redacted>"
        record["valueRedacted"] = not include_input_values
        record["minimum"] = widget.minimum()
        record["maximum"] = widget.maximum()
        record["singleStep"] = widget.singleStep()
        record["prefix"] = widget.prefix()
        record["suffix"] = widget.suffix()
    if isinstance(widget, QSlider):
        record["orientation"] = _enum(widget.orientation())
        record["minimum"] = widget.minimum()
        record["maximum"] = widget.maximum()
        record["value"] = widget.value() if include_input_values else "<redacted>"
        record["sliderPosition"] = (
            widget.sliderPosition() if include_input_values else "<redacted>"
        )
        record["valueRedacted"] = not include_input_values
        record["singleStep"] = int(widget.singleStep())
        record["pageStep"] = int(widget.pageStep())
        record["tickPosition"] = _enum(widget.tickPosition())
        record["tickInterval"] = int(widget.tickInterval())
        record["tracking"] = bool(widget.hasTracking())
    if isinstance(widget, QProgressBar):
        record["minimum"] = widget.minimum()
        record["maximum"] = widget.maximum()
        record["value"] = widget.value()
        record["format"] = widget.format()
        record["textVisible"] = bool(widget.isTextVisible())

    for raw_name in widget.dynamicPropertyNames():
        name = bytes(raw_name).decode("utf-8", errors="replace")
        try:
            record["dynamicProperties"][name] = _json_value(widget.property(name))
        except (RuntimeError, TypeError, ValueError) as error:
            record["dynamicProperties"][name] = {
                "unavailable": str(error).splitlines()[0]
            }
    return record


def _screen_info(app: Any) -> dict[str, Any] | None:
    screen = app.primaryScreen()
    if screen is None:
        return None
    return {
        "name": screen.name(),
        "geometry": _rect(screen.geometry()),
        "availableGeometry": _rect(screen.availableGeometry()),
        "devicePixelRatio": float(screen.devicePixelRatio()),
        "logicalDpi": {
            "x": round(float(screen.logicalDotsPerInchX()), 2),
            "y": round(float(screen.logicalDotsPerInchY()), 2),
        },
        "physicalDpi": {
            "x": round(float(screen.physicalDotsPerInchX()), 2),
            "y": round(float(screen.physicalDotsPerInchY()), 2),
        },
    }


def _sample_window() -> Any:
    from PySide6.QtWidgets import (
        QCheckBox,
        QComboBox,
        QFormLayout,
        QHBoxLayout,
        QLabel,
        QLineEdit,
        QMainWindow,
        QPushButton,
        QStatusBar,
        QVBoxLayout,
        QWidget,
    )

    window = QMainWindow()
    window.setObjectName("previewWindow")
    window.setWindowTitle("GhostHand — Desktop Build Preview")
    window.resize(720, 460)
    central = QWidget()
    central.setObjectName("centralWidget")
    window.setCentralWidget(central)

    root = QVBoxLayout(central)
    root.setContentsMargins(24, 22, 24, 18)
    root.setSpacing(14)
    heading = QLabel("Build configuration")
    heading.setObjectName("heading")
    heading.setStyleSheet("font-size: 20px; font-weight: 600;")
    root.addWidget(heading)
    intro = QLabel("Review the target package options before generating a build.")
    intro.setWordWrap(True)
    root.addWidget(intro)

    form = QFormLayout()
    form.setHorizontalSpacing(16)
    form.setVerticalSpacing(12)
    project = QLineEdit("GhostHand")
    project.setObjectName("projectName")
    form.addRow("Project name", project)
    variant = QComboBox()
    variant.setObjectName("buildVariant")
    variant.addItems(["Debug", "Release", "Profile"])
    variant.setCurrentText("Release")
    form.addRow("Build variant", variant)
    root.addLayout(form)

    include_symbols = QCheckBox("Include debug symbols")
    include_symbols.setObjectName("includeSymbols")
    include_symbols.setChecked(True)
    root.addWidget(include_symbols)
    row = QHBoxLayout()
    row.addStretch(1)
    row.addWidget(QPushButton("Cancel"))
    build = QPushButton("Build package")
    build.setObjectName("buildButton")
    build.setDefault(True)
    row.addWidget(build)
    root.addLayout(row)

    status = QStatusBar()
    status.showMessage("Ready")
    window.setStatusBar(status)
    return window


def _load_factory(spec: str, app: Any) -> Any:
    if ":" not in spec:
        raise ValueError("--factory must be written as dotted.module:callable")
    module_name, attribute = spec.split(":", 1)
    if not module_name or not attribute:
        raise ValueError("--factory must be written as dotted.module:callable")
    module = importlib.import_module(module_name)
    factory = getattr(module, attribute, None)
    if not callable(factory):
        raise TypeError(f"{spec!r} does not resolve to a callable")

    try:
        signature = inspect.signature(factory)
        required = [
            parameter
            for parameter in signature.parameters.values()
            if parameter.default is inspect.Parameter.empty
            and parameter.kind
            in (
                inspect.Parameter.POSITIONAL_ONLY,
                inspect.Parameter.POSITIONAL_OR_KEYWORD,
            )
        ]
    except (TypeError, ValueError):
        required = []
    if len(required) == 0:
        result = factory()
    elif len(required) == 1:
        result = factory(app)
    else:
        raise TypeError(
            f"{spec!r} must accept no required arguments or one QApplication argument"
        )

    if result is None:
        top_levels = app.topLevelWidgets()
        if len(top_levels) == 1:
            result = top_levels[0]
    from PySide6.QtWidgets import QWidget

    if not isinstance(result, QWidget):
        raise TypeError(
            f"{spec!r} must return a QWidget (or create exactly one top-level QWidget)"
        )
    return result


def _parse_size(value: str) -> tuple[int, int]:
    match = re.fullmatch(r"([1-9][0-9]*)[xX]([1-9][0-9]*)", value)
    if not match:
        raise argparse.ArgumentTypeError("size must be WIDTHxHEIGHT, e.g. 1024x768")
    return int(match.group(1)), int(match.group(2))


def _arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Render a PySide6 top-level QWidget offscreen, save a PNG, and emit a "
            "JSON report of widget geometry/style/state."
        )
    )
    parser.add_argument(
        "--factory",
        help="Python entry point, module:function; returns a top-level QWidget",
    )
    parser.add_argument("--output", help="PNG screenshot destination")
    parser.add_argument(
        "--properties", help="JSON report destination (defaults beside the PNG)"
    )
    parser.add_argument("--size", type=_parse_size, help="override window size, e.g. 1024x768")
    parser.add_argument("--style", help="Qt style name, e.g. Fusion")
    parser.add_argument(
        "--settle-ms", type=int, default=60, help="wait before capture (default: 60)"
    )
    parser.add_argument(
        "--include-input-values",
        action="store_true",
        help="include values from editable text/numeric controls (redacted by default)",
    )
    parser.add_argument(
        "--self-test", action="store_true", help=argparse.SUPPRESS
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _arg_parser().parse_args(argv)
    if args.settle_ms < 0 or args.settle_ms > 10000:
        raise SystemExit("--settle-ms must be between 0 and 10000")
    if not args.output:
        raise SystemExit("--output is required")
    if not args.self_test and not args.factory:
        raise SystemExit("--factory is required (or use the internal --self-test)")
    if args.self_test and args.factory:
        raise SystemExit("--factory cannot be combined with --self-test")

    output = Path(args.output).expanduser().resolve()
    if output.suffix.lower() != ".png":
        raise SystemExit("--output must use a .png extension")
    properties = (
        Path(args.properties).expanduser().resolve()
        if args.properties
        else output.with_suffix(".json")
    )
    if properties == output:
        raise SystemExit("PNG and JSON outputs must be different files")
    output.parent.mkdir(parents=True, exist_ok=True)
    properties.parent.mkdir(parents=True, exist_ok=True)

    # Set the headless defaults before importing PySide. The wrapper can supply
    # additional, temporary Qt libraries via LD_LIBRARY_PATH.
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    os.environ.setdefault("QT_OPENGL", "software")
    os.environ.setdefault("QT_QUICK_BACKEND", "software")

    from PySide6.QtCore import qVersion
    from PySide6.QtWidgets import QApplication, QStyleFactory, QWidget

    app = QApplication.instance() or QApplication([sys.argv[0]])
    app.setQuitOnLastWindowClosed(False)
    if args.style:
        available = list(QStyleFactory.keys())
        style = next((key for key in available if key.casefold() == args.style.casefold()), None)
        if style is None:
            raise SystemExit(
                f"unknown Qt style {args.style!r}; available styles: {', '.join(available)}"
            )
        app.setStyle(style)

    if args.self_test:
        window = _sample_window()
    else:
        # Make a local project importable when this file is run by path.
        cwd = str(Path.cwd())
        if cwd not in sys.path:
            sys.path.insert(0, cwd)
        window = _load_factory(args.factory, app)
    if not isinstance(window, QWidget):
        raise SystemExit("factory did not return a QWidget")
    if window.parentWidget() is not None:
        raise SystemExit(
            "factory returned a child widget; return the top-level window instead"
        )
    if args.size:
        window.resize(*args.size)

    window.show()
    for _ in range(3):
        app.processEvents()
    if args.settle_ms:
        time.sleep(args.settle_ms / 1000.0)
        app.processEvents()

    pixmap = window.grab()
    image = pixmap.toImage()
    if image.isNull() or image.width() <= 0 or image.height() <= 0:
        raise RuntimeError("Qt returned an empty screenshot")
    if not image.save(str(output), "PNG"):
        raise RuntimeError(f"Qt could not write PNG screenshot to {output}")

    widgets = [window, *window.findChildren(QWidget)]
    indexes = {id(widget): index for index, widget in enumerate(widgets)}
    records = []
    rendering_warnings: list[str] = []
    for index, widget in enumerate(widgets):
        parent = widget.parentWidget()
        parent_index = indexes.get(id(parent)) if parent is not None else None
        record = _widget_record(widget, index, parent_index, args.include_input_values)
        records.append(record)
        class_name = str(record["qtClass"]).casefold()
        if "opengl" in class_name or "quick" in class_name:
            rendering_warnings.append(
                f"{record['qtClass']} is present; this CPU-raster snapshot does not "
                "validate OpenGL/Qt Quick rendering."
            )

    if os.environ.get("GHOSTHAND_QT_CPU_SHIMS") == "1":
        rendering_warnings.insert(
            0,
            "CPU-only no-op link shims are active for EGL, OpenGL, xkbcommon and "
            "D-Bus. Standard QWidget/QPainter raster output is tested; GL/EGL, "
            "keyboard input, D-Bus and native desktop integration are not.",
        )

    screen = _screen_info(app)
    report = {
        "runtime": {
            "binding": "PySide6",
            "qtVersion": qVersion(),
            "platformPlugin": os.environ.get("QT_QPA_PLATFORM", "(Qt default)"),
            "style": app.style().objectName(),
            "devicePixelRatio": float(window.devicePixelRatioF()),
            "screen": screen,
            "cpuOnlyLinkShims": os.environ.get("GHOSTHAND_QT_CPU_SHIMS") == "1",
        },
        "window": {
            "qtClass": window.metaObject().className(),
            "pythonClass": type(window).__name__,
            "objectName": window.objectName(),
            "title": window.windowTitle(),
            "geometry": _rect(window.geometry()),
            "frameGeometry": _rect(window.frameGeometry()),
            "visible": bool(window.isVisible()),
            "enabled": bool(window.isEnabled()),
        },
        "screenshot": {
            "file": str(output),
            "format": "PNG",
            "width": int(image.width()),
            "height": int(image.height()),
            "devicePixelRatio": float(image.devicePixelRatio()),
        },
        "widgetCount": len(records),
        "widgets": records,
        "warnings": rendering_warnings,
        "notes": [
            "The image is QWidget.grab() output: client area only, without OS window-manager decorations.",
            "Reported screen geometry/DPI come from Qt's active platform plugin; in offscreen mode they are virtual values.",
        ],
    }
    with properties.open("w", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")

    if args.self_test:
        if image.width() != 720 or image.height() != 460:
            raise RuntimeError(
                f"self-test expected a 720x460 PNG, got {image.width()}x{image.height()}"
            )
        sampled_colors = {
            image.pixelColor(x, y).rgba()
            for x in range(0, image.width(), 8)
            for y in range(0, image.height(), 8)
        }
        if len(sampled_colors) < 8:
            raise RuntimeError("self-test PNG appears blank or uniformly colored")
        names = {record["objectName"] for record in records}
        required_names = {"previewWindow", "projectName", "buildVariant", "buildButton"}
        if not required_names.issubset(names):
            raise RuntimeError("self-test widget tree is missing expected object names")
        project = next(record for record in records if record["objectName"] == "projectName")
        if project.get("text") != "<redacted>" or not project.get("textRedacted"):
            raise RuntimeError("self-test expected editable text to be redacted")

    print(
        json.dumps(
            {
                "qtVersion": qVersion(),
                "style": app.style().objectName(),
                "window": window.windowTitle(),
                "png": str(output),
                "properties": str(properties),
                "size": [int(image.width()), int(image.height())],
                "widgets": len(records),
                "warnings": rendering_warnings,
            },
            ensure_ascii=False,
        )
    )
    window.close()
    app.processEvents()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
