"""Compile and exercise production scoped disc parsing/grouping and native path mapping.
Does not claim Android grant enforcement, BIOS availability or emulator gameplay.
"""
from pathlib import Path
import hashlib, json, subprocess, tempfile
from datetime import datetime, timezone
root = Path(__file__).resolve().parents[2]
source_dir = root / 'native-launcher/app/src/main/java/tv/orbit/shield'
sources = [source_dir / name for name in ('DiscImport.kt', 'DuckStationPath.kt')]
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
fun main() {
 var count = 0
 fun pass(name: String, value: Boolean) { check(value) { name }; println("PASS|$name"); count++ }
 fun cue(file: String = "track.bin", mode: String = "MODE2/2352") = "FILE \"$file\" BINARY\n  TRACK 01 $mode\n    INDEX 01 00:00:00\n"
 fun scan(vararg pairs: Pair<String,String>): DiscImport.Result {
  val content = pairs.toMap()
  return DiscImport.inspect(pairs.map { DiscImport.File(it.first, it.first) }, { content.getValue(it.path) })
 }
 fun rejected(text: String, reason: DiscImport.Reason) = scan("bad.cue" to text, "track.bin" to "", "good.chd" to "").let {
  it.entries.map { f -> f.path } == listOf("good.chd") && it.problems.single().reason == reason
 }
 val basic = scan("a.cue" to cue(), "track.bin" to "", "loose.bin" to "")
 pass("CUE is one entry and tracks never become game cards", basic.entries.map{it.path} == listOf("a.cue") && basic.problems.isEmpty())
 pass("case insensitive descriptor extension and command tokens", scan("GAME.CUE" to cue("Track.BIN").lowercase().replace("track.bin", "Track.BIN"), "Track.BIN" to "").entries.size == 1)
 pass("quoted CUE filename with spaces", scan("Game.cue" to cue("Track 01.bin"), "Track 01.bin" to "").problems.isEmpty())
 pass("CUE nested track directory", scan("Game/game.cue" to cue("tracks/t.bin"), "Game/tracks/t.bin" to "").problems.isEmpty())
 pass("relative parent inside selected root", scan("Game/game.cue" to cue("../Shared/t.bin"), "Shared/t.bin" to "").problems.isEmpty())
 pass("case mismatch is reported instead of pretending native opens it", scan("g.cue" to cue("TRACK.bin"), "track.bin" to "").problems.single().reason == DiscImport.Reason.CASE_MISMATCH)
 pass("missing track rejects only its CUE", rejected(cue("missing.bin"), DiscImport.Reason.MISSING))
 pass("CUE without TRACK is rejected", rejected("FILE track.bin BINARY", DiscImport.Reason.MALFORMED))
 pass("CUE without index 01 is rejected", rejected("FILE track.bin BINARY\nTRACK 01 MODE2/2352\nINDEX 00 00:00:00", DiscImport.Reason.MALFORMED))
 pass("invalid quote is rejected", rejected("FILE \"track.bin BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00", DiscImport.Reason.MALFORMED))
 pass("unsupported command is rejected", rejected(cue()+"NOT_A_CUE_COMMAND foo", DiscImport.Reason.MALFORMED))
 pass("invalid MSF is rejected", rejected(cue().replace("00:00:00", "00:60:00"), DiscImport.Reason.MALFORMED))
 pass("duplicate index is rejected", rejected(cue()+"INDEX 01 00:00:10", DiscImport.Reason.MALFORMED))
 pass("index reversal is rejected", rejected(cue().replace("INDEX 01 00:00:00", "INDEX 00 00:01:00\nINDEX 01 00:00:00"), DiscImport.Reason.MALFORMED))
 pass("duplicate track number is rejected", rejected(cue()+"TRACK 01 AUDIO\nINDEX 01 01:00:00", DiscImport.Reason.MALFORMED))
 pass("CUE track numbers must start at one and remain contiguous", rejected(cue().replace("TRACK 01", "TRACK 02"), DiscImport.Reason.MALFORMED) && rejected(cue()+"TRACK 03 AUDIO\nINDEX 01 01:00:00", DiscImport.Reason.MALFORMED))
 pass("track positions in the same file cannot reverse", rejected(cue().replace("00:00:00", "02:00:00")+"TRACK 02 AUDIO\nINDEX 01 01:00:00", DiscImport.Reason.MALFORMED))
 pass("repeating FILE does not bypass same-file track ordering", rejected(cue().replace("00:00:00", "02:00:00")+"FILE track.bin BINARY\nTRACK 02 AUDIO\nINDEX 01 01:00:00", DiscImport.Reason.MALFORMED))
 pass("multiple tracks in one BIN are one CUE", scan("g.cue" to cue()+"TRACK 02 AUDIO\nINDEX 01 01:00:00", "track.bin" to "").problems.isEmpty())
 pass("BIN and WAVE files are explicit dependencies", scan("g.cue" to cue()+"FILE \"audio.wav\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00", "track.bin" to "", "audio.wav" to "").problems.isEmpty())
 pass("multiple BIN tracks remain one game", scan("g.cue" to cue()+"FILE \"track2.bin\" BINARY\nTRACK 02 AUDIO\nINDEX 01 00:00:00", "track.bin" to "", "track2.bin" to "").entries.size == 1)
 pass("CUE BINARY tracks may have raw/dat or no extension", listOf("data.raw", "data.dat", "data").all { scan("g.cue" to cue(it), it to "").problems.isEmpty() })
 pass("quoted CUE filenames preserve leading and trailing spaces", scan("g.cue" to cue(" track.bin "), " track.bin " to "").problems.isEmpty())
 pass("REM separated by a tab ignores arbitrary unmatched quotes", scan("g.cue" to "REM\tcomment \"unclosed\n"+cue(), "track.bin" to "").problems.isEmpty())
 pass("ignored CUE metadata is not tokenized", scan("g.cue" to "TITLE \"unclosed\n"+cue(), "track.bin" to "").problems.isEmpty())
 pass("bad CUE cannot consume an independent container game", listOf("chd", "pbp", "exe").all { ext -> scan("g.cue" to cue("other.$ext"), "other.$ext" to "").let { it.entries.single().path=="other.$ext" && it.problems.single().reason==DiscImport.Reason.UNSUPPORTED } })
 pass("CUE cannot refer to another descriptor", scan("g.cue" to cue("other.cue"), "other.cue" to cue(), "track.bin" to "").problems.single().reason == DiscImport.Reason.UNSUPPORTED)
 pass("CUE UTF-8 BOM is not falsely accepted by native format", rejected("\uFEFF"+cue(), DiscImport.Reason.MALFORMED))
 pass("NUL is rejected", rejected(cue()+"\u0000", DiscImport.Reason.MALFORMED))
 pass("oversized descriptor is rejected", rejected(" ".repeat(DiscImport.MAX_DESCRIPTOR_BYTES+1), DiscImport.Reason.TOO_LARGE))
 val playlist = scan("Set.m3u" to "#EXTM3U\nDisc 1/game.cue\nDisc 2.chd\n", "Disc 1/game.cue" to cue(), "Disc 1/track.bin" to "", "Disc 2.chd" to "", "Unrelated.chd" to "")
 pass("explicit M3U collapses its discs only", playlist.entries.map{it.path} == listOf("Set.m3u", "Unrelated.chd") && playlist.problems.isEmpty())
 pass("filename similarity does not group discs", scan("Game (Disc 1).chd" to "", "Game (Disc 2).chd" to "").entries.size == 2)
 pass("bad playlist does not hide usable discs", scan("Set.m3u" to "Disc1.chd\nmissing.chd", "Disc1.chd" to "").entries.map{it.path} == listOf("Disc1.chd"))
 pass("quoted M3U paths rejected because DuckStation reads literal quotes", scan("Set.m3u" to "\"Disc 1.chd\"", "Disc 1.chd" to "").problems.single().reason == DiscImport.Reason.MALFORMED)
 pass("M3U may contain unquoted spaces and CRLF", scan("Set.M3U" to "# hi\r\nDisc 1.chd\r\n", "Disc 1.chd" to "").entries.single().path == "Set.M3U")
 pass("PS-EXE is launchable but not a disc inside M3U", scan("Set.m3u" to "game.exe", "game.exe" to "").let{it.entries.single().path=="game.exe" && it.problems.single().reason==DiscImport.Reason.UNSUPPORTED})
 pass("nested M3U is explicitly reported", scan("A.m3u" to "B.m3u", "B.m3u" to "game.chd", "game.chd" to "").let{it.entries.single().path=="B.m3u" && it.problems.single().reason==DiscImport.Reason.NESTED_PLAYLIST})
 pass("M3U self cycle rejected", scan("A.m3u" to "A.m3u").problems.single().reason==DiscImport.Reason.CYCLE)
 pass("M3U mutual cycle rejected", scan("A.m3u" to "B.m3u", "B.m3u" to "A.m3u").let{it.entries.isEmpty() && it.problems.all{p->p.reason==DiscImport.Reason.CYCLE}})
 val deepChain=(0..16).map { n -> "%02d.m3u".format(n) to if(n==16) "valid.chd" else "%02d.m3u".format(n+1) } + ("valid.chd" to "")
 val deep=scan(*deepChain.toTypedArray())
 pass("invalid deep parent cannot poison a valid independent leaf playlist",deep.entries.map{it.path}==listOf("16.m3u") && deep.problems.none{it.path=="16.m3u"})
 pass("playlist count bounded", scan("A.m3u" to List(65){"game.chd"}.joinToString("\n"), "game.chd" to "").problems.single().reason==DiscImport.Reason.TOO_LARGE)
 pass("empty playlist rejected", scan("A.m3u" to "# just comments\n").problems.single().reason==DiscImport.Reason.MALFORMED)
 pass("duplicate exact provider paths are ambiguous", scan("g.cue" to cue(), "track.bin" to "", "track.bin" to "").problems.single().reason==DiscImport.Reason.AMBIGUOUS)
 pass("case-distinct exact paths remain resolvable", scan("g.cue" to cue(), "track.bin" to "", "TRACK.bin" to "").problems.isEmpty())
 val unsafe = listOf("../track.bin", "/track.bin", "C:/track.bin", "content://provider/file", "https://example.com/file", "..\\track.bin", "a/../../track.bin")
 pass("absolute URI drive and root escaping references rejected", unsafe.all{rejected(cue(it),DiscImport.Reason.OUTSIDE_ROOT)})
 pass("percent signs are literal file names not decoded traversal", scan("g.cue" to cue("%2e%2e.bin"), "%2e%2e.bin" to "").problems.isEmpty())
 pass("path normalization remains rooted", DiscImport.resolveRelative("A/B/g.cue", "../C/./t.bin")=="A/C/t.bin")
 val before=scan("own.cue" to cue(), "track.bin" to "")
 val other=scan("other.cue" to cue())
 pass("separate roots never borrow siblings or suppress each other", before.entries.single().path=="own.cue" && other.entries.isEmpty() && other.problems.single().reason==DiscImport.Reason.MISSING && scan("own.cue" to cue(),"track.bin" to "")==before)
 var providerRead=false
 val providerResult=DiscImport.inspect(listOf("a.chd", "b.pbp", "c.exe", "d.cue", "e.m3u").map { DiscImport.File(it,it) }, {providerRead=true; ""}, providerSupported=false)
 pass("opaque providers reject single images and descriptors without reads", providerResult.entries.isEmpty() && providerResult.problems.size==5 && providerResult.problems.all {it.reason==DiscImport.Reason.UNSUPPORTED} && !providerRead)
 var ioPropagated=false
 try { DiscImport.inspect(listOf(DiscImport.File("g.cue","id")), {throw java.io.IOException("USB unavailable")}) } catch (_:java.io.IOException){ioPropagated=true}
 pass("storage failure propagates for cached-root retention",ioPropagated)
 var cancelled=false
 try { DiscImport.inspect(listOf(DiscImport.File("g.cue","id")), {cue()}, {throw java.util.concurrent.CancellationException()}) } catch (_:java.util.concurrent.CancellationException){cancelled=true}
 pass("scan cancellation is never swallowed as descriptor warning",cancelled)
 val authority="com.android.externalstorage.documents"
 pass("native boot path preserves relative siblings and filename spaces", DuckStationPath.externalDocument(authority,"primary:ROMs","primary:ROMs/Disc 1/game.cue")=="/android_content/$authority/tree@primary/ROMs/document@primary/ROMs/Disc 1/game.cue")
 pass("USB volume and Unicode path preserved", DuckStationPath.externalDocument(authority,"ABCD-1234:Игры","ABCD-1234:Игры/Test.cue").endsWith("/document@ABCD-1234/Игры/Test.cue"))
 pass("selected volume root supported", DuckStationPath.externalDocument(authority,"ABCD-1234:","ABCD-1234:ROMs/Test.cue").contains("/tree@ABCD-1234/document@ABCD-1234/ROMs/Test.cue"))
 var outside=false
 try{DuckStationPath.externalDocument(authority,"primary:ROMs","primary:ROMs-other/Test.cue")}catch(_:IllegalArgumentException){outside=true}
 pass("native path cannot broaden selected root through prefix collision",outside)
 val original=Locale.getDefault(); Locale.setDefault(Locale.forLanguageTag("tr-TR"))
 try { pass("classification is not affected by Turkish locale",scan("G.CUE" to cue("T.BIN"),"T.BIN" to "").problems.isEmpty()) } finally {Locale.setDefault(original)}
 println("TOTAL|$count")
}
'''
report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'scope':__doc__,'source_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'reproduce':['python3',str(Path(__file__).resolve())]}
with tempfile.TemporaryDirectory(prefix='gostex-disc-check-') as tmp:
 folder=Path(tmp); (folder/'Checks.kt').write_text(checks); out=folder/'classes'
 command=[str(java),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(compiler[1]),'-d',str(out)]+list(map(str,sources))+[str(folder/'Checks.kt')]
 compiled=subprocess.run(command,text=True,capture_output=True)
 report['compile']={'returncode':compiled.returncode,'stdout':compiled.stdout,'stderr':compiled.stderr}
 if compiled.returncode==0:
  result=subprocess.run([str(java),'-cp',str(out)+':'+str(compiler[1]),'tv.orbit.shield.ChecksKt'],text=True,capture_output=True)
  report['run']={'returncode':result.returncode,'stdout':result.stdout,'stderr':result.stderr}
  report['checks']=[line.removeprefix('PASS|') for line in result.stdout.splitlines() if line.startswith('PASS|')]
  report['passed']=result.returncode==0
 else: report['passed']=False
path=root/'.reports/disc-import-checks.json';path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'checks':len(report.get('checks',[])),'report':str(path)}))
if not report['passed']:
 print(report);raise SystemExit(1)
