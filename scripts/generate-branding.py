#!/usr/bin/env python3
"""Export the separate branding SVG sources to PNG and Android vector resources."""

import argparse
import copy
import math
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / 'assets/branding'
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
NS = {'s': 'http://www.w3.org/2000/svg'}
DRAWABLES = {
    'path',
    'rect',
    'circle',
    'ellipse',
    'polygon',
    'polyline',
    'line',
    'text',
    'image',
    'use',
}


def drawable_elements(root):
    for element in root:
        name = element.tag.rsplit('}', 1)[-1]
        if name == 'defs':
            continue
        if name in DRAWABLES:
            yield element
        else:
            yield from drawable_elements(element)


def android_sources(branding=BRANDING):
    """Split the first painted shape (background) from the remaining artwork."""
    source = ET.parse(branding / 'icon.svg').getroot()
    box = tuple(map(float, source.attrib['viewBox'].replace(',', ' ').split()))
    if (
        len(box) != 4
        or not all(map(math.isfinite, box))
        or box[2] <= 0
        or box[2] != box[3]
    ):
        raise ValueError('icon.svg must have a positive square viewBox')
    x, y, width, height = box
    # Android expands adaptive layers by 1.5; the SVG artboard must remain the visible center.
    expanded = (x - width / 4, y - height / 4, width * 1.5, height * 1.5)
    result = {}
    for background, name in [
        (False, 'ic_launcher_foreground'),
        (True, 'ic_launcher_background'),
    ]:
        root = copy.deepcopy(source)
        shapes = list(drawable_elements(root))
        if len(shapes) < 2:
            raise ValueError(
                'icon.svg needs a background shape followed by foreground artwork'
            )
        parents = {child: parent for parent in root.iter() for child in parent}
        for shape in (shapes[1:] if background else shapes[:1]):
            parents[shape].remove(shape)
        # Illustrator may clip the whole artboard; extend only that rectangular clip for overscan.
        for clip in root.findall('.//s:clipPath', NS):
            if (
                len(clip) != 1
                or clip[0].tag != f"{{{NS['s']}}}rect"
                or clip.get('clipPathUnits', 'userSpaceOnUse') != 'userSpaceOnUse'
            ):
                continue
            rect = clip[0]
            if (
                'transform' in clip.attrib
                or 'transform' in rect.attrib
                or float(rect.get('rx', '0'))
                or float(rect.get('ry', '0'))
            ):
                continue
            bounds = tuple(
                float(rect.get(key, '0')) for key in ('x', 'y', 'width', 'height')
            )
            if all(math.isclose(a, b) for a, b in zip(bounds, box)):
                rect.attrib.update(
                    {
                        key: f'{value:g}'
                        for key, value in zip(('x', 'y', 'width', 'height'), expanded)
                    }
                )
        root.set('viewBox', ' '.join(f'{value:g}' for value in expanded))
        root.set('width', '108')
        root.set('height', '108')
        result[name] = root
    status = ET.parse(branding / 'status-icon.svg').getroot()
    status.set('width', '24')
    status.set('height', '24')
    result['ic_notification'] = status
    return result


def android_vectors(branding=BRANDING):
    ET.register_namespace('', NS['s'])
    with tempfile.TemporaryDirectory(prefix='message487-vectors-') as temp:
        directory = Path(temp)
        sources = android_sources(branding)
        for name, root in sources.items():
            ET.ElementTree(root).write(
                directory / f'{name}.svg', encoding='utf-8', xml_declaration=True
            )
        subprocess.run(
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
        return {
            f'{name}.xml': ET.parse(directory / f'{name}.xml').getroot()
            for name in sources
        }


def generate(output_root):
    for tool in ('rsvg-convert', 'magick'):
        if not shutil.which(tool):
            raise RuntimeError(f'Required tool is missing: {tool}')
    vectors = android_vectors()
    ET.register_namespace('android', ANDROID)
    ET.register_namespace('aapt', AAPT)

    def render(source, destination, size=None, opaque=False):
        destination = output_root / destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as temp:
            raster = Path(temp) / 'render.png'
            command = ['rsvg-convert', str(BRANDING / source), '-o', str(raster)]
            if size:
                command += ['-w', str(size), '-h', str(size)]
            subprocess.run(command, check=True)
            if opaque:
                subprocess.run(
                    [
                        'magick',
                        str(raster),
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
                        str(destination),
                    ],
                    check=True,
                )
            else:
                shutil.copyfile(raster, destination)

    for name, size in [
        ('icon', 512),
        ('icon-round', 512),
        ('status-icon', 24),
        ('feature', None),
    ]:
        render(f'{name}.svg', f'assets/branding/{name}.png', size)
    store = 'fastlane/metadata/android/en-US/images'
    render('icon.svg', f'{store}/icon.png', 512, opaque=True)
    render('feature.svg', f'{store}/featureGraphic.png', opaque=True)
    for density, size in [
        ('mdpi', 48),
        ('hdpi', 72),
        ('xhdpi', 96),
        ('xxhdpi', 144),
        ('xxxhdpi', 192),
    ]:
        for source, name in [
            ('icon.svg', 'ic_launcher'),
            ('icon-round.svg', 'ic_launcher_round'),
        ]:
            render(source, f'app/src/main/res/mipmap-{density}/{name}.png', size)
    for name, tree in vectors.items():
        destination = output_root / 'app/src/main/res/drawable' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        ET.indent(tree, space='    ')
        destination.write_text(ET.tostring(tree, encoding='unicode') + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '--output-root',
        type=Path,
        default=ROOT,
        help='Export root (default: repository)',
    )
    generate(parser.parse_args().output_root)
