"""Check vector conversion and the full export without modifying repository assets."""

import os
import re
import runpy
import shutil
import struct
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = runpy.run_path(str(ROOT / 'scripts/generate-branding.py'))
ANDROID_VECTORS = (
    os.environ.get('MESSAGE487_TEST_ANDROID_VECTORS') == '1'
    or os.environ.get('MESSAGE487_TEST_ILLUSTRATOR') == '1'
)


class BrandingExportTest(unittest.TestCase):
    def test_cli_selects_existing_svg_illustrator_or_pdf(self):
        main = GENERATOR['main']
        for flag, selected in [(None, None), ('--illustrator', 'export_illustrator'),
                               ('--pdf', 'export_pdf')]:
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as temp:
                output = Path(temp)
                def export(source, destination):
                    for name in GENERATOR['ARTBOARDS']:
                        shutil.copyfile(ROOT / f'assets/branding/{name}.svg',
                                        destination / f'{name}.svg')
                def generate(destination, sources):
                    self.assertEqual(destination, output)
                    self.assertEqual({p.stem for p in sources.glob('*.svg')},
                                     set(GENERATOR['ARTBOARDS']))
                exporters = {name: mock.Mock(side_effect=export)
                             for name in ('export_pdf', 'export_illustrator')}
                with mock.patch.dict(main.__globals__, generate=generate, **exporters):
                    main(['--output-root', str(output)] + ([flag] if flag else []))
                for name, exporter in exporters.items():
                    self.assertEqual(exporter.call_count, int(name == selected))
                self.assertEqual(len(list(output.rglob('*.svg'))), 4 if selected else 0)
        with self.assertRaises(SystemExit), mock.patch('sys.stderr'):
            main(['--pdf', '--illustrator'])

    @unittest.skipUnless(shutil.which('pdftocairo') and shutil.which('pdfinfo'),
                         'Install Poppler to check real PDF export')
    def test_pdf_export_is_vector_only_repeatable_and_preserves_source(self):
        source = ROOT / 'assets/branding/branding.ai'
        before = source.read_bytes()
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            GENERATOR['export_pdf'](source, output)
            first = {p.name: p.read_bytes() for p in output.glob('*.svg')}
            self.assertEqual(set(first), {f'{n}.svg' for n in GENERATOR['ARTBOARDS']})
            for name, size in GENERATOR['ARTBOARDS'].items():
                root = ET.parse(output / f'{name}.svg').getroot()
                self.assertEqual(root.get('viewBox'), f'0 0 {size[0]} {size[1]}')
                self.assertEqual(root.get('width'), str(size[0]))
                self.assertIsNone(root.find('.//s:image', GENERATOR['NS']))
                self.assertTrue(list(GENERATOR['drawable_elements'](root)))
            GENERATOR['export_pdf'](source, output)
            self.assertEqual(first, {p.name: p.read_bytes() for p in output.glob('*.svg')})
            if ANDROID_VECTORS:
                GENERATOR['generate'](output, output)
        self.assertEqual(source.read_bytes(), before)

    @unittest.skipUnless(
        os.environ.get('MESSAGE487_TEST_ILLUSTRATOR') == '1',
        'Set MESSAGE487_TEST_ILLUSTRATOR=1 on a Mac with Illustrator',
    )
    def test_illustrator_exports_all_artboards_reproducibly_without_changing_source(
        self,
    ):
        source = ROOT / 'assets/branding/branding.ai'
        before = source.read_bytes()
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            GENERATOR['export_illustrator'](source, output)
            first = {p.name: p.read_bytes() for p in output.glob('*.svg')}
            self.assertEqual(
                set(first),
                {'icon.svg', 'icon-round.svg', 'status-icon.svg', 'feature.svg'},
            )
            for path in output.glob('*.svg'):
                self.assertTrue(
                    list(GENERATOR['drawable_elements'](ET.parse(path).getroot()))
                )
            GENERATOR['export_illustrator'](source, output)
            self.assertEqual(
                first, {p.name: p.read_bytes() for p in output.glob('*.svg')}
            )
            GENERATOR['generate'](output, output)
        self.assertEqual(before, source.read_bytes())

    @unittest.skipUnless(
        ANDROID_VECTORS,
        'Set MESSAGE487_TEST_ANDROID_VECTORS=1 with Android build tools',
    )
    def test_viewport_translation_preserves_fractional_path_coordinates(self):
        path = 'M113.43 106.12h256.15v90.57h-30Z'
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
          <path fill="#302f5e" d="M0 0h512v512H0z"/>
          <path fill="#fff" d="{path}"/></svg>'''
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / 'icon.svg').write_text(svg)
            shutil.copy(
                ROOT / 'assets/branding/status-icon.svg', source / 'status-icon.svg'
            )
            foreground = GENERATOR['android_vectors'](source)[
                'ic_launcher_foreground.xml'
            ]
        android = '{' + GENERATOR['ANDROID'] + '}'
        tokens = r'[a-zA-Z]|[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?'
        self.assertEqual(
            re.findall(tokens, next(foreground.iter('path')).get(android + 'pathData')),
            re.findall(tokens, path),
        )

    def test_layers_follow_the_current_svg_without_requiring_ids_or_transforms(self):
        ns = GENERATOR['NS']
        sources = GENERATOR['android_sources']()
        original = ET.parse(ROOT / 'assets/branding/icon.svg').getroot()
        shapes = list(GENERATOR['drawable_elements'](original))
        foreground = sources['ic_launcher_foreground']
        background = sources['ic_launcher_background']
        self.assertEqual(
            [p.attrib for p in GENERATOR['drawable_elements'](foreground)],
            [p.attrib for p in shapes[1:]],
        )
        self.assertEqual(
            [p.attrib for p in GENERATOR['drawable_elements'](background)],
            [shapes[0].attrib],
        )
        x, y, width, height = map(float, original.attrib['viewBox'].split())
        self.assertEqual(
            tuple(map(float, foreground.attrib['viewBox'].split())),
            (x - width / 4, y - height / 4, width * 1.5, height * 1.5),
        )
        self.assertEqual(foreground.get('width'), '108')
        self.assertEqual(sources['ic_notification'].get('viewBox'), '0 0 24 24')

    def test_nested_illustrator_groups_and_arbitrary_clip_ids(self):
        svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
          <defs><clipPath id="random-export-id"><rect width="512" height="512" fill="none"/></clipPath>
            <clipPath id="internal"><rect x="100" y="100" width="20" height="20"/></clipPath></defs>
          <g clip-path="url(#random-export-id)"><g><path id="random-background" fill="#302f5e" d="M-128-128h768v768H-128z"/>
            <g id="random-artwork"><path fill="#fff" d="M100 100h200v200H100z"/></g></g></g>
        </svg>'''
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / 'icon.svg').write_text(svg)
            shutil.copy(
                ROOT / 'assets/branding/status-icon.svg', source / 'status-icon.svg'
            )
            layers = GENERATOR['android_sources'](source)
        ns = GENERATOR['NS']
        layer = layers['ic_launcher_foreground']
        self.assertIsNotNone(layer.find('.//s:g[@id="random-artwork"]/s:path', ns))
        self.assertEqual(
            layer.find('.//s:g', ns).get('clip-path'), 'url(#random-export-id)'
        )
        clip = layer.find('.//s:clipPath[@id="random-export-id"]/s:rect', ns)
        self.assertEqual(clip.get('x'), '-128')
        self.assertEqual(clip.get('width'), '768')
        self.assertEqual(
            layer.find('.//s:clipPath[@id="internal"]/s:rect', ns).get('width'), '20'
        )

    def test_legacy_gradient_and_transformed_group_remain_supported(self):
        svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="209 209 836 836">
          <defs><linearGradient id="any-name"><stop stop-color="#00c9ef"/><stop offset="1" stop-color="#ff1ab9"/></linearGradient></defs>
          <path fill="url(#any-name)" d="M0 0h1254v1254H0z"/>
          <g transform="translate(169.29 169.29) scale(0.73)"><path fill="#fff" d="M300 300h600v600H300z"/></g>
        </svg>'''
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / 'icon.svg').write_text(svg)
            shutil.copy(
                ROOT / 'assets/branding/status-icon.svg', source / 'status-icon.svg'
            )
            layers = GENERATOR['android_sources'](source)
        ns = GENERATOR['NS']
        self.assertEqual(
            layers['ic_launcher_foreground'].get('viewBox'), '0 0 1254 1254'
        )
        self.assertEqual(
            layers['ic_launcher_foreground'].find('s:g', ns).get('transform'),
            'translate(169.29 169.29) scale(0.73)',
        )
        self.assertEqual(
            layers['ic_launcher_background'].find('s:path', ns).get('fill'),
            'url(#any-name)',
        )

    @unittest.skipUnless(
        ANDROID_VECTORS and shutil.which('rsvg-convert') and shutil.which('magick'),
        'Set MESSAGE487_TEST_ANDROID_VECTORS=1 with Android and PNG export tools',
    )
    def test_export_sizes_and_store_opacity(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            GENERATOR['generate'](output)
            pngs = list(output.rglob('*.png'))
            self.assertEqual(len(pngs), 16)
            for png in pngs:
                data = png.read_bytes()
                width, height = struct.unpack('>II', data[16:24])
                if 'mipmap-' in str(png):
                    density = png.parent.name.removeprefix('mipmap-')
                    self.assertEqual(
                        (width, height),
                        (
                            dict(mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192)[
                                density
                            ],
                        )
                        * 2,
                    )
                elif png.name in ('feature.png', 'featureGraphic.png'):
                    self.assertEqual((width, height), (1024, 500))
                elif png.name == 'status-icon.png':
                    self.assertEqual((width, height), (24, 24))
                else:
                    self.assertEqual((width, height), (512, 512))
                if 'fastlane' in str(png):
                    self.assertEqual(data[25], 2)  # PNG RGB, without an alpha channel.
            self.assertEqual(len(list(output.rglob('*.xml'))), 3)
            android = '{' + GENERATOR['ANDROID'] + '}'
            foreground = ET.parse(
                output / 'app/src/main/res/drawable/ic_launcher_foreground.xml'
            ).getroot()
            self.assertEqual(foreground.get(android + 'width'), '108dp')
            self.assertEqual(
                float(foreground.get(android + 'viewportWidth')),
                float(
                    ET.parse(ROOT / 'assets/branding/icon.svg')
                    .getroot()
                    .get('viewBox')
                    .split()[2]
                )
                * 1.5,
            )
            self.assertTrue(list(foreground.iter('path')))
            x, y, _, _ = map(
                float,
                GENERATOR['android_sources']()['ic_launcher_foreground']
                .get('viewBox')
                .split(),
            )
            self.assertEqual(float(foreground[0].get(android + 'translateX')), -x)
            self.assertEqual(float(foreground[0].get(android + 'translateY')), -y)

            first = {
                p.relative_to(output): p.read_bytes()
                for p in output.rglob('*')
                if p.is_file()
            }
            GENERATOR['generate'](output)
            self.assertEqual(
                first,
                {
                    p.relative_to(output): p.read_bytes()
                    for p in output.rglob('*')
                    if p.is_file()
                },
            )
