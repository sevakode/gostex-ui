package tv.orbit.shield

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A real Grafana page, with no native bridge or access to local files. */
internal class GrafanaScreen(context: Context, private val interactive: Boolean) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(context).apply {
        setTextColor(Color.rgb(221, 225, 228))
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(dp(64), dp(36), dp(64), dp(36))
        setBackgroundColor(Color.rgb(13, 17, 22))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private var options = GrafanaOptions()
    private var web: WebView? = null
    private var active = false
    private var destroyed = false
    private var failed = false
    private var loading = false
    private var renderedUrl: String? = null
    private var pageVisibleAt: Long? = null
    private val rotation = GrafanaRotationQueue()
    private var catalog: GrafanaCatalogResult? = null
    private var catalogFavorites: Set<String> = emptySet()
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var catalogJob: Job? = null
    private var catalogRequest: GrafanaCatalogRequest? = null
    private var catalogGeneration = 0L
    private val timeout = Runnable {
        if (active && loading) showFailure(R.string.grafana_loading_timeout)
    }
    private val refresh = Runnable {
        if (automaticReloadEnabled()) reload()
    }
    private val scrollRestart = Runnable { updateAutoScroll() }
    private val rotate = Runnable {
        if (active && !destroyed && rotationEnabled() && rotation.canAdvance) {
            rotation.advance()?.let { displayUrl(it.url) }
        }
    }

    init {
        setBackgroundColor(Color.rgb(13, 17, 22))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showStatus(R.string.grafana_url_missing)
    }

    fun configure(value: GrafanaOptions) {
        val changedUrl = value.url != options.url
        val changedRefresh = value.refreshSeconds != options.refreshSeconds
        val changedScroll = value.scrollMode != options.scrollMode
        val changedRotation = value.rotationMode != options.rotationMode
        val changedDuration = value.rotationSeconds != options.rotationSeconds
        val wasSingle = options.rotationMode == GrafanaRotationMode.SINGLE
        options = value
        if (changedUrl) {
            if (catalog?.server != GrafanaCatalogServer.fromUrl(value.url)) catalog = null
            // Destroy the previous page: redirects and delayed callbacks cannot reveal stale data.
            if (active) beginPresentation() else {
                cancelCatalogLoad()
                releaseWebView()
                showStatus(R.string.grafana_loading)
            }
        } else if (active) {
            if (changedRotation) {
                if (wasSingle && !interactive) pageVisibleAt = if (web != null && !loading && !failed) SystemClock.elapsedRealtime() else null
                if (!rotationEnabled()) {
                    cancelCatalogLoad()
                    rotation.clear()
                    handler.removeCallbacks(rotate)
                    displayUrl(options.url)
                } else if (catalog != null) applyRotationCatalog()
                else if (catalogRequest == null) discoverRotationCatalog()
            } else if (rotationEnabled()) {
                val loaded = catalog
                if (loaded != null && GrafanaCatalogPreferences.readFavorites(context, loaded.scope) != catalogFavorites) {
                    applyRotationCatalog()
                }
            }
            if (changedRefresh) scheduleRefresh()
            if (changedDuration) scheduleRotation()
        }
        if (!changedUrl && active && changedScroll) updateAutoScroll()
    }

    fun start() {
        if (destroyed || active) return
        active = true
        beginPresentation()
    }

    fun stop() {
        active = false
        cancelCatalogLoad()
        catalog = null
        catalogFavorites = emptySet()
        rotation.clear()
        renderedUrl = null
        pageVisibleAt = null
        releaseWebView()
    }

    fun destroy() {
        stop()
        destroyed = true
        catalogScope.cancel()
    }

