"""Validate shipped artwork metadata without an Android device or network access.

Run: python3 native-launcher/checks/check_art_catalog.py [optional-catalog.json]
An entry without an ``asset`` is a locally generated painting or independent act.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys
from urllib.parse import urlsplit


PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / "app/src/main/assets"
THEMES = {"games", "media", "comics", "books", "history"}
WORLDS = {"hyrule", "tatooine", "gotham", "tetromino", "london", "b612", "tranquility", "snake"}
LANGUAGES = ("en", "ru", "es")
MAX_REVEAL_ANCHORS = 8
PAINTING_STYLES = {"scene", "ink", "tiles", "focus", "blocks", "weave", "still"}
# Official franchise/creator/institution sources. Review additions with the content.
PRIMARY_HOSTS = {
    "zelda.nintendo.com", "www.starwars.com", "tetris.com", "play.tetris.com",
    "www.doctorwho.tv", "www.dc.com", "www.lepetitprince.com", "www.nasa.gov", "www.hmd.com",
}


def validate_direction(entry: dict, label: str, errors: list[str]) -> str:
    direction = entry.get("direction")
    if not isinstance(direction, dict):
        errors.append(f"{label}: direction must contain the authored act kind and treatments")
        return "painting"
    kind = direction.get("kind")
    if kind not in ("painting", "snakes"):
        errors.append(f"{label}: unsupported direction.kind")
        return "painting"
    treatments = direction.get("treatments")
    if kind == "snakes":
        if treatments != []:
            errors.append(f"{label}: a snake act must not have painting treatments")
        if entry.get("world") != "snake" or "asset" in entry:
            errors.append(f"{label}: a snake act needs its own procedural snake world")
        return kind
    if entry.get("world") == "snake":
        errors.append(f"{label}: the snake world must be an independent act")
    if not isinstance(treatments, list) or not 1 <= len(treatments) <= 8:
        errors.append(f"{label}: painting direction must contain 1–8 treatments")
        return kind
    seen: set[tuple] = set()
    for index, treatment in enumerate(treatments):
        treatment_label = f"{label}: direction.treatments[{index}]"
        if not isinstance(treatment, dict):
            errors.append(f"{treatment_label} must be an object")
            continue
        style = treatment.get("style")
        if not isinstance(style, str) or style not in PAINTING_STYLES:
            errors.append(f"{treatment_label}: style must be a concrete painting treatment")
        columns = treatment.get("columns", 160)
        rows = treatment.get("rows", 90)
        valid_grid = type(columns) is int and type(rows) is int
        if not valid_grid or not 2 <= columns <= 256 or not 2 <= rows <= 256 or columns * rows > 160 * 90:
            errors.append(f"{treatment_label}: grid must be bounded to 2–256 per axis and 14400 cells")
        retained = treatment.get("retainPixelGrid", False)
        if type(retained) is not bool:
            errors.append(f"{treatment_label}: retainPixelGrid must be boolean")
        if isinstance(style, str) and valid_grid and type(retained) is bool:
            key = (style, columns, rows, retained)
            if key in seen:
                errors.append(f"{treatment_label}: duplicate treatment")
            seen.add(key)
    return kind


def validate(catalog: Path) -> tuple[list[str], int]:
    errors: list[str] = []
    try:
        data = json.loads(catalog.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        return [f"{catalog}: {error}"], 0
    if not isinstance(data, dict):
        return ["catalog must be a JSON object"], 0
    if type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1:
        errors.append("schemaVersion must be integer 1")
    entries = data.get("artworks")
    if not isinstance(entries, list):
        return errors + ["artworks must be a nonempty array"], 0
    if not entries:
        errors.append("artworks must be a nonempty array")

    seen_ids: set[str] = set()
    represented: set[str] = set()
    generated: set[str] = set()
    painting_count = 0
    artwork_worlds: dict[str, str] = {}
    archived_names = {path.name for path in (PROJECT / "design/art-originals").glob("*") if path.is_file()}
    for index, entry in enumerate(entries):
        label = f"artworks[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{label}: must be an object")
            continue
        entry_id = entry.get("id")
        if not isinstance(entry_id, str) or not entry_id.strip():
            errors.append(f"{label}: id must be a nonblank string")
        else:
            label += f" ({entry_id})"
            if entry_id in seen_ids:
                errors.append(f"{label}: duplicate id")
            seen_ids.add(entry_id)

        theme = entry.get("theme")
        world = entry.get("world")
        direction_kind = validate_direction(entry, label, errors)
        if direction_kind == "painting":
            painting_count += 1
        if not isinstance(world, str) or world not in WORLDS:
            errors.append(f"{label}: world must identify a supported composition, independently of theme")
        valid_theme = isinstance(theme, str) and theme in THEMES
        if not valid_theme:
            errors.append(f"{label}: unsupported theme {theme!r}; expected one of {sorted(THEMES)}")
        else:
            represented.add(theme)
            if "asset" not in entry and direction_kind == "painting":
                generated.add(theme)

        for field in ("title", "reference"):
            translations = entry.get(field)
            if not isinstance(translations, dict):
                errors.append(f"{label}: {field} must contain en/ru/es translations")
                continue
            for language in LANGUAGES:
                value = translations.get(language)
                if not isinstance(value, str) or not value.strip():
                    errors.append(f"{label}: {field}.{language} must be a nonblank string")

        reveal = entry.get("reveal")
        if direction_kind != "painting":
            pass
        elif not isinstance(reveal, dict):
            errors.append(f"{label}: reveal must contain an authored anchors array")
        else:
            if reveal.get("development") not in ("contours", "light"):
                errors.append(f"{label}: reveal.development must be contours or light")
            anchors = reveal.get("anchors")
            if not isinstance(anchors, list) or not 1 <= len(anchors) <= MAX_REVEAL_ANCHORS:
                errors.append(f"{label}: reveal.anchors must contain 1–{MAX_REVEAL_ANCHORS} anchors")
            else:
                for anchor_index, anchor in enumerate(anchors):
                    anchor_label = f"{label}: reveal.anchors[{anchor_index}]"
                    if not isinstance(anchor, dict):
                        errors.append(f"{anchor_label} must be an object")
                        continue
                    for field in ("x", "y", "radiusX", "radiusY", "delay"):
                        value = anchor.get(field)
                        if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1:
                            errors.append(f"{anchor_label}.{field} must be a finite number between 0 and 1")
                        elif field in ("radiusX", "radiusY") and value == 0:
                            errors.append(f"{anchor_label}.{field} must be greater than zero")

        source = entry.get("source")
        try:
            if not isinstance(source, str) or source.strip() != source or not source:
                raise ValueError("must be a nonblank URL without surrounding whitespace")
            url = urlsplit(source)
            if url.scheme != "https" or url.hostname not in PRIMARY_HOSTS:
                raise ValueError(f"must use https on an approved primary host ({', '.join(sorted(PRIMARY_HOSTS))})")
            if url.username is not None or url.password is not None or url.port not in (None, 443):
                raise ValueError("must not contain credentials or a non-HTTPS port")
        except ValueError as error:
            errors.append(f"{label}: source {error}")

        if "asset" in entry:
            asset = entry["asset"]
            if not isinstance(asset, str) or not asset.strip():
                errors.append(f"{label}: asset must be a nonblank path, or omit it for a generated fallback")
                continue
            path = Path(asset)
            resolved = (ASSETS / path).resolve()
            if path.is_absolute() or ".." in path.parts or "\\" in asset or not resolved.is_relative_to(ASSETS.resolve()):
                errors.append(f"{label}: asset must stay within the shipped assets directory")
            elif not resolved.is_file():
                errors.append(f"{label}: missing asset {asset!r}")
            else:
                digest = hashlib.sha256(resolved.read_bytes()).hexdigest()
                previous_world = artwork_worlds.setdefault(digest, world)
                if previous_world != world:
                    errors.append(f"{label}: different worlds must not reuse the same background image")
            if path.name in archived_names or "art-originals" in path.parts:
                errors.append(f"{label}: archived original must not be referenced: {asset!r}")

    if not painting_count:
        errors.append("catalog must contain at least one painting")
    for theme in sorted(THEMES - represented):
        errors.append(f"theme {theme!r}: no artwork entry")
    for theme in sorted(THEMES - generated):
        errors.append(f"theme {theme!r}: no generated weekly fallback (entry without asset)")
    return errors, len(entries)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog", nargs="?", type=Path, default=ASSETS / "art/catalog.json")
    args = parser.parse_args()
    errors, count = validate(args.catalog)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        print(f"Art catalog failed: {len(errors)} error(s)", file=sys.stderr)
        return 1
    print(f"Art catalog OK: {count} entries; 5 themes with weekly paintings; en/ru/es; primary sources; distinct acts and curated presentation profiles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
