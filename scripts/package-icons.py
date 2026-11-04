from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'reference/runeicons'
OUTPUT = ROOT / 'app/src/main/res/drawable'
ICONS = {
    'folder': 'documents/folder.svg',
    'download': 'arrows/arrow-down-to-line.svg',
    'history': 'schedule/history.svg',
    'settings': 'tools/settings-2.svg',
    'search': 'tools/search.svg',
    'link': 'code/link.svg',
    'sort': 'tools/list-filter.svg',
    'grid': 'layouts/grid-2x2.svg',
    'menu': 'layouts/menu.svg',
    'play': 'playback/play.svg',
    'pause': 'playback/pause.svg',
    'close': 'indicators/x.svg',
    'check': 'indicators/check.svg',
    'plus': 'indicators/plus.svg',
    'info': 'indicators/info.svg',
    'share': 'tools/share-2.svg',
    'trash': 'tools/trash-2.svg',
    'refresh': 'schedule/refresh-cw.svg',
    'back': 'arrows/chevron-left.svg',
    'next': 'arrows/chevron-right.svg',
    'video': 'playback/video.svg',
    'volume': 'playback/volume-2.svg',
}
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
REFERENCE.mkdir(exist_ok=True)
OUTPUT.mkdir(parents=True, exist_ok=True)
for name, path in ICONS.items():
    source = REFERENCE / (name + '.svg')
    if not source.exists():
        url = 'https://raw.githubusercontent.com/nexvyn/runeicons/main/public/normal/' + path
        source.write_bytes(urlopen(url, timeout=20).read())
    svg = ET.fromstring(source.read_bytes())
    _, _, width, height = svg.attrib['viewBox'].split()
    vector = ET.Element('vector', {
        f'{{{ANDROID}}}width': '24dp', f'{{{ANDROID}}}height': '24dp',
        f'{{{ANDROID}}}viewportWidth': width, f'{{{ANDROID}}}viewportHeight': height,
    })
    for shape in svg:
        tag = shape.tag.rsplit('}', 1)[-1]
        if tag != 'path':
            raise ValueError(f'{path} has unsupported {tag}')
        attributes = {f'{{{ANDROID}}}pathData': shape.attrib['d']}
        attributes[f'{{{ANDROID}}}fillColor'] = '#00000000' if shape.attrib.get('fill', svg.attrib.get('fill')) == 'none' else '#FF000000'
        if shape.attrib.get('stroke'):
            attributes[f'{{{ANDROID}}}strokeColor'] = '#FF000000'
            attributes[f'{{{ANDROID}}}strokeWidth'] = shape.attrib.get('stroke-width', '1')
            attributes[f'{{{ANDROID}}}strokeLineCap'] = shape.attrib.get('stroke-linecap', 'butt')
            attributes[f'{{{ANDROID}}}strokeLineJoin'] = shape.attrib.get('stroke-linejoin', 'miter')
        ET.SubElement(vector, 'path', attributes)
    ET.ElementTree(vector).write(OUTPUT / ('rune_' + name + '.xml'), encoding='utf-8', xml_declaration=True)
license_path = REFERENCE / 'LICENSE'
if not license_path.exists():
    license_path.write_bytes(urlopen('https://raw.githubusercontent.com/nexvyn/runeicons/main/LICENSE', timeout=20).read())
print(f'Packaged {len(ICONS)} Rune icons with original path data and stroke widths.')