    fun reload() {
        if (!active || destroyed) return
        if (renderedUrl == null) { beginPresentation(); return }
        // An explicit reload recovers from renderer failure as well as a network error.
        releaseWebView()
        openConfiguredPage()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rotationEnabled() = !interactive && options.rotationMode != GrafanaRotationMode.SINGLE

    private fun beginPresentation() {
        cancelCatalogLoad()
        releaseWebView()
        rotation.clear()
        renderedUrl = null
        pageVisibleAt = null
        if (rotationEnabled()) {
            if (catalog != null) applyRotationCatalog() else discoverRotationCatalog()
        } else displayUrl(options.url)
    }

    private fun displayUrl(value: String) {
        val url = GrafanaPreferences.normalizeUrl(value)
        if (url.isNullOrBlank()) {
            releaseWebView()
            renderedUrl = null
            pageVisibleAt = null
            showStatus(R.string.grafana_url_missing)
            return
        }
        if (renderedUrl == url && web != null) {
            scheduleRotation()
            return
        }
        releaseWebView()
        renderedUrl = url
        pageVisibleAt = null
        openConfiguredPage()
    }

    private fun discoverRotationCatalog() {
        if (!active || destroyed || !rotationEnabled() || catalogRequest != null) return
        releaseWebView()
        renderedUrl = null
        pageVisibleAt = null
        val server = GrafanaCatalogServer.fromUrl(options.url)
        if (server == null) { showStatus(R.string.grafana_url_missing); return }
        val request = try { GrafanaCatalogRequest(server, CookieManager.getInstance()) }
        catch (_: RuntimeException) { showStatus(R.string.grafana_webview_unavailable); return }
        val generation = ++catalogGeneration
        catalogRequest = request
        showStatus(R.string.grafana_rotation_loading)
        catalogJob = catalogScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { request.load() }
                if (active && !destroyed && generation == catalogGeneration && rotationEnabled()) {
                    catalog = result
                    applyRotationCatalog()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GrafanaCatalogException) {
                if (active && !destroyed && generation == catalogGeneration) {
                    showStatus(when (failure.reason) {
                        GrafanaCatalogFailure.SIGN_IN -> R.string.grafana_catalog_signin_needed
                        GrafanaCatalogFailure.NETWORK -> R.string.grafana_catalog_network_error
                        GrafanaCatalogFailure.RESPONSE -> R.string.grafana_catalog_response_error
                    })
                }
            } finally {
                request.close()
                if (generation == catalogGeneration) {
                    catalogRequest = null
                    catalogJob = null
                }
            }
        }
    }

    private fun cancelCatalogLoad() {
        catalogGeneration++
        catalogRequest?.close()
        catalogRequest = null
        catalogJob?.cancel()
        catalogJob = null
    }

    private fun applyRotationCatalog() {
        if (!active || destroyed || !rotationEnabled()) return
        val loaded = catalog ?: return
        catalogFavorites = GrafanaCatalogPreferences.readFavorites(context, loaded.scope)
        rotation.replace(grafanaRotationPages(loaded, options.rotationMode, catalogFavorites, options.url),
            grafanaRotationAnchorUid(loaded.server, options.url))
        val selected = rotation.current
        if (selected == null) {
            releaseWebView()
            renderedUrl = null
            pageVisibleAt = null
            showStatus(if (options.rotationMode == GrafanaRotationMode.FAVORITES)
                R.string.grafana_rotation_favorites_empty else R.string.grafana_rotation_empty)
        } else displayUrl(selected.url)
    }

