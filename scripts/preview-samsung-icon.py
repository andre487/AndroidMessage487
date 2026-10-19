#!/usr/bin/env python3
"""Preview the adaptive artwork with an approximate Samsung-style mask."""

import argparse
import math
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SVG = 'http://www.w3.org/2000/svg'


def preview_svg(source, bevel=0.18, effects=True):
    tree = ET.parse(source)
    root = tree.getroot()
    x, y, width, height = map(float, root.attrib['viewBox'].split())
    clip = None
    for candidate in root.iter(f'{{{SVG}}}clipPath'):
        # Illustrator renames IDs and references clips through CSS classes.
        # Replace only a full-artboard circle; preserve unrelated internal clipping.
        if (
            len(candidate) != 1
            or candidate[0].tag != f'{{{SVG}}}circle'
            or candidate.get('clipPathUnits', 'userSpaceOnUse') != 'userSpaceOnUse'
            or 'transform' in candidate.attrib
            or 'transform' in candidate[0].attrib
        ):
            continue
        circle = candidate[0]
        cx, cy, radius = (float(circle.get(key, '0')) for key in ('cx', 'cy', 'r'))
        if all(
            math.isclose(actual, expected)
            for actual, expected in (
                (cx, x + width / 2),
                (cy, y + height / 2),
                (radius, width / 2),
                (radius, height / 2),
            )
        ):
            clip = candidate
            clip.remove(circle)
            break
    if clip is None:
        defs = ET.SubElement(root, f'{{{SVG}}}defs')
        clip = ET.SubElement(defs, f'{{{SVG}}}clipPath', {'id': 'samsung-preview-mask'})
        group = ET.Element(f'{{{SVG}}}g', {'clip-path': 'url(#samsung-preview-mask)'})
        for child in list(root):
            if child.tag != f'{{{SVG}}}defs':
                root.remove(child)
                group.append(child)
        root.append(group)
    # ponytail: approximate mask; use a device-extracted mask for pixel-exact comparison.
    ET.SubElement(
        clip,
        f'{{{SVG}}}path',
        {
            'transform': f'translate({x} {y}) scale({width / 100} {height / 100})',
            'd': 'M50 0 C88 0 100 12 100 50 C100 88 88 100 50 100 '
            'C12 100 0 88 0 50 C0 12 12 0 50 0Z',
        },
    )
    if effects:
        # Shadow proportions from SemAppIconSolution; SVG blur differs from Android Skia.
        # ponytail: bevel is calibrated visually; use Samsung's on-device API for exact output.
        defs = ET.SubElement(root, f'{{{SVG}}}defs')
        defs.append(ET.fromstring(f'''<filter xmlns="{SVG}" id="samsung-preview-effects"
            filterUnits="userSpaceOnUse" x="{x-width*.1}" y="{y-height*.1}"
            width="{width*1.2}" height="{height*1.2}" color-interpolation-filters="sRGB">
          <feGaussianBlur in="SourceAlpha" stdDeviation="{width/96}" result="shadow1"/>
          <feGaussianBlur in="SourceAlpha" stdDeviation="{width/48}" result="shadow2"/>
          <feFlood flood-color="black" flood-opacity="{41/255}"/>
          <feComposite in2="shadow1" operator="in" result="ambient1"/>
          <feFlood flood-color="black" flood-opacity="{26/255}"/>
          <feComposite in2="shadow2" operator="in" result="ambient2"/>
          <feGaussianBlur in="SourceAlpha" stdDeviation="{width*.002}" result="edge"/>
          <feOffset in="edge" dx="{width*.003}" dy="{height*.006}" result="down"/>
          <feComposite in="SourceAlpha" in2="down" operator="out" result="top"/>
          <feFlood flood-color="white" flood-opacity="{bevel}"/>
          <feComposite in2="top" operator="in" result="highlight"/>
          <feOffset in="edge" dx="{-width*.003}" dy="{-height*.006}" result="up"/>
          <feComposite in="SourceAlpha" in2="up" operator="out" result="bottom"/>
          <feFlood flood-color="black" flood-opacity="{bevel}"/>
          <feComposite in2="bottom" operator="in" result="shade"/>
          <feMerge><feMergeNode in="ambient1"/><feMergeNode in="ambient2"/>
            <feMergeNode in="SourceGraphic"/><feMergeNode in="shade"/>
            <feMergeNode in="highlight"/></feMerge>
        </filter>'''))
        wrapper = ET.Element(
            f'{{{SVG}}}g',
            {
                'transform': f'translate({x + width*.02} {y + height*.02}) scale(.96) translate({-x} {-y})',
                'filter': 'url(#samsung-preview-effects)',
            },
        )
        for child in list(root):
            if child.tag != f'{{{SVG}}}defs':
                root.remove(child)
                wrapper.append(child)
        root.append(wrapper)
    ET.register_namespace('', SVG)
    return ET.tostring(tree.getroot(), encoding='unicode')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '--source',
        type=Path,
        default=ROOT / 'assets/branding/icon.svg',
        help='Editable source SVG (default: assets/branding/icon.svg)',
    )
    parser.add_argument('--flat', action='store_true', help='Disable shadow and bevel')
    parser.add_argument(
        '--bevel',
        type=float,
        default=0.18,
        help='Edge strength, 0 to 1 (default: 0.18)',
    )
    parser.add_argument(
        '--size', type=int, default=140, help='Output size in pixels (default: 140)'
    )
    parser.add_argument('output', type=Path, help='Output .svg or .png file')
    args = parser.parse_args()
    if args.output.suffix.lower() not in ('.svg', '.png'):
        parser.error('output must end in .svg or .png')
    if not 0 <= args.bevel <= 1:
        parser.error('--bevel must be between 0 and 1')
    if args.size <= 0:
        parser.error('--size must be positive')
    svg_root = ET.fromstring(
        preview_svg(args.source, bevel=args.bevel, effects=not args.flat)
    )
    svg_root.set('width', str(args.size))
    svg_root.set('height', str(args.size))
    svg = ET.tostring(svg_root, encoding='unicode')
    if args.output.suffix.lower() == '.svg':
        args.output.write_text(svg + '\n')
    else:
        subprocess.run(
            ['rsvg-convert', '-o', str(args.output)],
            input=svg.encode(),
            check=True,
        )


if __name__ == '__main__':
    main()
