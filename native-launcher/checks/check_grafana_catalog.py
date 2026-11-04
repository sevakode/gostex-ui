"""Run production catalog URL, preference and HTTP logic against local fixtures.

Android CookieManager/SharedPreferences are typed test boundaries. The JSON boundary
uses the bundled Groovy parser; no test dependency is added to the Android app.
TV focus and real WebView cookie sharing remain device checks.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
catalog = (SOURCES / 'GrafanaCatalog.kt').read_text()
options = (SOURCES / 'GrafanaOptions.kt').read_text()
normalizer = options[options.index('    fun normalizeUrl('):options.index('\n    fun read(')]

BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(): Editor
    interface Editor {
        fun putStringSet(key: String, value: Set<String>): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun apply()
    }
}
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''
COOKIES = '''package android.webkit
class CookieManager {
    var value: String? = "session=test-only"
    var flushCount = 0
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val saved = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    fun getCookie(url: String): String? { requested.add(url); return value }
    fun setCookie(url: String, value: String) { saved.add(url to value); if (value.startsWith("session=")) this.value = value.substringBefore(';') }
    fun flush() { flushCount++ }
}
'''
JSON_BOUNDARY = '''package org.json
import groovy.json.JsonSlurper
class JSONObject private constructor(private val value: Map<*, *>) {
    constructor(text: String) : this(JsonSlurper().parseText(text) as Map<*, *>)
    fun optLong(key: String, default: Long): Long = (value[key] as? Number)?.toLong() ?: default
    fun optString(key: String): String = value[key] as? String ?: ""
    companion object { fun from(value: Any?) = (value as? Map<*, *>)?.let { JSONObject(it) } }
}
class JSONArray(text: String) {
    private val values = JsonSlurper().parseText(text) as List<*>
    fun length() = values.size
    fun optJSONObject(index: Int): JSONObject? = JSONObject.from(values.getOrNull(index))
}
'''
CHECKS = r'''package tv.orbit.shield
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class Prefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>("other.setting" to "keep")
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, default: Set<String>?) = (values[key] ?: default) as Set<String>?
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    override fun edit() = object : SharedPreferences.Editor {
        override fun putStringSet(key: String, value: Set<String>): SharedPreferences.Editor { values[key] = value.toSet(); return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { values[key] = value; return this }
        override fun apply() { }
    }
}
private class TestContext(val preferences: Prefs) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences { check(name == "orbit" && mode == 0); return preferences }
}
fun main() {
    var count = 0
    fun pass(name: String) { count++; println("PASS|$name") }
    val base = GrafanaCatalogServer.fromUrl("HTTPS://Grafana.Example:443/grafana/d/one/title?orgId=6&from=now-12h&to=now&refresh=1m&var-team=old&timezone=America%2FArgentina%2FBuenos_Aires")!!
    check(base.baseUrl == "https://grafana.example/grafana/" && base.requestedOrgId == 6L)
    check(GrafanaCatalogServer.fromUrl("https://grafana.example/grafana/dashboards")==base.copy(requestedOrgId = null))
    check(GrafanaCatalogServer.fromUrl("https://grafana.example/grafana/")==base.copy(requestedOrgId = null))
    check(base.scope(6) != base.scope(1))
    check(base.scope(6) != base.copy(baseUrl = "https://grafana.example/another/").scope(6))
    check(base.scope(6) != base.copy(baseUrl = "http://grafana.example/grafana/").scope(6))
    pass("Server installation paths and actual organization IDs have separate scopes")

    for (bad in listOf("file:///tmp/a", "https://user:password@example.org", "https://grafana.example/d/one?orgId=0", "https://grafana.example/?orgId=6&orgId=7", "https://grafana.example/a/../d/one"))
        check(GrafanaCatalogServer.fromUrl(bad) == null)
    for (bad in listOf("https://elsewhere.example/grafana/d/one", "http://grafana.example/grafana/d/one", "https://grafana.example/other/d/one", "https://grafana.example/grafana/../d/one", "https://grafana.example/grafana/%2e%2e/d/one"))
        check(!base.accepts(bad))
    pass("Credential-bearing, ambiguous-org, traversal and cross-origin URLs are rejected")

    val original = "https://grafana.example/grafana/d/one/title?orgId=6&from=now-12h&to=now&refresh=1m&var-team=old&timezone=America%2FArgentina%2FBuenos_Aires"
    val other = GrafanaDashboard("two", "Other", "", "https://grafana.example/grafana/d/two/other")
    val changed = grafanaDashboardUrl(base, 6, other, original)!!
    check("orgId=6" in changed && "from=now-12h" in changed && "to=now" in changed && "refresh=1m" in changed && "timezone=America%2FArgentina%2FBuenos_Aires" in changed && "kiosk=true" in changed)
    check("var-team" !in changed)
    val same = grafanaDashboardUrl(base, 6, other.copy(uid="one", url="https://grafana.example/grafana/d/one/title"), original)!!
    check("var-team=old" in same)
    check(grafanaDashboardUrl(base, 6, other.copy(url="https://elsewhere.example/d/two/other"), original) == null)
    check(grafanaDashboardUrl(base, 6, other.copy(url="https://grafana.example/grafana/login"), original) == null)
    pass("Selection keeps time/refresh/timezone, isolates dashboard variables and enables kiosk")

    val sameUidTarget = other.copy(uid="one", url="https://grafana.example/grafana/d/one/title")
    val crossOrg = grafanaDashboardUrl(base.copy(requestedOrgId=7), 7, sameUidTarget, original)!!
    check("orgId=7" in crossOrg && "var-team" !in crossOrg && "from=now-12h" in crossOrg && "refresh=1m" in crossOrg)
    for (ambiguous in listOf("", "orgId=0&", "orgId=-6&", "orgId=invalid&", "orgId=6&orgId=6&", "orgId=6&orgId=7&")) {
        val anchor = "https://grafana.example/grafana/d/one/title?" + ambiguous + "from=now-12h&to=now&refresh=1m&var-team=old"
        val scoped = grafanaDashboardUrl(base, 6, sameUidTarget, anchor)!!
        check("var-team" !in scoped && "from=now-12h" in scoped && "to=now" in scoped && "refresh=1m" in scoped)
    }
    check("var-team=old" in grafanaDashboardUrl(base, 6, sameUidTarget, original.replace("orgId=6", "orgId=%36"))!!)
    pass("Same-UID variables require one explicit matching positive organization; common parameters survive")

    val prefs = Prefs(); val context = TestContext(prefs)
    val initial = prefs.values.toMap()
    check(GrafanaCatalogPreferences.readFavorites(context, base.scope(6)).isEmpty())
    check(!GrafanaCatalogPreferences.readFavoritesOnly(context, base.scope(6)))
    check(prefs.values == initial)
    GrafanaCatalogPreferences.toggleFavorite(context, base.scope(6), "one")
    GrafanaCatalogPreferences.setFavoritesOnly(context, base.scope(6), true)
    check(GrafanaCatalogPreferences.readFavorites(TestContext(prefs), base.scope(6)) == setOf("one"))
    check(GrafanaCatalogPreferences.readFavoritesOnly(TestContext(prefs), base.scope(6)))
    check(GrafanaCatalogPreferences.readFavorites(context, base.scope(1)).isEmpty())
    check(!GrafanaCatalogPreferences.readFavoritesOnly(context, base.scope(1)))
    val saved = prefs.values.toMap()
    GrafanaCatalogPreferences.toggleFavorite(context, base.scope(6), "../bad")
    check(prefs.values == saved && prefs.values["other.setting"] == "keep")
    GrafanaCatalogPreferences.toggleFavorite(context, base.scope(6), "one")
    check(GrafanaCatalogPreferences.readFavorites(context, base.scope(6)).isEmpty())
    for (key in prefs.values.keys.filter { it.startsWith(GrafanaCatalogPreferences.PREFIX) }) prefs.values[key] = "stale-type"
    check(GrafanaCatalogPreferences.readFavorites(context, base.scope(6)).isEmpty())
    check(!GrafanaCatalogPreferences.readFavoritesOnly(context, base.scope(6)))
    pass("Favorites and filter persist, survive stale values and never overwrite other organizations/settings")

    val hitPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
    val headerOrgs = java.util.Collections.synchronizedList(mutableListOf<String?>())
    val cookieHeaders = java.util.Collections.synchronizedList(mutableListOf<String?>())
    val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
    val rotationOrigins = java.util.Collections.synchronizedList(mutableListOf<String?>())
    val rotationBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    val externalHits = java.util.concurrent.atomic.AtomicInteger()
    val external = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    external.createContext("/") { exchange -> externalHits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
    external.start()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    server.executor = executor
    var mode = "pages"
    val reading = CountDownLatch(1)
    val rotationReading = CountDownLatch(1)
    fun row(index: Int) = """{"uid":"g$index","type":"dash-db","title":"Dashboard $index","url":"/grafana/d/g$index/title","folderTitle":"Folder"}"""
    server.createContext("/grafana/api/") { exchange ->
        hitPaths.add(exchange.requestURI.toString()); headerOrgs.add(exchange.requestHeaders.getFirst("X-Grafana-Org-Id")); cookieHeaders.add(exchange.requestHeaders.getFirst("Cookie")); methods.add(exchange.requestMethod)
        var status = 200
        var contentType = "application/json"
        val isRotation = exchange.requestURI.path.endsWith("/auth-tokens/rotate")
        val response = if (isRotation) {
            rotationOrigins.add(exchange.requestHeaders.getFirst("Origin"))
            rotationBodies.add(exchange.requestBody.bufferedReader().use { it.readText() })
            when (mode) {
                "rotation-redirect" -> { status = 302; exchange.responseHeaders.add("Location", "http://127.0.0.1:${external.address.port}/private"); "" }
                "rotation-denied" -> { status = 401; """{"messageId":"session.token.rotate"}""" }
                else -> { exchange.responseHeaders.add("Set-Cookie", "session=renewed-test; Path=/grafana; HttpOnly"); "{}" }
            }
        } else if (exchange.requestURI.path.endsWith("/org")) {
            if (mode.startsWith("rotation") && (mode !in listOf("rotation-success", "rotation-later") || exchange.requestHeaders.getFirst("Cookie") != "session=renewed-test")) {
                status = 401
                if (mode == "rotation-large") """{"messageId":"session.token.rotate","padding":"${"a".repeat(20000)}"}"""
                else """{"messageId":"session.token.rotate"}"""
            } else if (mode == "wrong-org") """{"id":7}""" else """{"id":6}"""
        } else when (mode) {
            "redirect" -> { status = 302; exchange.responseHeaders.add("Location", "http://127.0.0.1:${external.address.port}/private"); "" }
            "login" -> { contentType = "text/html"; "<html>Login</html>" }
            "denied" -> { status = 401; "{}" }
            "forbidden-rotate-message" -> { status = 403; """{"messageId":"session.token.rotate"}""" }
            "rotation-later" -> { status = 401; """{"messageId":"session.token.rotate"}""" }
            "bad-json" -> "{bad-json"
            "repeated" -> (0 until 200).joinToString(",", "[", "]", transform = ::row)
            "oversized-page" -> (0 until 5001).joinToString(",", "[", "]", transform = ::row)
            "stream" -> ""
            else -> if ("page=1" in exchange.requestURI.query) (0 until 200).joinToString(",", "[", "]", transform = ::row)
                else "[${row(200)},{\"uid\":\"bad\",\"title\":\"External\",\"type\":\"dash-db\",\"url\":\"http://127.0.0.1:${external.address.port}/d/bad/secret\"}]"
        }
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Set-Cookie", "fixture=received; Path=/grafana; HttpOnly")
        if ((mode == "stream" && exchange.requestURI.path.endsWith("/search")) || (mode == "rotation-stream" && isRotation)) {
            exchange.sendResponseHeaders(200, 0)
            try { repeat(200) { exchange.responseBody.write(" ".toByteArray()); exchange.responseBody.flush(); if (isRotation) rotationReading.countDown() else reading.countDown(); Thread.sleep(25) } } catch (_: Exception) { }
            exchange.close()
        } else {
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }
    server.start()
    val local = GrafanaCatalogServer.fromUrl("http://127.0.0.1:${server.address.port}/grafana/dashboards?orgId=6")!!
    try {
        val cookies = CookieManager()
        val result = GrafanaCatalogRequest(local, cookies).use { it.load() }
        check(result.orgId == 6L && result.dashboards.size == 201 && !result.truncated)
        check(hitPaths.size == 3 && headerOrgs.all { it == "6" } && cookieHeaders.all { it == "session=test-only" })
        check(cookies.requested.size == 3 && cookies.requested.all { it.startsWith(local.baseUrl + "api/") })
        check(cookies.saved.size == 3 && externalHits.get() == 0)
        pass("Real local HTTP requests paginate accessible dashboards and share exact-URL cookies without following external dashboard URLs")

        for ((fixture, reason) in listOf("redirect" to GrafanaCatalogFailure.SIGN_IN, "login" to GrafanaCatalogFailure.SIGN_IN, "denied" to GrafanaCatalogFailure.SIGN_IN, "forbidden-rotate-message" to GrafanaCatalogFailure.SIGN_IN, "bad-json" to GrafanaCatalogFailure.RESPONSE, "wrong-org" to GrafanaCatalogFailure.RESPONSE)) {
            mode = fixture
            val error = runCatching { GrafanaCatalogRequest(local, CookieManager()).use { it.load() } }.exceptionOrNull()
            check(error is GrafanaCatalogException && error.reason == reason) { fixture }
        }
        check(externalHits.get() == 0 && methods.none { it == "POST" })
        pass("Redirects never forward cookies; expired login, malformed JSON and wrong organization return honest errors")

        mode = "rotation-success"
        val rotationCookies = CookieManager()
        val beforeRotation = hitPaths.size
        val afterRotation = GrafanaCatalogRequest(local, rotationCookies).use { it.load() }
        check(afterRotation.dashboards.size == 201 && afterRotation.orgId == 6L)
        check(hitPaths.drop(beforeRotation) == listOf("/grafana/api/org", "/grafana/api/user/auth-tokens/rotate", "/grafana/api/org", "/grafana/api/search?type=dash-db&limit=200&page=1", "/grafana/api/search?type=dash-db&limit=200&page=2"))
        check(methods.drop(beforeRotation) == listOf("GET", "POST", "GET", "GET", "GET"))
        check(rotationOrigins.last() == "http://127.0.0.1:${server.address.port}" && rotationBodies.last() == "{}")
        check(cookieHeaders.drop(beforeRotation).take(2).all { it == "session=test-only" })
        check(cookieHeaders.drop(beforeRotation).drop(2).all { it == "session=renewed-test" })
        check(rotationCookies.value == "session=renewed-test" && rotationCookies.flushCount == 1)
        pass("An exact rotation challenge renews the existing same-origin session once and retries the original GET with the shared replacement cookie")

        for (fixture in listOf("rotation-repeat", "rotation-redirect", "rotation-denied")) {
            mode = fixture
            val beforeFailure = hitPaths.size
            val failure = runCatching { GrafanaCatalogRequest(local, CookieManager()).use { it.load() } }.exceptionOrNull()
            check(failure is GrafanaCatalogException && failure.reason == GrafanaCatalogFailure.SIGN_IN)
            check(methods.drop(beforeFailure).count { it == "POST" } == 1)
            check(hitPaths.size - beforeFailure == if (fixture == "rotation-repeat") 3 else 2)
        }
        check(externalHits.get() == 0)
        pass("Repeated rotation challenges, renewal rejection and renewal redirects cannot loop or forward credentials")

        mode = "rotation-later"
        val beforeLater = hitPaths.size
        val laterFailure = runCatching { GrafanaCatalogRequest(local, CookieManager()).use { it.load() } }.exceptionOrNull()
        check(laterFailure is GrafanaCatalogException && laterFailure.reason == GrafanaCatalogFailure.SIGN_IN)
        check(methods.drop(beforeLater) == listOf("GET", "POST", "GET", "GET"))
        pass("The renewal allowance applies to the whole catalog load, including later search pages")

        for (fixture in listOf("rotation-large", "rotation-no-cookie")) {
            mode = fixture
            val beforeSkipped = hitPaths.size
            val noCookie = CookieManager().apply { if (fixture == "rotation-no-cookie") value = null }
            val failure = runCatching { GrafanaCatalogRequest(local, noCookie).use { it.load() } }.exceptionOrNull()
            check(failure is GrafanaCatalogException && failure.reason == GrafanaCatalogFailure.SIGN_IN)
            check(methods.drop(beforeSkipped) == listOf("GET"))
        }
        pass("Oversized error bodies and absent browser cookies never trigger a session-renewal POST")

        mode = "rotation-stream"
        val beforeCanceledRenewal = hitPaths.size
        val renewalRequest = GrafanaCatalogRequest(local, CookieManager())
        val renewalFuture = executor.submit<Throwable?> { runCatching { renewalRequest.load() }.exceptionOrNull() }
        check(rotationReading.await(3, TimeUnit.SECONDS))
        renewalRequest.close()
        check(renewalFuture.get(3, TimeUnit.SECONDS) is CancellationException)
        check(methods.drop(beforeCanceledRenewal) == listOf("GET", "POST"))
        pass("Closing during renewal cancels its body read and prevents the original GET retry")

        mode = "repeated"
        val before = hitPaths.size
        val repeated = GrafanaCatalogRequest(local, CookieManager()).use { it.load() }
        check(repeated.dashboards.size == 200 && repeated.truncated && hitPaths.size - before == 3)
        pass("A server ignoring pagination cannot loop indefinitely or duplicate dashboards")

        mode = "oversized-page"
        val beforeOversized = hitPaths.size
        val bounded = GrafanaCatalogRequest(local, CookieManager()).use { it.load() }
        check(bounded.dashboards.size == 5000 && bounded.truncated && hitPaths.size - beforeOversized == 2)
        pass("A server ignoring the requested page size still cannot exceed 5000 native rows")

        val closed = GrafanaCatalogRequest(local, CookieManager())
        closed.close(); val beforeClosed = hitPaths.size
        check(runCatching { closed.load() }.exceptionOrNull() is CancellationException)
        check(beforeClosed == hitPaths.size)
        mode = "stream"
        val active = GrafanaCatalogRequest(local, CookieManager())
        val future = executor.submit<Throwable?> { runCatching { active.load() }.exceptionOrNull() }
        check(reading.await(3, TimeUnit.SECONDS))
        active.close()
        check(future.get(3, TimeUnit.SECONDS) is CancellationException)
        val afterClosed = hitPaths.size
        Thread.sleep(80)
        check(hitPaths.size == afterClosed)
        pass("Closing before/during a read cancels the request and creates no further HTTP work")
    } finally { server.stop(0); external.stop(0); executor.shutdownNow() }
    println("TOTAL|$count")
}
'''

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', artifact, version) for artifact, version in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
groovy = ROOT / '.tools/native/gradle/gradle-9.8.2/lib'
classpath = ':'.join(map(str, [compiler[1], compiler[-1], groovy / 'groovy-4.0.33.jar', groovy / 'groovy-json-4.0.33.jar']))
report = {'timestampUtc': datetime.now(timezone.utc).isoformat(), 'scope': __doc__,
          'sourceSha256': hashlib.sha256(catalog.encode()).hexdigest(), 'passed': False,
          'notCovered': ['TV dialog D-pad focus and Back', 'Real WebView CookieManager session sharing', 'Activity stop/dispose connection closure']}
with tempfile.TemporaryDirectory(prefix='gostex-grafana-catalog-') as folder:
    temporary = Path(folder)
    contents = {
        'Boundary.kt': BOUNDARY, 'Cookies.kt': COOKIES, 'Json.kt': JSON_BOUNDARY,
        'GrafanaCatalog.kt': catalog,
        'GrafanaPreferences.kt': 'package tv.orbit.shield\nimport java.net.URI\ninternal object GrafanaPreferences { const val MAX_URL_LENGTH = 8192\n' + normalizer + '\n}',
        'Checks.kt': CHECKS,
    }
    for name, value in contents.items(): (temporary / name).write_text(value)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes), *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        # Android's HttpURLConnection permits the same-origin Origin header. Desktop JDK filters it
        # unless this test-only switch is enabled; it is not an Android app/runtime configuration.
        run = subprocess.run([str(JAVA), '-Dsun.net.http.allowRestrictedHeaders=true', '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True, timeout=45)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 15
output = ROOT / '.reports/grafana-catalog-checks.json'
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2)); raise SystemExit(1)