    private fun scheduleRotation() {
        handler.removeCallbacks(rotate)
        if (!active || destroyed || !rotationEnabled() || !rotation.canAdvance) return
        val delay = if (failed) ROTATION_FAILURE_DELAY_MS else {
            if (loading) return
            val visibleAt = pageVisibleAt ?: return
            grafanaRotationRemainingMillis(options.rotationSeconds, visibleAt, SystemClock.elapsedRealtime())
        }
        handler.postDelayed(rotate, delay)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun openConfiguredPage() {
        if (!active || destroyed || web != null) return
        val url = renderedUrl?.let(GrafanaPreferences::normalizeUrl)
        if (url.isNullOrBlank()) {
            showStatus(R.string.grafana_url_missing)
            return
        }
        // A disabled or missing Android System WebView must not take down the launcher/dream.
        val page = try {
            WebView(context).apply {
                id = View.generateViewId()
                setBackgroundColor(Color.rgb(13, 17, 22))
                isFocusable = interactive
                isFocusableInTouchMode = interactive
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                setDownloadListener { _, _, _, _, _ -> /* Downloads belong outside the screensaver. */ }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            }
        } catch (_: RuntimeException) {
            showStatus(R.string.grafana_webview_unavailable)
            return
        }
        web = page
        page.webViewClient = object : WebViewClient() {
            private fun current(view: WebView) = active && view === web
            private fun routeKey(value: String?) = value?.substringBefore('?')?.substringBefore('#')
            private var scrollRoute = routeKey(url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                GrafanaPreferences.normalizeUrl(request.url.toString()) == null

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                GrafanaPreferences.normalizeUrl(url) == null

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!current(view)) return
                stopAutoScroll(view)
                handler.removeCallbacks(scrollRestart)
                failed = false
                loading = true
                showStatus(R.string.grafana_loading)
                handler.removeCallbacks(timeout)
                handler.removeCallbacks(refresh)
                handler.removeCallbacks(rotate)
                handler.postDelayed(timeout, LOAD_TIMEOUT_MS)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!current(view) || interactive) return
                val nextRoute = routeKey(url)
                if (nextRoute != null && nextRoute != scrollRoute) {
                    scrollRoute = nextRoute
                    stopAutoScroll(view)
                    handler.removeCallbacks(scrollRestart)
                    // Grafana playlists navigate within the existing SPA document. Query-only
                    // normalization (time/variables) must not reset motion or its end pauses.
                    handler.postDelayed(scrollRestart, SCROLL_ROUTE_SETTLE_MS)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (current(view) && !failed) {
                    loading = false
                    handler.removeCallbacks(timeout)
                    showPage()
                    scheduleRefresh()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!current(view)) return
                loading = false
                handler.removeCallbacks(timeout)
                if (!failed) showPage()
                CookieManager.getInstance().flush()
                scheduleRefresh()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (current(view) && request.isForMainFrame && !failed) showFailure(R.string.grafana_load_failed)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (current(view) && request.isForMainFrame && !failed) showFailure(R.string.grafana_load_failed)
            }

            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                if (current(view)) showFailure(R.string.grafana_ssl_error)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === web) {
                    releaseWebView()
                    showFailure(R.string.grafana_load_failed)
                }
                return true
            }
        }
        addView(page, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        failed = false
        loading = true
        showStatus(R.string.grafana_loading)
        handler.postDelayed(timeout, LOAD_TIMEOUT_MS)
        page.onResume()
        page.loadUrl(url)
    }

    private fun showPage() {
        status.visibility = View.GONE
        web?.visibility = View.VISIBLE
        if (pageVisibleAt == null) pageVisibleAt = SystemClock.elapsedRealtime()
        updateAutoScroll()
        scheduleRotation()
    }

    private fun showStatus(message: Int) {
        status.text = context.getString(message)
        status.visibility = View.VISIBLE
        web?.visibility = View.INVISIBLE
    }

    private fun showFailure(message: Int) {
        failed = true
        loading = false
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(scrollRestart)
        web?.let(::stopAutoScroll)
        web?.stopLoading()
        showStatus(message)
        if (rotationEnabled() && rotation.canAdvance) {
            handler.removeCallbacks(refresh)
            scheduleRotation()
        } else scheduleRefresh()
    }

    private fun automaticReloadEnabled(): Boolean =
        active && !destroyed && !interactive && options.refreshSeconds > 0 &&
            !renderedUrl.isNullOrBlank()

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        // Preview never reloads a login form. Zero leaves dashboard refresh/playlist timing to Grafana.
        // A renderer crash destroys WebView; a configured retry must still recreate it later.
        if (automaticReloadEnabled()) {
            handler.postDelayed(refresh, options.refreshSeconds.coerceAtLeast(60) * 1_000L)
        }
    }

    private fun updateAutoScroll() {
        // Login/preview is always user-controlled, even when the saver scroll setting is enabled.
        if (!active || destroyed || interactive || failed || loading) return
        web?.evaluateJavascript(GrafanaAutoScroll.script(options.scrollMode), null)
    }

    private fun stopAutoScroll(page: WebView) {
        if (!interactive) {
            // A renderer-gone callback can reach teardown after JavaScript is unavailable.
            try { page.evaluateJavascript(GrafanaAutoScroll.stopScript, null) }
            catch (_: RuntimeException) { /* Destroying the WebView also cancels its callbacks. */ }
        }
    }

    private fun releaseWebView() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(scrollRestart)
        handler.removeCallbacks(rotate)
        loading = false
        val page = web
        web = null
        if (page != null) {
            stopAutoScroll(page)
            page.stopLoading()
            page.onPause()
            page.webViewClient = WebViewClient()
            removeView(page)
            page.destroy()
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 45_000L
        const val SCROLL_ROUTE_SETTLE_MS = 500L
        const val ROTATION_FAILURE_DELAY_MS = 5_000L
    }
}

/** Interactive login/preview; closing it leaves ordinary platform cookies for the dream. */
class GrafanaPreviewActivity : Activity() {
    private lateinit var screen: GrafanaScreen
    private lateinit var preferences: SharedPreferences
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(GrafanaPreferences.KEY_PREFIX)) {
            screen.configure(GrafanaPreferences.read(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val localized = Language.context(this)
        screen = GrafanaScreen(localized, interactive = true)
        preferences = getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val column = LinearLayout(localized).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 17, 22))
        }
        val header = LinearLayout(localized).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, 0)
        }
        header.addView(TextView(localized).apply {
            text = localized.getString(R.string.grafana_preview_title)
            textSize = 18f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(localized).apply {
            setText(R.string.grafana_reload)
            setOnClickListener { screen.reload() }
        })
        header.addView(Button(localized).apply {
            setText(R.string.close)
            setOnClickListener { finish() }
        })
        column.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (60 * resources.displayMetrics.density).toInt()))
        column.addView(screen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        window.hidePreviewSystemBars()
    }

    override fun onStart() {
        super.onStart()
        preferences.registerOnSharedPreferenceChangeListener(listener)
        screen.configure(GrafanaPreferences.read(this))
    }

    override fun onResume() { super.onResume(); screen.start() }
    override fun onPause() { screen.stop(); super.onPause() }
    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onStop()
    }
    override fun onDestroy() { screen.destroy(); super.onDestroy() }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
