"""Validate offline description/media provenance and report honest current coverage."""
import hashlib,json
from datetime import datetime,timezone
from pathlib import Path
from urllib.parse import urlparse
ROOT=Path(__file__).resolve().parents[2]; ASSETS=ROOT/'native-launcher/app/src/main/assets'
games=json.loads((ASSETS/'catalog.json').read_text());real=[g for g in games if not g.get('demo')];descriptions=json.loads((ASSETS/'descriptions.json').read_text());media=json.loads((ASSETS/'media-sources.json').read_text());aliases=json.loads((ASSETS/'metadata-aliases.json').read_text());keys={g['platform']+'|'+g['title'] for g in games}
for key,locales in descriptions.items():
 assert key in keys,key
 for language,d in locales.items():
  assert language in ('en','es','ru') and d['language']==language,(key,language)
  assert d['text'].strip() and d['authors'].strip(),key
  url=urlparse(d['url']);assert url.scheme=='https',key
  if d.get('sourceName','Wikipedia')=='Wikipedia':
   assert url.hostname.endswith('.wikipedia.org'),key
   assert d['license']=='CC BY-SA 4.0' and d['licenseUrl']=='https://creativecommons.org/licenses/by-sa/4.0/',key
  else:
   assert d['sourceName']=='GameFAQs' and url.hostname=='gamefaqs.gamespot.com',key
   assert not d['license'] and not d['licenseUrl'],key
  if d.get('modified'):assert d.get('adaptation') and d.get('sourceLanguage'),key
for path,item in media.items():
 data=(ASSETS/path).read_bytes();assert hashlib.sha256(data).hexdigest()==item['sha256'],path
 assert hashlib.sha1(('blob '+str(len(data))+'\0').encode()+data).hexdigest()==item['gitBlobSha'],path
 assert item['url'].startswith('https://github.com/libretro-thumbnails/') and '/blob/' in item['url'],path
 assert item['copyright'] and item['kind'],path
for game in real:
 for key in ['cover','scene']:
  if game[key]:assert game[key] in media,(game['title'],key)
for alias in aliases:
 assert alias['platform']+'|'+alias['title'] in keys,alias
 assert alias['source'].startswith('https://'),alias
report={'updatedAtUtc':datetime.now(timezone.utc).isoformat(),'scope':'Offline metadata for existing real library titles; demos excluded. Source validation verifies attribution structure and actual media bytes, not unknown ROM identities.','before':{'games':70,'descriptions':53,'locales':{'en':53,'ru':36,'es':35},'artwork':61},'after':{'games':len(real),'descriptions':sum(g['platform']+'|'+g['title'] in descriptions for g in real),'locales':{l:sum(l in descriptions.get(g['platform']+'|'+g['title'],{}) for g in real) for l in ['en','ru','es']},'artwork':sum(bool(g['cover']) for g in real),'attributedMediaFiles':len(media),'aliases':len(aliases)},'missingDescriptions':[g['title'] for g in real if g['platform']+'|'+g['title'] not in descriptions],'missingArtwork':[g['title'] for g in real if not g['cover']],'limitations':['Adult Manga 1/2 remain unidentified and keep honest fallback, without guessed franchise descriptions or pictures.','Catalog enrichment is offline and bounded to verified entries; arbitrary new PSP/N64/Dreamcast or other games do not gain invented metadata.','Descriptions are edition-specific summaries. Changes introduced by individual fan translations/hacks are not claimed to match the base game.','Radical Dreamers card image is its title screen, not a retail box scan.','Commercial game art is not licensed under Wikipedia CC BY-SA. Source repository and exact bytes are recorded separately.'],'checks':'Description language/source/license/adaptation fields, aliases target existing platform keys, every existing real-game image has verified SHA256/Git blob and pinned source URL.'}
out=ROOT/'.reports/metadata-coverage/coverage.json';out.parent.mkdir(exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report['after'],ensure_ascii=False));print('Missing descriptions/artwork:',report['missingDescriptions']);print('PASS',out)
