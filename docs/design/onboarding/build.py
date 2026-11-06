"""Inline the Karoo frame, fonts, Karoo screenshots and karoo-device.js into preview.html.

Preview pages are opened as static snapshots that can't load files beside them, so everything is
embedded. Run: python3 build.py  (from this folder)
"""
import base64, pathlib
here = pathlib.Path(__file__).parent
assets = here.parents[3] / 'design-assets'          # Karoo/design-assets
def data_url(p, mime='image/png'): return f'data:{mime};base64,' + base64.b64encode(p.read_bytes()).decode()
html = (here / 'preview.src.html').read_text()
html = html.replace('/*__KAROO_DEVICE_JS__*/', (assets / 'karoo-device.js').read_text())
html = html.replace('__FRAME__', data_url(assets / 'karoo-3-frame.png'))
fonts = assets / 'fonts'
for key, name, mime in [('REG', 'IBMPlexSans-Regular.ttf', 'font/ttf'), ('MED', 'IBMPlexSans-Medium.ttf', 'font/ttf'),
                        ('BOLD', 'IBMPlexSans-SemiBold.ttf', 'font/ttf')]:
    html = html.replace(f'__FONT_{key}__', data_url(fonts / name, mime))
for key in ['settings', 'pages', 'picker-interval', 'watts-plain', 'watts-text', 'watts-background', 'watts-both', 'watts-gauge']:
    html = html.replace(f'__SHOT_{key}__', data_url(here / 'base' / f'{key}.png'))
(here / 'preview.html').write_text(html)
print('wrote', here / 'preview.html')
