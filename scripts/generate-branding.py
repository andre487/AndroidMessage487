#!/usr/bin/env python3
"""Generate Android vectors and store artwork from the Inkscape master."""

import argparse
import pathlib
import subprocess as sp
import tempfile
import xml.etree.ElementTree as ET

import branding

ROOT = branding.MASTER.parents[1]
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'


def android_sources(document):
    sources = {
        'ic_launcher_background': branding.page(document, 'icon', 'background'),
        'ic_launcher_foreground': branding.page(document, 'icon', 'foreground'),
        'ic_notification': branding.page(document, 'status-icon'),
    }
    for name, root in sources.items():
        x, y, w, h = map(float, root.get('viewBox').split())
        if w != h:
            raise ValueError(f'{name} needs a square page')
        # The background fills the adaptive canvas; the foreground occupies its central 72 dp.
        if name == 'ic_launcher_foreground':
            root.set('viewBox', f'{x-w/4:g} {y-h/4:g} {w*1.5:g} {h*1.5:g}')
        size = '24' if name == 'ic_notification' else '108'
        root.set('width', size)
        root.set('height', size)
        # Svg2Vector cannot import Inkscape's unused sRGB profile declaration.
        defs = root.find(branding.SVG + 'defs')
        for profile in defs.findall(branding.SVG + 'color-profile'):
            defs.remove(profile)
    return sources


def android_vectors(document):
    with tempfile.TemporaryDirectory(prefix='message487-vectors-') as temp:
        directory = pathlib.Path(temp)
        sources = android_sources(document)
        offsets = {}
        for name, root in sources.items():
            x, y, width, height = root.get('viewBox').split()
            offsets[name] = (-float(x), -float(y))
            # Avoid the importer's path rewriting/rounding for a viewBox translation.
            # Apply this translation as an Android group after conversion instead.
            root.set('viewBox', f'0 0 {width} {height}')
            ET.ElementTree(root).write(
                directory / f'{name}.svg', encoding='utf-8', xml_declaration=True
            )
        sp.run(
            [
                'bash',
                '-ec',
                'source "$1"; shift; exec "$@"',
                'branding',
                str(ROOT / 'scripts/java-toolchain.sh'),
                str(ROOT / 'gradlew'),
                '--offline',
                '--console=plain',
                '-q',
                'convertBrandingVectors',
                f'-PbrandingVectorDir={directory}',
            ],
            check=True,
            cwd=ROOT,
        )
        result = {}
        for name in sources:
            vector = ET.parse(directory / f'{name}.xml').getroot()
            dx, dy = offsets[name]
            if dx or dy:
                group = ET.Element(
                    'group',
                    {
                        f'{{{ANDROID}}}translateX': f'{dx:.15g}',
                        f'{{{ANDROID}}}translateY': f'{dy:.15g}',
                    },
                )
                for child in list(vector):
                    vector.remove(child)
                    group.append(child)
                vector.append(group)
            result[f'{name}.xml'] = vector
        return result


def generate(output_root, export_pages=False):
    document = ET.parse(branding.MASTER).getroot()
    vectors = android_vectors(document)
    ET.register_namespace('android', ANDROID)
    ET.register_namespace('aapt', AAPT)
    store = output_root / 'fastlane/metadata/android/en-US/images'
    store.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        work = pathlib.Path(temporary)
        for page_name, filename, width in [
            ('icon', 'icon.png', 512),
            ('feature', 'featureGraphic.png', 1024),
        ]:
            svg = ET.tostring(branding.page(document, page_name), encoding='utf-8')
            raster = work / filename
            sp.run(
                ['rsvg-convert', '-w', str(width), '-o', raster], input=svg, check=True
            )
            sp.run(
                [
                    'magick',
                    raster,
                    '-background',
                    'white',
                    '-alpha',
                    'remove',
                    '-alpha',
                    'off',
                    '-type',
                    'TrueColor',
                    '-define',
                    'png:color-type=2',
                    '-strip',
                    store / filename,
                ],
                check=True,
            )
    for name, tree in vectors.items():
        destination = output_root / 'app/src/main/res/drawable' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        ET.indent(tree, space='    ')
        destination.write_text(ET.tostring(tree, encoding='unicode') + '\n')
    if export_pages:
        directory = output_root / 'assets/branding'
        directory.mkdir(parents=True, exist_ok=True)
        for name in branding.PAGES:
            ET.ElementTree(branding.page(document, name)).write(
                directory / f'{name}.svg', encoding='utf-8', xml_declaration=True
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-root', type=pathlib.Path, default=ROOT)
    parser.add_argument(
        '--export-pages',
        action='store_true',
        help='Also refresh the standalone SVGs in assets/branding',
    )
    args = parser.parse_args()
    generate(args.output_root, args.export_pages)


if __name__ == '__main__':
    main()
