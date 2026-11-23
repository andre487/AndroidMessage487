"""Read named pages and launcher layers from the Inkscape branding document."""

import copy
import math
import pathlib
import xml.etree.ElementTree as ET

MASTER = pathlib.Path(__file__).resolve().parents[1] / 'assets/branding.svg'
SVG = '{http://www.w3.org/2000/svg}'
INK = '{http://www.inkscape.org/namespaces/inkscape}'
PAGES = ('icon', 'icon-round', 'status-icon', 'feature')

ET.register_namespace('', SVG[1:-1])
ET.register_namespace('xlink', 'http://www.w3.org/1999/xlink')
ET.register_namespace('inkscape', INK[1:-1])


def page(document, name, part=None):
    pages = [p for p in document.iter(INK + 'page') if p.get(INK + 'label') == name]
    layers = [
        g for g in document.findall(SVG + 'g') if g.get(INK + 'groupmode') == 'layer'
    ]
    matches = [g for g in layers if g.get(INK + 'label') == name]
    if len(pages) != 1 or len(matches) != 1:
        raise ValueError(f'Expected one page and one top-level layer named {name}')
    bounds = [pages[0].attrib[k] for k in ('x', 'y', 'width', 'height')]
    x, y, w, h = map(float, bounds)
    if not all(map(math.isfinite, (x, y, w, h))) or min(w, h) <= 0:
        raise ValueError(f'Invalid page bounds for {name}')
    root = ET.Element(
        SVG + 'svg', width=bounds[2], height=bounds[3], viewBox=' '.join(bounds)
    )
    defs = document.find(SVG + 'defs')
    defs = copy.deepcopy(defs) if defs is not None else ET.Element(SVG + 'defs')
    root.append(defs)
    layer = copy.deepcopy(matches[0])
    if part:
        parts = [g for g in layer if g.get(INK + 'label') == part]
        if len(parts) != 1:
            raise ValueError(f'Expected one {part} group in the {name} layer')
        layer[:] = parts
    root.append(layer)
    # The banner and round icon clone artwork from the icon layer on another page.
    # Keep those definitions reachable without painting the other pages.
    if next(layer.iter(SVG + 'use'), None) is not None:
        defs.extend(copy.deepcopy(other) for other in layers if other is not matches[0])
    return root
