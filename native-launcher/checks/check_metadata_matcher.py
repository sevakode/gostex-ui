"""Compile production pure matcher code and exercise identity/edition collision cases."""
import hashlib,json,subprocess,tempfile
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]; SOURCE=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/GameMetadataMatcher.kt'; ASSETS=ROOT/'native-launcher/app/src/main/assets'; OUT=ROOT/'.reports/metadata-coverage';OUT.mkdir(exist_ok=True)
games=json.loads((ASSETS/'catalog.json').read_text()); aliases=json.loads((ASSETS/'metadata-aliases.json').read_text());production=SOURCE.read_text(); pure=production[production.index('internal data class MetadataAlias'):]
main=(ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/MainActivity.kt').read_text(); game=main[main.index('data class Game('):main.index('\n)\n', main.index('data class Game('))+3]
def q(s):return json.dumps(s,ensure_ascii=False).replace('$','\\$')
rows=[]
for g in games:
 rows.append('Game('+','.join(q(g[x]) for x in ['id','title','platform','emulator','component'])+',listOf('+','.join(q(f) for f in g['files'])+'),'+','.join(q(g[x]) for x in ['tree','cover','scene','genre','year','developer','players'])+')')
ali=['MetadataAlias('+','.join(q(a[k]) for k in ['platform','alias','title'])+')' for a in aliases]
checks='''
var checked=0
fun verify(name:String, condition:Boolean) {check(condition) {name}; checked++; println("PASS|"+name)}
for (g in games) {
 verify("Exact title "+g.title, index.find(g.platform,g.title)?.id==g.id)
 for (f in g.files) verify("Known file "+f.substringAfterLast('/'),index.find(g.platform,f.substringAfterLast('/'))?.id==g.id)
}
for(a in aliases) verify("Alias "+a.alias,index.find(a.platform,a.alias+" (Japan).sfc")?.title==a.title)
verify("New region+translation metadata", index.find("SNES","Aladdin (USA) [T+Rus1.2 Example (2026)].sfc")?.title=="Aladdin")
verify("Revision/languages", index.find("SNES","Chrono_Trigger (Europe) (En,Fr) (Rev 1).SMC")?.title=="Chrono Trigger")
verify("No cross-platform match",index.find("NES","Aladdin.nes")==null)
verify("Final Fantasy IV SNES alias",index.find("SNES","Final Fantasy IV (Japan).sfc")?.title=="Final Fantasy II")
verify("Final Fantasy IV DS not SNES",index.find("Nintendo DS","Final Fantasy IV.nds")==null)
val portrait=index.find("Nintendo DS","Castlevania - Portrait of Ruin (Eur) (Rus).nds")
verify("Observed DS translation filename has base-game cover",portrait?.title=="Castlevania - Portrait of Ruin" && portrait.cover.isNotBlank())
verify("DS metadata cannot replace import identity or invent a ROM",portrait?.files?.isEmpty()==true && portrait.tree.isEmpty())
verify("DS original Europe edition has same base-game cover",index.find("Nintendo DS","Castlevania - Portrait of Ruin (Europe) (En,Fr,De,Es,It).nds")?.id==portrait?.id)
verify("DS title cannot match another platform",index.find("SNES","Castlevania - Portrait of Ruin (Eur) (Rus).nds")==null)
verify("DS unknown translation label not stripped",index.find("Nintendo DS","Castlevania - Portrait of Ruin (Eur) (OtherTranslation).nds")==null)
for(tag in listOf("Hack","Beta","Prototype","Demo")) verify("DS edition retained "+tag,index.find("Nintendo DS","Castlevania - Portrait of Ruin (Eur) (Rus) ("+tag+").nds")==null)
verify("DS sequel remains distinct",index.find("Nintendo DS","Castlevania - Portrait of Ruin 2 (Eur) (Rus).nds")==null)
verify("Do not erase sequel", index.find("SNES","Aladdin 2.sfc")==null)
for(tag in listOf("Hack","Beta","Prototype","Demo","PD","Different Subtitle")) verify("Preserve edition "+tag,index.find("SNES","Aladdin (USA) ("+tag+").sfc")==null)
verify("Unknown brackets retained",index.find("SNES","Aladdin [random-hack].smc")==null)
verify("Missing and empty",index.find("SNES","")==null && index.find("SNES","Unknown Adventure.zip")==null)
val collision=MetadataIndex(listOf(games.first().copy(id="one",title="Collision-Game"),games.first().copy(id="two",title="Collision Game")),emptyList())
verify("Ambiguous punctuation does not choose first",collision.find("SNES","Collision_Game (USA).sfc")==null)
for (ext in listOf("rar","exe","gcz","ciso","wia","dol")) verify("Supported extension "+ext, metadataTitleKey("Example."+ext)=="example")
verify("Slash in title preserved",metadataTitleKey("Venom/Spider-Man: Separation Anxiety") == "venomspidermanseparationanxiety")
println("COUNT|"+checked)
'''
code='import java.text.Normalizer\nimport java.util.Locale\n'+game+pure+'\nfun main(){\nval games=listOf('+',\n'.join(rows)+')\nval aliases=listOf('+',\n'.join(ali)+')\nval index=MetadataIndex(games,aliases)\n'+checks+'\n}\n';test=OUT/'MetadataMatcherCheck.kt';test.write_text(code)
cache=ROOT/'.tools/native/gradle-cache/caches/modules-2/files-2.1'
def jar(g,a,v):return next((cache/g/a/v).glob('*/*.jar'))
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.21'),jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.4.21'),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),jar('org.jetbrains','annotations','13.0')]
java=ROOT/'.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java';report={'timestamp':datetime.now(timezone.utc).isoformat(),'sourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'testSha256':hashlib.sha256(test.read_bytes()).hexdigest(),'scope':'Production pure MetadataIndex/normalization with real bundled title/file/alias corpus; does not prove identity of unknown ROM bytes or Android UI.'}
with tempfile.TemporaryDirectory(prefix='gostex-metadata-check-') as folder:
 command=[str(java),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(compiler[1]),'-d',folder,str(test)]
 r=subprocess.run(command,capture_output=True,text=True);report['compile']={'code':r.returncode,'stderr':r.stderr};report['passed']=False
 if not r.returncode:
  r=subprocess.run([str(java),'-cp',folder+':'+str(compiler[1]),'MetadataMatcherCheckKt'],capture_output=True,text=True);report['run']={'code':r.returncode,'stdout':r.stdout,'stderr':r.stderr};report['passed']=r.returncode==0
(OUT/'matcher-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'passed':report['passed'],'checks':report.get('run',{}).get('stdout','').count('PASS|'),'errors':report.get('run',report['compile']).get('stderr','')}));raise SystemExit(0 if report['passed'] else 1)
