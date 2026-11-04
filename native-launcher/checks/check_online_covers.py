"""Execute production provider configuration, artwork matching and API response parsing on the JVM."""
from pathlib import Path
import ast,json,subprocess,tempfile,hashlib
ROOT=Path(__file__).resolve().parents[2]
original=(ROOT/'native-launcher/checks/check_managed_covers.py').read_text()
namespace={"__file__":__file__,"Path":Path}
exec(original[original.index('ROOT='):original.index('source=ROOT')],namespace)
compiler,libs,JAVA=namespace['compiler'],namespace['libs'],namespace['JAVA']
cp=':'.join(map(str,libs))
node=ast.parse(original)
boundary=next(ast.literal_eval(n.value) for n in node.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='boundary' for t in n.targets))
boundary=boundary.replace(' fun has(key:String)', ''' fun optJSONArray(key:String):JSONArray?=(values[key] as? List<*>)?.let { JSONArray(it) }
 fun optBoolean(key:String)=(values[key] as? Boolean) ?: false
 fun optInt(key:String)=(values[key] as? Number)?.toInt() ?: 0
 fun optLong(key:String)=(values[key] as? Number)?.toLong() ?: 0L
 fun optJSONObject(key:String):JSONObject? { @Suppress("UNCHECKED_CAST") val map=values[key] as? Map<String,Any?>;return map?.let { JSONObject(it) } }
 fun has(key:String)''')
