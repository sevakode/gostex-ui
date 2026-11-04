"""Execute production cover manifest, persisted index and source-selection logic on the JVM."""
from pathlib import Path
import subprocess, tempfile, json, hashlib
ROOT=Path(__file__).resolve().parents[2]
CACHE=ROOT/'.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA=ROOT/'.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
GRADLE=ROOT/'.tools/native/gradle/gradle-9.8.2/lib'
def jar(group,artifact,version): return next((CACHE/group/artifact/version).glob('*/*.jar'))
compiler=[jar('org.jetbrains.kotlin',a,v) for a,v in [('kotlin-compiler-embeddable','2.4.21'),('kotlin-stdlib','2.4.21'),('kotlin-script-runtime','2.4.21'),('kotlin-reflect','1.6.10')]]+[jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),jar('org.jetbrains','annotations','13.0')]
libs=[compiler[1],GRADLE/'groovy-4.0.33.jar',GRADLE/'groovy-json-4.0.33.jar']
cp=':'.join(map(str,libs))
source=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/ManagedCoverModel.kt'
boundary='''package org.json
class JSONObject {
 val values: MutableMap<String,Any?>
 constructor() { values=linkedMapOf() }
 constructor(text:String) { @Suppress("UNCHECKED_CAST") val parsed=groovy.json.JsonSlurper().parseText(text) as Map<String,Any?>; values=parsed.toMutableMap() }
 constructor(map:Map<String,Any?>) { values=map.toMutableMap() }
 fun opt(key:String):Any?=values[key]
 fun has(key:String)=values.containsKey(key)
 fun getString(key:String)=values[key] as String
 fun optString(key:String)=values[key] as? String ?: ""
 fun length()=values.size
 fun keys()=values.keys.iterator()
 fun put(key:String,value:Any?):JSONObject { values[key]=if(value is JSONObject)value.values else value;return this }
 fun getJSONObject(key:String):JSONObject { @Suppress("UNCHECKED_CAST") val map=values[key] as Map<String,Any?>;return JSONObject(map) }
 fun getJSONArray(key:String)=JSONArray(values[key] as List<*>)
 override fun toString()=groovy.json.JsonOutput.toJson(values)
}
class JSONArray(val items:List<*>) {
 fun length()=items.size
 fun getJSONObject(index:Int):JSONObject { @Suppress("UNCHECKED_CAST") val map=items[index] as Map<String,Any?>;return JSONObject(map) }
}
'''
checks=r'''package tv.orbit.shield
import groovy.json.JsonOutput
fun main() {
 var count=0
 fun test(name:String, body:()->Unit) { body();println("PASS|$name");count++ }
 val digest="a".repeat(64)
 fun update(id:String="game",path:String="incoming/game.webp",sha:String=digest)=mapOf("gameId" to id,"wide" to path,"wideSha256" to sha)
 fun manifest(rows:List<Map<String,Any?>>,version:Any=1)=JsonOutput.toJson(mapOf("version" to version,"covers" to rows))
 fun rejected(text:String) { check(runCatching { ManagedCoverManifest.parse(text) }.isFailure) }
 test("Valid generated cover remains bound to its stable game id and checksum") {
  val rows=ManagedCoverManifest.parse(manifest(listOf(update())))
  check(rows.single()==CoverUpdate("game","","","incoming/game.webp",digest))
 }
 test("Original and wide variants can be imported together") {
  val row=update()+mapOf("original" to "incoming/original.png","originalSha256" to "b".repeat(64))
  check(ManagedCoverManifest.parse(manifest(listOf(row))).single().original=="incoming/original.png")
 }
 test("Traversal, absolute paths, backslashes, URIs and unsupported formats fail before file access") {
  for(path in listOf("../secret.png","incoming/../../secret.webp","/sdcard/a.png","incoming\\a.png","content://x/a.png","a//b.png","./a.png","incoming/file.svg","a/b/c/d/e.png")) rejected(manifest(listOf(update(path=path))))
 }
 test("Ambiguous duplicate ids and invalid digests are rejected atomically") {
  rejected(manifest(listOf(update(),update())))
  for(sha in listOf("", "a".repeat(63),"G".repeat(64))) rejected(manifest(listOf(update(sha=sha))))
 }
 test("Malformed, unsupported-version and oversized manifests cannot replace working data") {
  rejected("not json"); rejected(manifest(listOf(update()),2)); rejected(" ".repeat(ManagedCoverManifest.MAX_BYTES+1))
  rejected(manifest((0..ManagedCoverManifest.MAX_ENTRIES).map { update(id="game$it") }))
  rejected(manifest(listOf(mapOf("gameId" to "game"))))
 }
 val manual=managedCoverKey(digest);val original=managedCoverKey("b".repeat(64));val wide=managedCoverKey("c".repeat(64))
 val entry=ManagedCover(manual,original,wide)
 test("Manual selection stays ahead of agent updates; original and automatic remain selectable") {
  check(selectManagedCover("media/base.png",entry,CoverChoice.MANUAL)==SelectedCover(manual))
  check(selectManagedCover("media/base.png",entry.copy(wide=managedCoverKey("d".repeat(64))),CoverChoice.MANUAL)==SelectedCover(manual))
  check(selectManagedCover("media/base.png",entry,CoverChoice.AUTO)==SelectedCover(original,wide))
  check(selectManagedCover("media/base.png",entry,CoverChoice.ORIGINAL)==SelectedCover("media/base.png"))
 }
 test("Missing or invalid choices use safe defaults without modifying stored data") {
  check(CoverChoice.read("unknown")==CoverChoice.AUTO); check(CoverChoice.read(null)==CoverChoice.AUTO)
  check(selectManagedCover("media/base.png",null,CoverChoice.MANUAL)==SelectedCover("media/base.png"))
  check(selectManagedCover("media/base.png",ManagedCover(),CoverChoice.MANUAL)==SelectedCover("media/base.png"))
 }
 test("Index round trip preserves every source and stable game identity") {
  val entries=mapOf("game-with:stable/id" to entry)
  check(ManagedCoverManifest.decodeIndex(ManagedCoverManifest.encodeIndex(entries))==entries)
 }
 test("Tampered index cannot introduce arbitrary filesystem paths") {
  val rows=mapOf("version" to 1,"games" to mapOf("game" to mapOf("manual" to "/data/private.png","original" to "managed-cover:../bad","wide" to wide)))
  check(ManagedCoverManifest.decodeIndex(JsonOutput.toJson(rows))["game"]==ManagedCover(wide=wide))
 }
 println("TOTAL|$count")
}
'''
report={'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'scope':__doc__,'notCovered':['Android bitmap decoding and SAF grants','Compose focus and lifecycle callbacks','Device import and export integration']}
with tempfile.TemporaryDirectory(prefix='gostex-managed-covers-') as folder:
 p=Path(folder)
 for name,text in [('ManagedCoverModel.kt',source.read_text()),('Json.kt',boundary),('Checks.kt',checks)]: (p/name).write_text(text)
 build=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(p/'classes'),*[str(f) for f in p.glob('*.kt')]],capture_output=True,text=True)
 report['compile']={'code':build.returncode,'stderr':build.stderr}
 if build.returncode==0:
  run=subprocess.run([str(JAVA),'-cp',f'{p/"classes"}:{cp}','tv.orbit.shield.ChecksKt'],capture_output=True,text=True)
  report['run']={'code':run.returncode,'stdout':run.stdout,'stderr':run.stderr}
  report['passed']=run.returncode==0 and 'TOTAL|9' in run.stdout
 else: report['passed']=False
out=ROOT/'.reports/launcher30/managed-cover-checks.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2));raise SystemExit(0 if report['passed'] else 1)
