"""Apply reviewed game/edition-specific summaries after the optional Wikipedia importer.
No network, ROM reading or title guessing. Missing games remain missing.
Run: python3 native-launcher/tools/apply_curated_metadata.py
Then run checks/check_game_metadata.py and checks/check_metadata_matcher.py.
"""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets'
file=ASSETS/'descriptions.json';data=json.loads(file.read_text());curated=json.loads((ROOT/'tools/curated-descriptions.json').read_text())
for key,locales in curated.items():data.setdefault(key,{}).update(locales)
file.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
print('Applied',sum(map(len,curated.values())),'reviewed locale entries;',len(data),'game descriptions')
