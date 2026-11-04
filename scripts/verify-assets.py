#!/usr/bin/env python3
"""Check packaged fonts, artwork, and icon geometry against the pinned sources."""

from hashlib import sha256
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "app/src/main/res"
IOS = ROOT / "reference/ios/iYoinks/AppResources"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def identical(source: Path, packaged: Path) -> None:
    if sha256(source.read_bytes()).digest() != sha256(packaged.read_bytes()).digest():
        raise SystemExit(f"Asset differs from its pinned source: {packaged.relative_to(ROOT)}")


font_count = 0
for family, resource_family in (("Text", "text"), ("Notch", "notch")):
    for weight, resource_weight in (
        ("Regular", "regular"),
        ("Medium", "medium"),
        ("SemiBold", "semibold"),
        ("Bold", "bold"),
    ):
        identical(
            IOS / f"Fonts/StackSans{family}-{weight}.ttf",
            RESOURCES / f"font/stack_sans_{resource_family}_{resource_weight}.ttf",
        )
        font_count += 1

for original, packaged in (
    ("BrandMark.png", "brand_mark.png"),
    ("BrandMarkLight.png", "brand_mark_light.png"),
):
    identical(IOS / original, RESOURCES / f"drawable/{packaged}")

identical(IOS / "Fonts/OFL.txt", ROOT / "app/src/main/assets/font-license.txt")
identical(ROOT / "reference/runeicons/LICENSE", ROOT / "app/src/main/assets/rune-license.txt")

icons = sorted((ROOT / "reference/runeicons").glob("*.svg"))
if len(icons) != 22:
    raise SystemExit(f"Expected the 22 packaged Rune icons, found {len(icons)}.")

for source in icons:
    svg = ET.parse(source).getroot()
    vector_path = RESOURCES / f"drawable/rune_{source.stem}.xml"
    vector = ET.parse(vector_path).getroot()
    _, _, width, height = svg.attrib["viewBox"].split()
    if (vector.get(ANDROID + "viewportWidth"), vector.get(ANDROID + "viewportHeight")) != (width, height):
        raise SystemExit(f"Icon viewport differs: {source.stem}")
    source_paths = list(svg)
    packaged_paths = vector.findall("path")
    if len(source_paths) != len(packaged_paths):
        raise SystemExit(f"Icon path count differs: {source.stem}")
    for original, packaged in zip(source_paths, packaged_paths):
        if original.tag.rsplit("}", 1)[-1] != "path" or original.get("d") != packaged.get(ANDROID + "pathData"):
            raise SystemExit(f"Icon geometry differs: {source.stem}")
        for svg_attribute, vector_attribute in (
            ("stroke-width", "strokeWidth"),
            ("stroke-linecap", "strokeLineCap"),
            ("stroke-linejoin", "strokeLineJoin"),
        ):
            expected = original.get(svg_attribute, svg.get(svg_attribute))
            if expected is not None and packaged.get(ANDROID + vector_attribute) != expected:
                raise SystemExit(f"Icon {svg_attribute} differs: {source.stem}")

print(f"Verified {font_count} fonts, 2 brand images, 22 Rune icons, and font/icon licenses.")
