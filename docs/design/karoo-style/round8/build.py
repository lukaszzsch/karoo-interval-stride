"""Inline the Karoo frame, font, device screenshots and karoo-device.js into preview.html.

Run: python3 build.py  (from this folder)
"""
import base64, pathlib
here = pathlib.Path(__file__).parent
assets = here.parents[4] / 'design-assets'          # Karoo/design-assets
def data_url(p, mime='image/png'): return f'data:{mime};base64,' + base64.b64encode(p.read_bytes()).decode()
html = (here / 'preview.src.html').read_text()
html = html.replace('/*__KAROO_DEVICE_JS__*/', (assets / 'karoo-device.js').read_text())
html = html.replace('__FRAME__', data_url(assets / 'karoo-3-frame.png'))
html = html.replace('__FONT_COND__', data_url(assets / 'fonts' / 'IBMPlexSansCondensed-Medium.otf', 'font/otf'))
for key in ['light', 'dark']:
    html = html.replace(f'__SHOT_{key}__', data_url(here / f'base-{key}.png'))
(here / 'preview.html').write_text(html)
print('wrote', here / 'preview.html')