boundary=boundary.replace(' fun length()=items.size',' fun getString(index:Int)=items[index] as String\n fun length()=items.size')
checks=r'''package tv.orbit.shield
import groovy.json.JsonOutput
fun main(args:Array<String>) {
 var n=0
 fun test(name:String,body:()->Unit) { body();n++;println("PASS|$name") }
 fun response(data:List<Map<String,Any?>>)=JsonOutput.toJson(mapOf("success" to true,"data" to data))
 test("Provider preference keeps user order, disables explicitly and tolerates unknown values") {
  check(coverProviders(null)==CoverProvider.entries.toList());check(coverProviders("bad")==CoverProvider.entries.toList())
  check(coverProviders("libretro,steamgriddb,libretro")==listOf(CoverProvider.LIBRETRO,CoverProvider.STEAMGRIDDB));check(coverProviders("off").isEmpty())
 }
 test("API key validation rejects injected or malformed values and permits removal") {
  check(coverApiKeyValid(""));check(coverApiKeyValid("f".repeat(32)))
  for(v in listOf("short","f".repeat(31),"f".repeat(32)+"\n","Bearer "+"f".repeat(32))) check(!coverApiKeyValid(v))
 }
 test("Search cleanup retains prototypes, hacks and subtitles while removing known file metadata") {
  check(coverSearchTitle("Castlevania - Portrait of Ruin (Eur) (Rus).nds")=="Castlevania - Portrait of Ruin")
  check(coverSearchTitle("Game (Beta).nds")=="Game (Beta)");check(coverSearchTitle("Game (Hack).zip")=="Game (Hack)")
  check(coverSearchTitle("Mystery P.I. - Portrait of a Thief")=="Mystery P.I. - Portrait of a Thief")
 }
 test("Image URLs stay HTTPS on documented CDN hosts; no credentials or arbitrary destinations") {
  check(coverImageUrl("https://cdn2.steamgriddb.com/file/sgdb-cdn/grid/x.png"));check(coverImageUrl("https://thumbnails.libretro.com/a.png"))
  for(v in listOf("http://cdn2.steamgriddb.com/a.png","https://localhost/a.png","https://cdn2.steamgriddb.com.evil/a.png","https://u:p@cdn2.steamgriddb.com/a.png","file:///a.png","https://cdn2.steamgriddb.com:444/a.png")) check(!coverImageUrl(v))
 }
 test("Game API preserves separate editions and bounds displayed matches") {
  val result=OnlineCoverParser.games(response((1..30).map { mapOf("id" to it,"name" to "Game $it","release_date" to 1163980800L) }))
  check(result.size==12 && result.first().year=="2006" && result.last().id==12)
  check(OnlineCoverParser.games(response(listOf(mapOf("id" to -1,"name" to "bad")))).isEmpty())
 }
 fun image(id:Int=1,w:Int=920,h:Int=430)=mapOf<String,Any?>("id" to id,"width" to w,"height" to h,"mime" to "image/png", "url" to "https://cdn2.steamgriddb.com/grid/$id.png", "thumb" to "https://cdn2.steamgriddb.com/thumb/$id.jpg", "author" to mapOf("name" to "Artist"))
 test("Image API ranks landscape, retains provenance and filters unwanted or unsupported results") {
  val rows=listOf(image(1,600,900),image(2),image(3)+mapOf("mime" to "image/gif"),image(4)+mapOf("nsfw" to true),image(5)+mapOf("url" to "https://localhost/x.png"),image(6)+mapOf("humor" to true),image(7)+mapOf("epilepsy" to true))
  val result=OnlineCoverParser.images(response(rows),"Game")
  check(result.map { it.url }==listOf(image(2)["url"],image(1)["url"]))
  check(result.first().author=="Artist" && result.first().sourceUrl=="https://www.steamgriddb.com/grid/2")
  check(OnlineCoverParser.images(response((1..100).map { image(it) }),"Game").size==16)
 }
 test("Libretro filename parsing rejects paths and preserves literal plus signs") {
  check(OnlineCoverParser.libretroName("Game%20%2B%20Game%20(USA).png")=="Game + Game (USA).png")
  for(v in listOf("../a.png","%2Fsecret.png","a%5Cb.png","a.jpg","bad%00.png")) check(OnlineCoverParser.libretroName(v)==null)
 }
 test("Fallback matching is exact and does not substitute a sequel, beta or different subtitle") {
  val names=listOf("Game (USA).png","Game 2 (USA).png","Game (Beta).png","Game - Other (USA).png")
  check(OnlineCoverParser.libretroMatches(names,"Game")==listOf("Game (USA).png"))
 }
 test("Live NDS directory yields both real Castlevania regional covers") {
  val html=java.io.File(args[0]).readText()
  val names=Regex("href=\"([^\"]+)\"").findAll(html).mapNotNull { OnlineCoverParser.libretroName(it.groupValues[1]) }.toList()
  val result=OnlineCoverParser.libretroMatches(names,"Castlevania - Portrait of Ruin")
  check(result.size==2 && result.any { "Europe" in it } && result.any { "USA" in it })
 }
 test("Compact packaged fallback resolves live regional names without a directory scan") {
  val bytes=java.util.zip.GZIPInputStream(java.io.File(args[1]).inputStream()).use { it.readBytes() }
  val rows=org.json.JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray(coverTitleKey(coverSearchTitle("Castlevania - Portrait of Ruin")))!!
  check(rows.length()==2 && (0 until rows.length()).any { "Europe" in rows.getString(it) })
 }
 test("Saved attribution round trips and legacy covers keep working") {
  val entry=ManagedCover(manual=managedCoverKey("a".repeat(64)),sourceName="SteamGridDB",sourceUrl="https://www.steamgriddb.com/grid/1",author="Artist")
  check(ManagedCoverManifest.decodeIndex(ManagedCoverManifest.encodeIndex(mapOf("id" to entry)))["id"]==entry)
  check(ManagedCoverManifest.decodeIndex("""{"version":1,"games":{"id":{"manual":"","wide":"","original":""}}}""")["id"]==ManagedCover())
 }
 println("TOTAL|$n")
}
'''
sources=[ROOT/'native-launcher/app/src/main/java/tv/orbit/shield'/s for s in ['OnlineCoverModel.kt','ManagedCoverModel.kt']]
report={'scope':__doc__,'sourceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'notCovered':['Authenticated SteamGridDB request without owner API key','Android lifecycle, SAF and UI integration (device report)']}
with tempfile.TemporaryDirectory(prefix='gostex-online-covers-') as temp:
 p=Path(temp)
 for src in sources:(p/src.name).write_text(src.read_text())
 (p/'Json.kt').write_text(boundary);(p/'Checks.kt').write_text(checks)
 build=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(p/'classes'),*[str(f) for f in p.glob('*.kt')]],capture_output=True,text=True)
 report['compile']={'exit':build.returncode,'stderr':build.stderr}
 if build.returncode==0:
  run=subprocess.run([str(JAVA),'-cp',str(p/'classes')+':'+cp,'tv.orbit.shield.ChecksKt',str(ROOT/'.reports/covers31/libretro-nds-index.html'),str(ROOT/'native-launcher/app/src/main/assets/libretro-covers'/json.loads((ROOT/'native-launcher/app/src/main/assets/libretro-covers/manifest.json').read_text())['platforms']['Nintendo DS']['file'])],capture_output=True,text=True)
  report['run']={'exit':run.returncode,'stdout':run.stdout,'stderr':run.stderr};report['passed']=run.returncode==0 and 'TOTAL|11' in run.stdout
 else:report['passed']=False
out=ROOT/'.reports/covers31/model-checks.json';out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));raise SystemExit(0 if report['passed'] else 1)
