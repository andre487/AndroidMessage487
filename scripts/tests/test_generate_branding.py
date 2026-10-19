"""Check vector conversion and the full export without modifying repository assets."""

import runpy
import shutil
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = runpy.run_path(str(ROOT / 'scripts/generate-branding.py'))


class BrandingExportTest(unittest.TestCase):
    def test_android_paths_and_transform_follow_separate_sources(self):
        ns = GENERATOR['NS']
        android = GENERATOR['ANDROID']
        vectors = GENERATOR['android_vectors']()
        source = ET.parse(ROOT / 'assets/branding/icon-round.svg').find('s:g/s:g', ns)
        group = vectors['ic_launcher_foreground.xml'].find('group')
        self.assertEqual(group.get(f'{{{android}}}scaleX'), '0.65')
        self.assertEqual(group.get(f'{{{android}}}translateX'), '219.45')
        self.assertEqual(
            [p.get(f'{{{android}}}pathData') for p in group],
            [p.get('d') for p in source],
        )
        status = ET.parse(ROOT / 'assets/branding/status-icon.svg').getroot()
        self.assertEqual(
            vectors['ic_notification.xml'][0].get(f'{{{android}}}pathData'),
            status[0].get('d'),
        )
        with self.assertRaises(ValueError):
            GENERATOR['paths'](ET.Element('group'), [ET.Element('rect')])

    @unittest.skipUnless(
        shutil.which('rsvg-convert') and shutil.which('magick'), 'Export tools required'
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
