"""Export the separate branding SVG sources to PNG and Android vector resources."""

import argparse
import re
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


def color(value):
    if value == 'none':
        return '#00000000'
    if value == 'white':
        return '#FFFFFFFF'
    if re.fullmatch(r'#[0-9a-fA-F]{3}', value):
        return '#FF' + ''.join(c * 2 for c in value[1:])
    if re.fullmatch(r'#[0-9a-fA-F]{6}', value):
        return '#FF' + value[1:]
    raise ValueError(f'Unsupported SVG color: {value}')


def vector(size, viewport):
    return ET.Element(
        'vector',
        {
            f'{{{ANDROID}}}width': f'{size}dp',
            f'{{{ANDROID}}}height': f'{size}dp',
            f'{{{ANDROID}}}viewportWidth': str(viewport),
            f'{{{ANDROID}}}viewportHeight': str(viewport),
        },
    )


def paths(parent, elements):
    attributes = {
        'd': 'pathData',
        'fill': 'fillColor',
        'fill-rule': 'fillType',
        'stroke': 'strokeColor',
        'stroke-width': 'strokeWidth',
        'stroke-linecap': 'strokeLineCap',
        'stroke-linejoin': 'strokeLineJoin',
    }
    # ponytail: handles our path-only artwork; reject new SVG features rather than silently dropping them.
    for source in elements:
        if (
            source.tag != f"{{{NS['s']}}}path"
            or source.attrib.keys() - attributes.keys()
        ):
            raise ValueError(
                'Android export supports only paths with fill/stroke attributes'
            )
        target = ET.SubElement(parent, 'path')
        target.set(f'{{{ANDROID}}}fillColor', '#FF000000')
        for key, value in source.attrib.items():
            if key in ('fill', 'stroke'):
                value = color(value)
            elif key == 'fill-rule':
                value = {'evenodd': 'evenOdd', 'nonzero': 'nonZero'}[value]
            target.set(f'{{{ANDROID}}}{attributes[key]}', value)


def android_vectors():
    round_icon = ET.parse(BRANDING / 'icon-round.svg').getroot()
    artwork = round_icon.find('s:g/s:g', NS)
    transform = re.fullmatch(
        r'translate\(([-\d.]+) ([-\d.]+)\) scale\(([-\d.]+)\)',
        artwork.attrib['transform'],
    )
    if transform is None:
        raise ValueError('Expected translate(x y) scale(s) on round icon artwork')
    tx, ty, scale = transform.groups()
    foreground = vector(108, 1254)
    group = ET.SubElement(
        foreground,
        'group',
        {
            f'{{{ANDROID}}}translateX': tx,
            f'{{{ANDROID}}}translateY': ty,
            f'{{{ANDROID}}}scaleX': scale,
            f'{{{ANDROID}}}scaleY': scale,
        },
    )
    paths(group, artwork)
    background = vector(108, 108)
    path = ET.SubElement(
        background, 'path', {f'{{{ANDROID}}}pathData': 'M0,0H108V108H0Z'}
    )
    attr = ET.SubElement(path, f'{{{AAPT}}}attr', {'name': 'android:fillColor'})
    gradient = ET.SubElement(
        attr,
        'gradient',
        {
            f'{{{ANDROID}}}type': 'linear',
            f'{{{ANDROID}}}startX': '0',
            f'{{{ANDROID}}}startY': '0',
            f'{{{ANDROID}}}endX': '108',
            f'{{{ANDROID}}}endY': '108',
        },
    )
    source_gradient = round_icon.find('s:defs/s:linearGradient', NS)
    if source_gradient.attrib != {
        'id': 'background',
        'gradientUnits': 'userSpaceOnUse',
        'x1': '0',
        'y1': '0',
        'x2': '1254',
        'y2': '1254',
    }:
        raise ValueError(
            'Android background expects the existing diagonal linear gradient'
        )
    for stop in source_gradient:
        ET.SubElement(
            gradient,
            'item',
            {
                f'{{{ANDROID}}}offset': stop.get('offset', '0'),
                f'{{{ANDROID}}}color': color(stop.attrib['stop-color']),
            },
        )
    status = ET.parse(BRANDING / 'status-icon.svg').getroot()
    if status.attrib['viewBox'] != '0 0 24 24':
        raise ValueError('Status icon must retain its 24×24 viewBox')
    notification = vector(24, 24)
    paths(notification, status)
    return {
        'ic_launcher_foreground.xml': foreground,
        'ic_launcher_background.xml': background,
        'ic_notification.xml': notification,
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
                        '-strip',
                        str(destination),
                    ],
                    check=True,
                )
            else:
                shutil.copyfile(raster, destination)

    for name in ('icon', 'icon-round', 'status-icon', 'feature'):
        render(f'{name}.svg', f'assets/branding/{name}.png')
    store = 'fastlane/metadata/android/en-US/images'
    render('icon.svg', f'{store}/icon.png', opaque=True)
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
