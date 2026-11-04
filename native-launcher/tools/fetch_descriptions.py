"""Fetch curated, platform-specific Wikipedia intros with attribution and language links.
Run manually on the development machine; generated assets also work offline on Shield.
No fuzzy matching: missing/ambiguous articles are reported, not attached to another game.
"""
import json, re, time, urllib.request, urllib.parse, urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
OVERRIDES={
'Ace wo Nerae!':None,'Adult Manga 1':None,'Adult Manga 2':None,
'Addams Family, The':'The Addams Family (video game)',
'Aladdin':"Disney's Aladdin (SNES video game)",
'Alien 3':'Alien 3 (video game)', 'Alien vs Predator':'Alien vs. Predator (1993 video game)',
'Arcana':'Arcana (video game)', 'BioMetal':'BioMetal (video game)', 'Goof Troop':'Goof Troop (video game)',
'Battletoads & Double Dragon - The Ultimate Team':'Battletoads & Double Dragon',
'Bishoujo Senshi Sailor Moon - Another Story':'Sailor Moon: Another Story',
'Clock Tower':'Clock Tower (1995 video game)',
'Dr. Mario':None,
'FF6 Rus v1.0.8 Logo WH':'Final Fantasy VI','Final Fantasy II':'Final Fantasy IV','Final Fantasy III':'Final Fantasy VI',
'Flashback - The Quest for Identity':'Flashback (1992 video game)',
'Fushigi no Dungeon 2 - Fuurai no Shiren':'Mystery Dungeon: Shiren the Wanderer',
'Go Go Ackman':None, 'Hameln no Violin Hiki':None,
'Harvest Moon':'Harvest Moon (video game)', 'Home Alone':'Home Alone (video game)',
'Home Alone 2 - Lost in New York':'Home Alone 2: Lost in New York (video game)',
'Hook':'Hook (video game)',
'Legend of Zelda, The - A Link to the Past':'The Legend of Zelda: A Link to the Past',
'Legend of Zelda - Link to the Past':'The Legend of Zelda: A Link to the Past',
'Lethal Weapon':'Lethal Weapon (video game)', 'Lion King, The':'The Lion King (video game)',
'Phantom 2040':'Phantom 2040 (video game)',
'Radical Dreamers - Nusumenai Houseki':'Radical Dreamers',
'Shadow, The':None, 'Slayers':None, 'SpellCraft - Aspects of Valor':None,
'Shin Nekketsu Kouha - Kunio-tachi no Banka':'Shin Nekketsu Kōha: Kunio-tachi no Banka',
'Shodai Nekketsu Kouha Kunio-kun':'Shodai Nekketsu Kōha Kunio-kun',
'Star Fox':'Star Fox (1993 video game)', 'Star Ocean':'Star Ocean (video game)',
'Timecop':'Timecop (video game)', 'Timeslip':'Timeslip (video game)',
'Venom - Spider-Man - Separation Anxiety':'Venom/Spider-Man: Separation Anxiety',
'Wolfenstein 3-D':'Wolfenstein 3D',
'Wonder Project J - Kikai no Shonen Pino':'Wonder Project J',
'Worms':'Worms (1995 video game)',
'Pilotwings':'Pilotwings (video game)', 'Mega Man X':'Mega Man X (video game)',
'Ninja Gaiden Trilogy':None,'Shodai Nekketsu Kouha Kunio-kun':None,
'Lost Vikings 2':'The Lost Vikings 2'}
_last_request = 0.0
def get(lang,title):
 global _last_request
 time.sleep(max(0.0, 2.1 - (time.monotonic() - _last_request)))
 _last_request = time.monotonic()
 params=urllib.parse.urlencode(dict(action='query',prop='extracts|langlinks|pageprops|info',inprop='url',exintro=1,explaintext=1,lllimit=500,redirects=1,titles=title,format='json'))
 req=urllib.request.Request(f'https://{lang}.wikipedia.org/w/api.php?{params}',headers={'User-Agent':'GOSTEXPersonalLauncher/0.8 (offline personal game library)'})
 for attempt in range(3):
  try:
   with urllib.request.urlopen(req,timeout=20) as r: data=json.load(r)
   return next(iter(data['query']['pages'].values()))
  except urllib.error.HTTPError as error:
   if error.code != 429 or attempt == 2: raise
   time.sleep(min(60, max(10, int(error.headers.get('Retry-After', '60')))))
  except Exception:
   if attempt==2:raise
   time.sleep(5+attempt)
def entry(page,lang):
 if 'missing' in page or 'disambiguation' in page.get('pageprops',{}):return None
 paragraphs=[p.strip() for p in page.get('extract','').split('\n') if p.strip()]
 text='\n\n'.join(paragraphs[:2])
 if not text:return None
 return dict(text=text,title=page['title'],url=page.get('fullurl',f'https://{lang}.wikipedia.org/wiki/'+urllib.parse.quote(page['title'].replace(' ','_'))),license='CC BY-SA 4.0',licenseUrl='https://creativecommons.org/licenses/by-sa/4.0/',authors='Wikipedia contributors',language=lang,modified=False)
def main():
 output=json.loads((ASSETS/'descriptions.json').read_text()) if (ASSETS/'descriptions.json').exists() else {};report={};cache={}
 for game in json.loads((ASSETS/'catalog.json').read_text()):
  if game.get('demo'):continue
  title=game['title'];pageTitle=OVERRIDES.get(title,title)
  if game['platform']+'|'+title in output: continue
  if pageTitle is None:report[title]='No unambiguous game article selected';continue
  try:
   if pageTitle not in cache:cache[pageTitle]=get('en',pageTitle)
   en=cache[pageTitle];item=entry(en,'en')
   # Require evidence for the requested console, not merely a matching media title.
   if not item or not re.search(r'SNES|Super Nintendo|Super NES|Super Famicom', en.get('extract',''),re.I):
    report[title]='No SNES evidence in intro';continue
   locales={'en':item}
   for link in en.get('langlinks',[]):
    if link['lang'] in ('ru','es'):
     key=link['lang']+':'+link['*']
     if key not in cache:cache[key]=get(link['lang'],link['*'])
     translated=entry(cache[key],link['lang'])
     if translated:locales[link['lang']]=translated
   output[game['platform']+'|'+title]=locales
   (ASSETS/'descriptions.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
   print(title,','.join(locales),flush=True)
  except Exception as e:report[title]=str(e)
  time.sleep(2.1)
 (ASSETS/'descriptions.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
 (ROOT/'tools/description-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print('COVERAGE',len(output),'MISSING',len(report),flush=True)
if __name__=='__main__':main()
