"""Build compact filename indexes, never download cover images. Run when updating the app's fallback snapshot."""
from pathlib import Path
import re,urllib.request,urllib.parse,json,gzip,unicodedata,hashlib,concurrent.futures,datetime
ROOT=Path(__file__).resolve().parents[1]
model=(ROOT/'app/src/main/java/tv/orbit/shield/OnlineCoverModel.kt').read_text()
systems=dict(re.findall(r'"([^"]+)" to "([^"]+)"',model[model.index('internal val libretroSystems'):]))
suffix=re.compile(r'\((?:USA|Europe|Eur|Japan|World|Russia|Rus|En|Es|Ru|Fr|De|It|Ja|Rev[ .0-9]+)(?:[, ]+(?:En|Es|Ru|Fr|De|It|Ja))*\)|\[T[+-][^]]*]',re.I)
def key(s):
 s=re.sub(r'\s+',' ',suffix.sub(' ',s).replace('_',' ')).strip()[:160]
 return ''.join(c for c in unicodedata.normalize('NFKD',s).lower() if unicodedata.category(c)[0] in ['L','N'])
out=ROOT/'app/src/main/assets/libretro-covers';out.mkdir(parents=True,exist_ok=True)
def index(item):
 platform,system=item;url='https://thumbnails.libretro.com/'+urllib.parse.quote(system,safe='')+'/Named_Boxarts/'
 b=urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'GOSTEX-Cover-Index-Builder'}),timeout=40).read(16*1024*1024+1)
 if len(b)>16*1024*1024:raise ValueError('Index too large')
 names=[]
 for href in re.findall('href="([^"]+)"',b.decode()):
  name=urllib.parse.unquote(href)
  if name.lower().endswith('.png') and 5<=len(name)<=240 and not any(c in name for c in '/\\') and not any(ord(c)<32 for c in name):names.append(name)
 entries={}
 for name in sorted(set(names)):entries.setdefault(key(name[:-4]),[]).append(name)
 entries={k:v[:16] for k,v in entries.items() if k}
 filename=hashlib.sha256(system.encode()).hexdigest()[:16]+'.idx'
 payload=json.dumps(entries,ensure_ascii=False,separators=(',',':')).encode()
 compressed=gzip.compress(payload,mtime=0);(out/filename).write_bytes(compressed)
 return platform,{'system':system,'file':filename,'source':url,'names':len(names),'keys':len(entries),'bytes':len(compressed),'sha256':hashlib.sha256(compressed).hexdigest()}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 results=dict(pool.map(index,systems.items()))
manifest={'version':1,'generatedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Filename index only. Image artwork remains on the source server. Not a snapshot of the images.','platforms':dict(sorted(results.items()))}
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'platforms':len(results),'names':sum(x['names'] for x in results.values()),'compressedBytes':sum(x['bytes'] for x in results.values())}))
