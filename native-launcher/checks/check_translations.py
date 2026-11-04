"""Check translation coverage and format arguments without an Android device."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

res = Path(__file__).resolve().parents[1] / 'app/src/main/res'
def read(folder):
    entries = {}
    for path in sorted((res / folder).glob('*.xml')):
        for node in ET.parse(path).getroot():
            if node.tag not in ('string', 'plurals'):
                continue
            name = node.attrib['name']
            assert name not in entries, (folder, name, 'duplicate translation key')
            entries[name] = node
    return entries
base = read('values')
for folder in ('values-en', 'values-es', 'values-ru'):
    translated = read(folder)
    assert set(translated) == set(base), (folder, set(base) ^ set(translated))
    for key, source in base.items():
        target = translated[key]
        if source.tag == 'string':
            args = lambda text: sorted(re.findall(r'%\d+\$[ds]', text or ''))
            assert args(source.text) == args(target.text), (folder, key)
            assert target.text and target.text.strip(), (folder, key)
        else:
            quantities = {item.attrib['quantity'] for item in target}
            assert 'other' in quantities
            if folder == 'values-ru': assert {'one','few','many','other'} <= quantities
            for item in target: assert '%d' in item.text
    print(folder, len(translated), 'entries: coverage and placeholders OK')
