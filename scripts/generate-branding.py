#!/usr/bin/env python3
"""Generate branding PNG and Android resources from SVG; optionally export AI first."""

import argparse
import copy
import math
import os
import re
import shutil
import subprocess as sp
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / 'assets/branding'
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
NS = {'s': 'http://www.w3.org/2000/svg'}
ARTBOARDS = {
    'icon': (512, 512),
    'icon-round': (512, 512),
    'status-icon': (24, 24),
    'feature': (1024, 500),
}
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
        if name in ('defs', 'clipPath', 'mask', 'symbol'):
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


def export_pdf(source, destination):
    """Read the PDF-compatible AI pages in the project's artboard order."""
    for tool in ('pdfinfo', 'pdftocairo'):
        if not shutil.which(tool):
            raise RuntimeError(f'Required tool is missing: {tool} (install Poppler)')
    info = sp.run(
        ['pdfinfo', str(source)],
        check=True,
        capture_output=True,
        text=True,
        env={**os.environ, 'LC_ALL': 'C'},
    ).stdout
    if not re.search(rf'^Pages:\s+{len(ARTBOARDS)}\s*$', info, re.MULTILINE):
        raise ValueError(
            'Expected four PDF pages in branding.ai; save with PDF compatibility'
        )
    with tempfile.TemporaryDirectory(prefix='message487-pdf-') as temp:
        directory = Path(temp)
        for page, (name, size) in enumerate(ARTBOARDS.items(), 1):
            target = directory / f'{name}.svg'
            sp.run(
                [
                    'pdftocairo',
                    '-svg',
                    '-f',
                    str(page),
                    '-l',
                    str(page),
                    str(source),
                    str(target),
                ],
                check=True,
            )
            root = ET.parse(target).getroot()
            box = tuple(map(float, root.attrib['viewBox'].split()))
            if box != (0, 0, *size):
                raise ValueError(f'Unexpected PDF page bounds for {name}: {box}')
            if root.find('.//s:image', NS) is not None:
                raise ValueError(f'PDF page for {name} contains raster images')
            # PDF points and CSS pixels differ; keep the project's artboard pixel sizes.
            root.set('width', str(size[0]))
            root.set('height', str(size[1]))
            ET.register_namespace('', NS['s'])
            ET.register_namespace('xlink', 'http://www.w3.org/1999/xlink')
            ET.indent(root, space='  ')
            ET.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
        destination.mkdir(parents=True, exist_ok=True)
        for target in directory.glob('*.svg'):
            shutil.copyfile(target, destination / target.name)


def export_illustrator(source, destination):
    """Export a disk snapshot without modifying the user's open Illustrator document."""
    if not shutil.which('osascript'):
        raise RuntimeError('Illustrator export requires macOS and Adobe Illustrator')
    with tempfile.TemporaryDirectory(prefix='message487-illustrator-') as temp:
        directory = Path(temp)
        snapshot = directory / 'source.ai'
        shutil.copyfile(source, snapshot)
        sp.run(
            [
                'osascript',
                str(ROOT / 'scripts/export-branding.applescript'),
                str(ROOT / 'scripts/export-branding.jsx'),
                str(snapshot),
                str(directory),
            ],
            check=True,
        )
        exports = {}
        for name, size in ARTBOARDS.items():
            files = list((directory / name).glob('*.svg'))
            if len(files) != 1:
                raise ValueError(f'Expected one Illustrator SVG export for {name}')
            root = ET.parse(files[0]).getroot()
            box = tuple(map(float, root.attrib['viewBox'].split()))
            if box != (0, 0, *size):
                raise ValueError(f'Unexpected artboard bounds for {name}: {box}')
            # Illustrator invents random ID suffixes on every export. Keep diffs stable.
            ids = {}
            counts = {}
            for element in root.iter():
                old = element.get('id')
                if old is None:
                    continue
                base = re.sub(r'_\d{20,}_$', '', old)
                if re.fullmatch(r'SVGID_\d+_', base):
                    base = 'clip'
                counts[base] = counts.get(base, 0) + 1
                ids[old] = f'{base}-{counts[base]}'
                element.set('id', ids[old])
            for element in root.iter():
                for key, value in list(element.attrib.items()):
                    if key.endswith('}href') or key == 'href':
                        if value.startswith('#') and value[1:] in ids:
                            value = '#' + ids[value[1:]]
                    value = re.sub(
                        r'url\(#([^)]+)\)',
                        lambda match: f'url(#{ids.get(match[1], match[1])})',
                        value,
                    )
                    element.set(key, value)
            ET.register_namespace('', NS['s'])
            ET.register_namespace('xlink', 'http://www.w3.org/1999/xlink')
            ET.indent(root, space='  ')
            exports[name] = (
                ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'
            )
        destination.mkdir(parents=True, exist_ok=True)
        for name, data in exports.items():
            (destination / f'{name}.svg').write_bytes(data)


def generate(output_root, branding=BRANDING):
    for tool in ('rsvg-convert', 'magick'):
        if not shutil.which(tool):
            raise RuntimeError(f'Required tool is missing: {tool}')
    vectors = android_vectors(branding)
    ET.register_namespace('android', ANDROID)
    ET.register_namespace('aapt', AAPT)

    def render(source, destination, size=None, opaque=False):
        destination = output_root / destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as temp:
            raster = Path(temp) / 'render.png'
            command = ['rsvg-convert', str(branding / source), '-o', str(raster)]
            if size:
                command += ['-w', str(size), '-h', str(size)]
            sp.run(command, check=True)
            if opaque:
                sp.run(
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '--output-root',
        type=Path,
        default=ROOT,
        help='Export root (default: repository)',
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        '--illustrator',
        action='store_true',
        help='Export AI artboards through Illustrator (macOS) before generating assets',
    )
    mode.add_argument(
        '--pdf',
        action='store_true',
        help='Export PDF-compatible AI pages through Poppler before generating assets',
    )
    args = parser.parse_args(argv)
    with tempfile.TemporaryDirectory(prefix='message487-branding-') as temp:
        sources = Path(temp)
        if not (args.illustrator or args.pdf):
            for name in ARTBOARDS:
                shutil.copyfile(BRANDING / f'{name}.svg', sources / f'{name}.svg')
        else:
            exporter = export_illustrator if args.illustrator else export_pdf
            exporter(BRANDING / 'branding.ai', sources)
        generate(args.output_root, sources)
        destination = args.output_root / 'assets/branding'
        if args.illustrator or args.pdf:
            destination.mkdir(parents=True, exist_ok=True)
            for source in sources.glob('*.svg'):
                shutil.copyfile(source, destination / source.name)


if __name__ == '__main__':
    main()
