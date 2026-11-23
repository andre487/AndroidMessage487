"""Verify Android and store resources generated directly from the Inkscape master."""

import os
import pathlib
import runpy
import shutil
import struct
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

import branding

GENERATOR = runpy.run_path(str(SCRIPTS / 'generate-branding.py'))
ANDROID_VECTORS = os.environ.get('MESSAGE487_TEST_ANDROID_VECTORS') == '1'


class BrandingExportTest(unittest.TestCase):
    def setUp(self):
        self.master = ET.parse(branding.MASTER).getroot()

    def test_pages_keep_bounds_and_referenced_artwork(self):
        for name in branding.PAGES:
            with self.subTest(name=name):
                svg = branding.page(self.master, name)
                self.assertEqual(svg[-1].get(branding.INK + 'label'), name)
                self.assertEqual(
                    svg.get('viewBox').split()[2:],
                    [svg.get('width'), svg.get('height')],
                )
                ids = {e.get('id') for e in svg.iter()}
                for use in svg.iter(branding.SVG + 'use'):
                    target = use.get('{http://www.w3.org/1999/xlink}href')
                    self.assertIn(target.removeprefix('#'), ids)

    def test_android_selects_named_groups_and_preserves_status_page_bounds(self):
        layers = GENERATOR['android_sources'](self.master)
        for part in ('background', 'foreground'):
            root = layers['ic_launcher_' + part]
            self.assertEqual([g.get(branding.INK + 'label') for g in root[-1]], [part])
            self.assertEqual(root.get('width'), '108')
        self.assertEqual(
            layers['ic_launcher_foreground'].get('viewBox'), '-125 -125 750 750'
        )
        status = layers['ic_notification']
        self.assertEqual(status.get('width'), '24')
        self.assertEqual(
            status.get('viewBox'),
            branding.page(self.master, 'status-icon').get('viewBox'),
        )
        with self.assertRaisesRegex(ValueError, 'missing'):
            branding.page(self.master, 'icon', 'missing')

    @unittest.skipUnless(
        ANDROID_VECTORS,
        'Set MESSAGE487_TEST_ANDROID_VECTORS=1 with Android build tools',
    )
    def test_committed_vectors_match_master(self):
        resources = SCRIPTS.parent / 'app/src/main/res/drawable'
        for name, vector in GENERATOR['android_vectors'](self.master).items():
            ET.indent(vector, space='    ')
            self.assertEqual(
                ET.tostring(vector), ET.tostring(ET.parse(resources / name).getroot())
            )
            self.assertIsNotNone(vector.find('.//path'))

    @unittest.skipUnless(
        ANDROID_VECTORS and shutil.which('magick') and shutil.which('rsvg-convert'),
        'Enable Android vectors and install PNG tools',
    )
    def test_store_pngs_and_optional_page_exports(self):
        with tempfile.TemporaryDirectory() as temp:
            output = pathlib.Path(temp)
            GENERATOR['generate'](output)
            self.assertFalse((output / 'assets').exists())
            self.assertFalse(list((output / 'app').rglob('*.png')))
            pngs = {p.name: p.read_bytes() for p in output.rglob('*.png')}
            self.assertEqual(set(pngs), {'icon.png', 'featureGraphic.png'})
            for name, size in [
                ('icon.png', (512, 512)),
                ('featureGraphic.png', (1024, 500)),
            ]:
                self.assertEqual(struct.unpack('>II', pngs[name][16:24]), size)
                self.assertEqual(pngs[name][25], 2)
            GENERATOR['generate'](output, export_pages=True)
            self.assertEqual(
                {p.stem for p in (output / 'assets/branding').glob('*.svg')},
                set(branding.PAGES),
            )
            self.assertEqual(
                pngs, {p.name: p.read_bytes() for p in output.rglob('*.png')}
            )
