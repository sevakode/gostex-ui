"""Compile the production bounded 3DS reader; exercise format, language and malformed-file cases."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / 'native-launcher/app/src/main/java/tv/orbit/shield/ThreeDsMetadata.kt'
cache = root / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10')]]
compiler += [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
java = root / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
checks = r'''package tv.orbit.shield
import java.util.Locale
fun ByteArray.put32(offset:Int, value:Long) { for(i in 0..3) this[offset+i]=(value shr (8*i)).toByte() }
fun ByteArray.putMagic(offset:Int, value:String) {value.toByteArray(Charsets.US_ASCII).copyInto(this,offset)}
fun ByteArray.title(slot:Int, value:String, publisher:String="Test Studio", long:Boolean=false) {
 val offset=8+slot*512+if(long) 128 else 0
 value.toByteArray(Charsets.UTF_16LE).copyInto(this,offset)
 publisher.toByteArray(Charsets.UTF_16LE).copyInto(this,8+slot*512+384)
}
fun smdh():ByteArray = ByteArray(0x36c0).apply {
 putMagic(0,"SMDH"); title(1,"English Game"); title(5,"Juego español"); title(10,"Русская игра")
}
fun ncch(icon:ByteArray=smdh(), exponent:Int=0):ByteArray {
 val unit=512 shl exponent
 val length=((unit+512+icon.size+unit-1)/unit)*unit
 return ByteArray(length).apply {
  putMagic(0x100,"NCCH"); put32(0x104,(length/unit).toLong())
  put32(0x118,0x001bd200); put32(0x11c,0x00040000)
  this[0x18d]=3; this[0x18e]=exponent.toByte(); this[0x18f]=4
  put32(0x1a0,1); put32(0x1a4,(length/unit-1).toLong())
  putMagic(unit,"icon"); put32(unit+8,0); put32(unit+12,icon.size.toLong())
  icon.copyInto(this,unit+512)
 }
}
fun cartridge(main:ByteArray=ncch()):ByteArray = ByteArray(0x400+main.size).apply {
 putMagic(0x100,"NCSD"); put32(0x104,0xffffffffL)
 put32(0x120,2); put32(0x124,(main.size/512).toLong()); main.copyInto(this,0x400)
}
private fun parsed(bytes:ByteArray, language:String="en")=ThreeDsMetadata.read(bytes.size.toLong(),language) { offset,length ->
 check(offset>=0 && offset<=bytes.size && length<=bytes.size-offset) {"out of bounds request"}
 bytes.copyOfRange(offset.toInt(),offset.toInt()+length)
}
fun main() {
 var count=0
 fun pass(name:String, good:Boolean) { check(good) {name}; count++; println("PASS|$name") }
 pass("NCCH title, publisher and little-endian program ID",parsed(ncch())==ThreeDsGameMetadata("English Game","Test Studio","00040000001bd200"))
 pass("Spanish locale selects Spanish title",parsed(ncch(),"es-AR")?.title=="Juego español")
 pass("Russian locale selects Russian title",parsed(ncch(),"ru_RU")?.title=="Русская игра")
 pass("Unknown locale falls back to English",parsed(ncch(),"xx")?.title=="English Game")
 val missingLanguage=smdh().apply {fill(0,8+10*512,8+11*512)}
 pass("Missing requested title falls back to English",parsed(ncch(missingLanguage),"ru")?.title=="English Game")
 val onlyJapanese=ByteArray(0x36c0).apply {putMagic(0,"SMDH"); title(0,"日本語ゲーム")}
 pass("Remaining native language used if English absent",parsed(ncch(onlyJapanese),"es")?.title=="日本語ゲーム")
 val longOnly=ByteArray(0x36c0).apply {putMagic(0,"SMDH"); title(1,"Long\nGame Title",long=true)}
 pass("Long title fallback collapses line breaks",parsed(ncch(longOnly))?.title=="Long Game Title")
 pass("Standalone SMDH uses identical localized metadata",ThreeDsMetadata.readSmdh(smdh(),"ru")?.title=="Русская игра")
 pass("Data-only NCCH never becomes a game",parsed(ncch().apply {this[0x18d]=1})==null)
 pass("Encrypted NCCH rejected even if plaintext-looking icon exists",parsed(ncch().apply {this[0x18f]=0})==null)
 pass("NCSD main executable metadata",parsed(cartridge())?.title=="English Game")
 pass("Trimmed NCSD does not require unused card capacity",parsed(cartridge())!=null)
 val dataMain=cartridge(ncch().apply {this[0x18d]=1}).apply {put32(0x128,2); put32(0x12c,(size-0x400L)/512)}
 pass("NCSD does not substitute another partition for non-game main",parsed(dataMain)==null)
 pass("Content-unit exponent respected",parsed(ncch(exponent=1))?.title=="English Game")
 pass("NCCH signature required",parsed(ncch().apply {this[0x100]=0})==null)
 pass("Missing icon rejected",parsed(ncch().apply {this[512]=0})==null)
 pass("Icon section name must terminate",parsed(ncch().apply {this[516]=1})==null)
 pass("SMDH signature required",parsed(ncch(smdh().apply {this[0]=0}))==null)
 pass("Truncated SMDH rejected",ThreeDsMetadata.readSmdh(smdh().copyOf(0x36bf),"en")==null)
 pass("Empty title records rejected",parsed(ncch(ByteArray(0x36c0).apply{putMagic(0,"SMDH")}))==null)
 val malformedTitle=smdh().apply {fill(0,8+512,8+512+128);this[8+512+1]=0xd8.toByte()}
 pass("Malformed UTF-16 never becomes replacement-character title",parsed(ncch(malformedTitle))?.title=="Juego español")
 val controlTitle=smdh().apply {this[8+512]=1;this[8+512+1]=0}
 pass("Control characters reject the corrupt title slot",parsed(ncch(controlTitle))?.title=="Juego español")
 pass("Zero program ID stays absent",parsed(ncch().apply {fill(0,0x118,0x120)})?.titleId==null)
 pass("Truncated header rejected without callback",ThreeDsMetadata.read(100,"en") {_,_->error("unbounded read") }==null)
 pass("Negative source length rejected",ThreeDsMetadata.read(-1,"en") {_,_->error("unbounded read") }==null)
 pass("Provider short read rejected",ThreeDsMetadata.read(100000,"en") {_,_->ByteArray(511)}==null)
 pass("Excessive content size rejected",parsed(ncch().apply {put32(0x104,0xffffffffL)})==null)
 pass("Exponent overflow rejected",parsed(ncch().apply {this[0x18e]=0xff.toByte()})==null)
 pass("Out-of-file NCSD partition rejected",parsed(cartridge().apply {put32(0x120,0xffffffffL)})==null)
 pass("Zero-length NCSD partition rejected",parsed(cartridge().apply {put32(0x124,0)})==null)
 pass("Partition inside header rejected",parsed(cartridge().apply {put32(0x120,0)})==null)
 pass("Out-of-content ExeFS rejected",parsed(ncch().apply {put32(0x1a0,0xffffffffL)})==null)
 pass("Oversized ExeFS rejected",parsed(ncch().apply {put32(0x1a4,0xffffffffL)})==null)
 pass("Out-of-ExeFS icon rejected",parsed(ncch().apply {put32(512+8,0xffffffffL)})==null)
 pass("Oversized icon rejected without allocating its claimed length",parsed(ncch().apply {put32(512+12,0xffffffffL)})==null)
 pass("Short icon section rejected",parsed(ncch().apply {put32(512+12,0x36bf)})==null)
 var reads=0; var total=0; val cart=cartridge()
 val bounded=ThreeDsMetadata.read(Long.MAX_VALUE,"en") {offset,length ->
  reads++;total+=length;check(length<=0x36c0)
  cart.copyOfRange(offset.toInt(),offset.toInt()+length)
 }
 pass("Huge declared source still reads at most 15552 bytes",bounded!=null && reads==4 && total==15552)
 var encryptedReads=0
 val encrypted=ncch().apply{this[0x18f]=0}
 ThreeDsMetadata.read(encrypted.size.toLong(),"en") {o,n->encryptedReads++;encrypted.copyOfRange(o.toInt(),o.toInt()+n)}
 pass("Encrypted content not probed beyond its header",encryptedReads==1)
 var storageError=false
 try {ThreeDsMetadata.read(100000,"en") {_,_->throw java.io.IOException("gone")}}catch(_:java.io.IOException){storageError=true}
 pass("Storage exceptions propagate for cached-library retention",storageError)
 var cancelled=false
 try {ThreeDsMetadata.read(100000,"en") {_,_->throw java.util.concurrent.CancellationException()}}catch(_:java.util.concurrent.CancellationException){cancelled=true}
 pass("Cancellation propagates",cancelled)
 val random=java.util.Random(149)
 repeat(500) {
  val input=if(it%2==0) ncch() else cartridge()
  repeat(8){input[random.nextInt(512)]=random.nextInt(256).toByte()}
  var requests=0;var bytesRead=0
  ThreeDsMetadata.read(input.size.toLong(),"ru") {offset,length ->
   requests++;bytesRead+=length
   check(offset>=0 && offset<=input.size && length<=input.size-offset)
   check(requests<=4 && bytesRead<=15552)
   input.copyOfRange(offset.toInt(),offset.toInt()+length)
  }
 }
 pass("Mutated headers remain within source and fixed read budget",true)
 val previous=Locale.getDefault();Locale.setDefault(Locale.forLanguageTag("tr-TR"))
 try {pass("Host locale cannot alter SMDH language lookup",parsed(ncch(),"RU")?.title=="Русская игра")}finally{Locale.setDefault(previous)}
 // ACTUAL_FIXTURE
 println("TOTAL|$count")
}
'''
fixture = root / '.reports/3ds34/apollo-sparse.json'
if fixture.exists():
    data = json.loads(fixture.read_text())
    segments = ','.join(f'{s["offset"]}L to "{s["hex"]}".chunked(2).map{{it.toInt(16).toByte()}}.toByteArray()' for s in data['segments'])
    actual = f'''val segments=mapOf({segments})
 var actualBytes=0
 val actual=ThreeDsMetadata.read({data['size']}L,"ru") {{offset,length ->
  actualBytes+=length
  segments.entries.firstOrNull {{offset>=it.key && offset-it.key+length<=it.value.size}}?.let {{
   it.value.copyOfRange((offset-it.key).toInt(),(offset-it.key).toInt()+length)
  }}
 }}
 pass("Actual installed Apollo sparse metadata has Russian title and stable ID",actual?.title=="Судебный поворот 4" && actual.titleId=="00040000001bd200")
 pass("Actual 414 MB title reads only 15040 metadata bytes",actualBytes==15040)
'''
    checks = checks.replace('// ACTUAL_FIXTURE', actual)

report = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'scope': __doc__,
          'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
          'actual_sparse_fixture': str(fixture) if fixture.exists() else None}
with tempfile.TemporaryDirectory(prefix='gostex-3ds-metadata-') as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    out = folder / 'classes'
    command = [str(java), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
               '-no-stdlib', '-no-reflect', '-classpath', str(compiler[1]), '-d', str(out), str(source), str(folder / 'Checks.kt')]
    compiled = subprocess.run(command, text=True, capture_output=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    report['passed'] = False
    if compiled.returncode == 0:
        run = subprocess.run([str(java), '-cp', str(out) + ':' + str(compiler[1]), 'tv.orbit.shield.ChecksKt'], text=True, capture_output=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0
path = root / '.reports/three-ds-metadata-checks.json'
path.parent.mkdir(exist_ok=True)
path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(path)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
