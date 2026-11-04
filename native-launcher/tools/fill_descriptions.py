"""Batch enrichment of missing curated entries, respecting Wikimedia Retry-After."""
import json,time,re,urllib.request,urllib.parse,urllib.error
from fetch_descriptions import ASSETS,ROOT,OVERRIDES,entry
OVERRIDES.update({'BioMetal':'BioMetal (video game)', 'Goof Troop':'Goof Troop (video game)'})
cache_dir=ROOT/'tools/wiki-cache';cache_dir.mkdir(exist_ok=True)
def batch(lang,titles):
 params=dict(action='query',prop='extracts|langlinks|pageprops|info',inprop='url',exintro=1,explaintext=1,exlimit=10,lllimit=500,redirects=1,titles='|'.join(titles),format='json',formatversion=2)
 req=urllib.request.Request(f'https://{lang}.wikipedia.org/w/api.php?'+urllib.parse.urlencode(params),headers={'User-Agent':'GOSTEXPersonalLauncher/0.8 (personal offline game descriptions)'})
 for attempt in range(3):
  try:
   with urllib.request.urlopen(req,timeout=25) as r: d=json.load(r)
   q=d['query'];aliases={i['from']:i['to'] for k in ['normalized','redirects'] for i in q.get(k,[])}
   pages={p['title']:p for p in q['pages']}
   result={}
   for t in titles:
    key=t
    for _ in range(4):key=aliases.get(key,key)
    if key in pages:result[t]=pages[key]
   time.sleep(3)
   return result
  except urllib.error.HTTPError as e:
   if e.code != 429 or attempt==2:raise
   delay=min(60,max(15,int(e.headers.get('Retry-After','60'))));print('Rate limit; waiting',delay,flush=True);time.sleep(delay)
def main():
 out=json.loads((ASSETS/'descriptions.json').read_text());report=json.loads((ROOT/'tools/description-report.json').read_text())
 games=json.loads((ASSETS/'catalog.json').read_text());pending={}
 for g in games:
  k=g['platform']+'|'+g['title'];title=OVERRIDES.get(g['title'],g['title'])
  if not g.get('demo') and k not in out and title:pending.setdefault(title,[]).append(g)
 english={};names=list(pending)
 for start in range(0,len(names),8):
  try:english.update(batch('en',names[start:start+8]))
  except Exception as e:print('Batch error',e,flush=True)
 translations={'ru':{},'es':{}}
 for title,p in english.items():
  item=entry(p,'en')
  if not item or not re.search(r'SNES|Super Nintendo|Super NES|Super Famicom',p.get('extract',''),re.I):continue
  for g in pending[title]:
   k=g['platform']+'|'+g['title'];out[k]={'en':item};report.pop(g['title'],None)
   for link in p.get('langlinks',[]):
    if link['lang'] in translations:translations[link['lang']].setdefault(link['title'],[]).append(k)
 (ASSETS/'descriptions.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
 print('English coverage',len(out),flush=True)
 for lang,matches in translations.items():
  titles=list(matches)
  for start in range(0,len(titles),8):
   try:
    result=batch(lang,titles[start:start+8])
    for title,p in result.items():
     item=entry(p,lang)
     if item:
      for k in matches[title]:out[k][lang]=item
    (ASSETS/'descriptions.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
    print(lang,start,len(result),flush=True)
   except Exception as e:print('Batch error',e,flush=True)
 (ROOT/'tools/description-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print('Coverage',len(out), 'locales',{l:sum(l in d for d in out.values()) for l in ['en','ru','es']},flush=True)
if __name__=='__main__':main()
