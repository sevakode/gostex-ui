package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal class TvBrowserActivity : ComponentActivity() {
    private lateinit var address: EditText
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var reloadButton: Button
    private lateinit var pointerButton: Button
    private lateinit var pointerHint: TextView
    private lateinit var tools: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var loadingBar: ProgressBar
    private lateinit var status: TextView
    private var page: WebView? = null
    private var pointer: TvBrowserPointer? = null
    private var prompt: AlertDialog? = null
    private var active = false
    private var currentUrl: String? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.context(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val padding = dp(12)
            view.setPadding(insets.left + padding, insets.top + padding, insets.right + padding, insets.bottom + padding)
            windowInsets
        }
        address = EditText(this).apply {
            id = View.generateViewId()
            setSingleLine()
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            hint = getString(R.string.browser_address)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setTextSize(16f)
            setSelectAllOnFocus(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = focusBackground(accentFill = false)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    submitAddress()
                    true
                } else false
            }
        }
        val go = control(null, R.string.browser_go, ::submitAddress)
        val location = horizontalBar().apply {
            addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))
            addControl(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        backButton = control("‹", R.string.browser_back) { page?.goBack() }
        forwardButton = control("›", R.string.browser_forward) { page?.goForward() }
        reloadButton = control("↻", R.string.browser_reload) {
            if (page == null) currentUrl?.let(::openPage) else page?.reload()
        }
        pointerButton = control("⌖", R.string.browser_pointer, ::togglePointer)
        tools = horizontalBar().apply {
            addControl(backButton)
            addControl(forwardButton)
            addControl(reloadButton)
            addControl(control("☆", R.string.browser_bookmarks, ::showBookmarks))
            addControl(pointerButton)
            addControl(control("↓", R.string.browser_downloads, ::finish))
        }
        if (resources.configuration.screenWidthDp >= 640) {
            layout.addView(horizontalBar().apply {
                addView(location, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(tools, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
            })
        } else {
            layout.addView(location)
            layout.addView(tools, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })
        }
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        layout.addView(loadingBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        layout.addView(status)
        pointerHint = TextView(this).apply {
            setText(R.string.browser_pointer_hint)
            setTextColor(Color.LTGRAY)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        layout.addView(pointerHint)
        content = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (active && pointer?.handleKey(event) == true) return true
                return super.dispatchKeyEvent(event)
            }
        }
        layout.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pointer != null) {
                    stopPointer(restoreFocus = true)
                    return
                }
                val web = page
                if (web?.canGoBack() == true) web.goBack() else finish()
            }
        })
        currentUrl = savedInstanceState?.getString(STATE_PAGE)?.let(TvBrowserOptions::webUrl)
        val draft = savedInstanceState?.getString(STATE_ADDRESS)
        currentUrl?.let(::openPage)
        if (draft != null) address.setText(draft)
        if (intent.getBooleanExtra(RESELECT_DOWNLOAD, false)) showStatus(R.string.http_download_session_pick_same)
        if (page == null) address.requestFocus()
        updateControls()
    }

    private fun submitAddress() {
        val text = address.text.toString()
        val magnet = TvBrowserOptions.magnet(text)
        if (magnet != null) {
            confirmDownload(magnet)
        } else {
            val url = TvBrowserOptions.address(this, text)
            if (url == null) showStatus(R.string.browser_error_address) else openPage(url)
        }
    }

    private fun openPage(url: String) {
        currentUrl = url
        val web = page ?: createPage() ?: return
        showStatus(null)
        address.setText(url)
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
        web.requestFocus()
        web.loadUrl(url)
    }

    private fun createPage(): WebView? {
        val web = try {
            WebView(this)
        } catch (_: RuntimeException) {
            showStatus(R.string.browser_error_webview)
            updateControls()
            return null
        }
        web.apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            nextFocusUpId = address.id
            setOnFocusChangeListener { _, focused -> if (!focused) stopPointer() }
            setBackgroundColor(BACKGROUND)
            configure(settings)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    if (page !== view) return
                    loadingBar.progress = value
                    loadingBar.visibility = if (value < 100) View.VISIBLE else View.INVISIBLE
                }
            }
            webViewClient = pageClient()
            setDownloadListener { url, _, _, mime, size ->
                if (page !== this) return@setDownloadListener
                val link = TvBrowserOptions.webUrl(url.orEmpty())
                if (link == null) {
                    showStatus(R.string.browser_error_download)
                } else {
                    handOffDownload(link, size, mime.orEmpty())
                }
            }
        }
        page = web
        address.nextFocusDownId = web.id
        for (index in 0 until tools.childCount) tools.getChildAt(index).nextFocusDownId = web.id
        content.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (active) web.onResume()
        updateControls()
        return web
    }

    @Suppress("DEPRECATION", "SetJavaScriptEnabled")
    private fun configure(settings: WebSettings) = with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        safeBrowsingEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    private fun pageClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (page !== view) return true
            val uri = request.url.toString()
            if (TvBrowserOptions.webUrl(uri) != null) return false
            if (request.isForMainFrame && request.hasGesture()) {
                val magnet = TvBrowserOptions.magnet(uri)
                if (magnet != null) confirmDownload(magnet) else showStatus(R.string.browser_error_address)
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (page === view) showStatus(null)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (page !== view) return
            val visited = url?.let(TvBrowserOptions::webUrl)
            if (visited != null) {
                currentUrl = visited
                if (!address.hasFocus()) address.setText(visited)
            }
            updateControls()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (page === view) updateControls()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (page === view && request.isForMainFrame) showStatus(R.string.browser_error_page)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
            if (page === view && request.isForMainFrame) showStatus(R.string.browser_error_page)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (page === view) showStatus(R.string.browser_error_page)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (page === view) {
                val pageHadFocus = view.hasFocus()
                releasePage(rendererGone = true)
                if (pageHadFocus) reloadButton.requestFocusFromTouch()
                showStatus(R.string.browser_error_webview)
            }
            return true
        }
    }

    private fun showBookmarks() {
        val bookmarks = TvBrowserOptions.bookmarks(this)
        val url = currentUrl
        AlertDialog.Builder(this).setTitle(R.string.browser_bookmarks).apply {
            if (bookmarks.isEmpty()) setMessage(R.string.browser_no_bookmarks)
            else setItems(bookmarks.toTypedArray()) { _, index -> openPage(bookmarks[index]) }
            if (url != null) {
                val action = if (url in bookmarks) R.string.browser_bookmark_remove else R.string.browser_bookmark_add
                setNeutralButton(action) { _, _ ->
                    try {
                        TvBrowserOptions.toggleBookmark(this@TvBrowserActivity, url)
                    } catch (_: IllegalArgumentException) {
                        showStatus(R.string.browser_bookmark_limit)
                    }
                }
            }
            setNegativeButton(R.string.cancel, null)
        }.show()
    }

    private fun handOffDownload(link: String, size: Long, mime: String) {
        if (!active || isFinishing || prompt != null) return
        val attachment = link.substringBefore('#')
        val cookieLookup: (String) -> String? = { CookieManager.getInstance().getCookie(it) }
        // Session creation checks whether a link could use cookies, without looking them up.
        // Check for their presence here so public same-origin attachments can still go directly
        // to the queue form. If WebView cannot report cookies, keep the review prompt.
        if (!intent.getBooleanExtra(RESELECT_DOWNLOAD, false) &&
            link.startsWith("https://", ignoreCase = true) && TvBrowserOptions.quickDownload(this) &&
            runCatching { cookieLookup(attachment).isNullOrBlank() }.getOrDefault(false)) {
            returnDownloadLink(link)
        } else {
            val session = HttpDownloadSession.create(currentUrl.orEmpty(), attachment, size, mime, cookieLookup)
            confirmDownload(link, session)
        }
    }

    private fun returnDownloadLink(link: String) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_LINK, link))
        finish()
    }

    private fun confirmDownload(link: String, session: HttpDownloadSession? = null) {
        if (!active || isFinishing || prompt != null) return
        val reselect = intent.getBooleanExtra(RESELECT_DOWNLOAD, false)
        if (reselect && session == null) {
            showStatus(R.string.http_download_session_pick_same)
            return
        }
        val host = Uri.parse(link).host
        val message = getString(R.string.browser_download_review)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_download_title)
            .setMessage(if (host.isNullOrEmpty()) message else "$message\n\n$host")
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (!reselect) setPositiveButton(R.string.browser_downloads) { _, _ ->
                    returnDownloadLink(link)
                }
                if (session != null) {
                    val acceptSession = android.content.DialogInterface.OnClickListener { _, _ ->
                        setResult(RESULT_OK, Intent().putExtra(RESULT_SESSION, BrowserDownloadDrafts.offer(session)))
                        finish()
                    }
                    if (reselect) setPositiveButton(R.string.browser_use_site_session, acceptSession)
                    else setNeutralButton(R.string.browser_use_site_session, acceptSession)
                }
            }
            .create()
        prompt = dialog
        dialog.setOnDismissListener { prompt = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun showStatus(message: Int?) {
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) {
            status.setText(message)
            loadingBar.visibility = View.INVISIBLE
        }
    }

    private fun updateControls() {
        backButton.isEnabled = page?.canGoBack() == true
        forwardButton.isEnabled = page?.canGoForward() == true
        reloadButton.isEnabled = currentUrl != null
        pointerButton.isEnabled = page != null
    }

    private fun togglePointer() {
        if (pointer != null) {
            stopPointer(restoreFocus = true)
            return
        }
        val web = page ?: return
        if (!web.requestFocus()) return
        pointer = TvBrowserPointer(content, web).also { it.show() }
        pointerHint.visibility = View.VISIBLE
    }

    private fun stopPointer(restoreFocus: Boolean = false) {
        val current = pointer ?: return
        pointer = null
        current.hide()
        pointerHint.visibility = View.GONE
        if (restoreFocus) pointerButton.requestFocusFromTouch()
    }

    override fun onResume() {
        super.onResume()
        active = true
        page?.onResume()
    }

    override fun onPause() {
        active = false
        stopPointer()
        prompt?.dismiss()
        page?.onPause()
        super.onPause()
    }

    override fun onStop() {
        page?.stopLoading()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, currentUrl)
        outState.putString(STATE_ADDRESS, address.text.toString().take(MAX_DRAFT_LENGTH))
        super.onSaveInstanceState(outState)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        prompt?.dismiss()
        releasePage()
        super.onDestroy()
    }

    private fun releasePage(rendererGone: Boolean = false) {
        val web = page ?: return
        stopPointer()
        page = null
        address.nextFocusDownId = View.NO_ID
        for (index in 0 until tools.childCount) tools.getChildAt(index).nextFocusDownId = View.NO_ID
        if (!rendererGone) web.stopLoading()
        web.setDownloadListener(null)
        content.removeView(web)
        web.destroy()
        updateControls()
    }

    private fun horizontalBar() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun LinearLayout.addControl(button: Button, params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(44), dp(44))) {
        params.marginStart = dp(4)
        addView(button, params)
    }

    private fun control(symbol: String?, label: Int, onClick: () -> Unit) = Button(this).apply {
        text = symbol ?: getString(label)
        if (symbol != null) {
            contentDescription = getString(label)
            tooltipText = getString(label)
        }
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextSize(if (symbol == null) 14f else 20f)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.GRAY, BACKGROUND, Color.WHITE),
        ))
        background = focusBackground(accentFill = true)
        backgroundTintList = null
        setOnClickListener { onClick() }
    }

    private fun focusBackground(accentFill: Boolean): StateListDrawable {
        fun shape(focused: Boolean) = GradientDrawable().apply {
            setColor(if (focused && accentFill) ACCENT else TOOLBAR)
            cornerRadius = dp(5).toFloat()
            if (focused) setStroke(dp(2), ACCENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(true))
            addState(intArrayOf(), shape(false))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val RESULT_LINK = "tv.orbit.shield.browser.LINK"
        const val RESULT_SESSION = "tv.orbit.shield.browser.SESSION"
        const val RESELECT_DOWNLOAD = "tv.orbit.shield.browser.RESELECT_DOWNLOAD"
        private const val STATE_PAGE = "browser.page"
        private const val STATE_ADDRESS = "browser.address"
        private const val MAX_DRAFT_LENGTH = 8192
        private const val BACKGROUND = 0xFF0A0A0A.toInt()
        private const val TOOLBAR = 0xFF242426.toInt()
        private const val ACCENT = 0xFFCCFF00.toInt()
    }
}
