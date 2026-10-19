"""Run with python3 -m unittest discover -s scripts/tests -p test_preview_samsung_icon.py."""

import runpy
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


class SamsungPreviewTest(unittest.TestCase):
    def test_background_defaults_to_white_and_can_be_disabled(self):
        root = Path(__file__).resolve().parents[2]
        script = runpy.run_path(str(root / 'scripts/preview-samsung-icon.py'))
        source = root / 'assets/branding/icon.svg'
        for effects in (False, True):
            opaque = ET.fromstring(script['preview_svg'](source, effects=effects))
            transparent = ET.fromstring(
                script['preview_svg'](source, effects=effects, transparent=True)
            )
            background = opaque[0]
            self.assertEqual(background.tag, '{http://www.w3.org/2000/svg}rect')
            self.assertEqual(background.get('fill'), 'white')
            opaque.remove(background)
            self.assertEqual(ET.tostring(opaque), ET.tostring(transparent))

    def test_mask_preserves_adaptive_artwork(self):
        root = Path(__file__).resolve().parents[2]
        script = runpy.run_path(str(root / 'scripts/preview-samsung-icon.py'))
        svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
          <defs><clipPath id="circle"><circle cx="256" cy="256" r="256"/></clipPath></defs>
          <g clip-path="url(#circle)"><path fill="blue" d="M0 0h512v512H0z"/></g>
        </svg>'''
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'round.svg'
            path.write_text(svg)
            preview = ET.fromstring(script['preview_svg'](path, effects=False))
        source = ET.fromstring(svg)
        ns = {'s': 'http://www.w3.org/2000/svg'}
        self.assertEqual(preview.attrib, source.attrib)
        self.assertEqual(
            ET.tostring(preview.find('s:g', ns)),
            ET.tostring(source.find('s:g', ns)),
        )
        clip = preview.find('.//s:clipPath', ns)
        self.assertEqual(clip.attrib['id'], source.find('.//s:clipPath', ns).get('id'))
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

    def test_illustrator_css_clip_is_replaced_without_losing_internal_clip(self):
        root = Path(__file__).resolve().parents[2]
        script = runpy.run_path(str(root / 'scripts/preview-samsung-icon.py'))
        source = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
          <defs><style>.cls-7 {clip-path: url(#clippath);}</style>
            <clipPath id="internal"><circle cx="20" cy="20" r="10"/></clipPath>
            <clipPath id="clippath"><circle cx="256" cy="256" r="256"/></clipPath>
          </defs><g class="cls-7"><path fill="blue" d="M0 0H512V512H0Z"/></g>
        </svg>'''
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'Artboard 1.svg'
            path.write_text(source)
            preview = ET.fromstring(script['preview_svg'](path, effects=False))
        ns = {'s': 'http://www.w3.org/2000/svg'}
        self.assertIsNotNone(preview.find('.//s:clipPath[@id="clippath"]/s:path', ns))
        self.assertIsNotNone(preview.find('.//s:clipPath[@id="internal"]/s:circle', ns))
        self.assertEqual(len(preview.findall('.//s:clipPath', ns)), 2)
        self.assertEqual(preview.find('s:g', ns).get('class'), 'cls-7')
        self.assertIn('url(#clippath)', preview.find('.//s:style', ns).text)
