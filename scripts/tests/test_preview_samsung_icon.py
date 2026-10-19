"""Run with python3 -m unittest discover -s scripts/tests -p test_preview_samsung_icon.py."""

import runpy
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


class SamsungPreviewTest(unittest.TestCase):
    def test_mask_preserves_adaptive_artwork(self):
        root = Path(__file__).resolve().parents[2]
        script = runpy.run_path(str(root / 'scripts/preview-samsung-icon.py'))
        preview = ET.fromstring(
            script['preview_svg'](
                root / 'assets/branding/icon-round.svg', effects=False
            )
        )
        source = ET.parse(root / 'assets/branding/icon-round.svg').getroot()
        ns = {'s': 'http://www.w3.org/2000/svg'}
        self.assertEqual(preview.attrib, source.attrib)
        self.assertEqual(
            ET.tostring(preview.find('s:g', ns)),
            ET.tostring(source.find('s:g', ns)),
        )
        clip = preview.find('.//s:clipPath', ns)
        self.assertEqual(clip.attrib['id'], 'circle')
        self.assertEqual(len(clip), 1)
        self.assertTrue(clip[0].tag.endswith('}path'))

    def test_square_source_gets_mask_without_changing_artwork(self):
        root = Path(__file__).resolve().parents[2]
        script = runpy.run_path(str(root / 'scripts/preview-samsung-icon.py'))
        source = ET.parse(root / 'assets/branding/icon.svg').getroot()
        preview = ET.fromstring(
            script['preview_svg'](root / 'assets/branding/icon.svg', effects=False)
        )
        ns = {'s': 'http://www.w3.org/2000/svg'}
        group = preview.find('s:g', ns)
        self.assertEqual(group.attrib['clip-path'], 'url(#samsung-preview-mask)')
        original = [child for child in source if child.tag != '{' + ns['s'] + '}defs']
        self.assertEqual(
            [ET.tostring(c) for c in group], [ET.tostring(c) for c in original]
        )

    def test_effects_generate_valid_svg_with_adjustable_bevel(self):
        root = Path(__file__).resolve().parents[2]
        script = runpy.run_path(str(root / 'scripts/preview-samsung-icon.py'))
        preview = ET.fromstring(
            script['preview_svg'](root / 'assets/branding/icon-round.svg', bevel=0.3)
        )
        ns = {'s': 'http://www.w3.org/2000/svg'}
        effect = preview.find('.//s:filter', ns)
        self.assertEqual(effect.attrib['id'], 'samsung-preview-effects')
        highlight = effect.find('s:feFlood[@flood-color="white"]', ns)
        self.assertEqual(highlight.attrib['flood-opacity'], '0.3')
        self.assertEqual(
            preview.find('s:g', ns).attrib['filter'], 'url(#samsung-preview-effects)'
        )
