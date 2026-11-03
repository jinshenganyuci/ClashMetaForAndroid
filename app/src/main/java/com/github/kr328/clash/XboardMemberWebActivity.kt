package com.github.kr328.clash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardApi
import com.github.kr328.clash.xboard.XboardSessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class XboardMemberWebActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val account by lazy { XboardAccount(this) }
    private lateinit var section: String
    private var web: WebView? = null
    private var boundBearer: String? = null
    private var ready = false
    private var loading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra("section").orEmpty()
        val title = titles[section] ?: return finish()
        setContentView(R.layout.xb_member_web)
        findViewById<TextView>(R.id.xb_member_web_title).setText(title)
        val root = findViewById<View>(R.id.xb_member_web_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.xb_member_web_back).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.xb_member_web_retry).setOnClickListener { open(retry = true) }
        findViewById<View>(R.id.xb_member_web_signin).setOnClickListener {
            startActivity(Intent(this, XboardAccountActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) open()
    }

    override fun onPause() {
        loading?.cancel()
        web?.apply { isVisible = false; onPause() }
        super.onPause()
    }

    private fun open(retry: Boolean = false) {
        loading?.cancel()
        loading = scope.launch {
            var bearer: String? = null
            try {
                status(R.string.xb_web_loading)
                bearer = account.cached()?.bearer
                if (bearer == null) {
                    disposePage()
                    status(R.string.xb_web_signed_out, signIn = true)
                    return@launch
                }
                if (!retry && bearer == boundBearer && ready) {
                    web?.onResume()
                    showPage()
                    return@launch
                }
                disposePage()
                val session = MemberSessions.get(bearer, section)
                val html = withContext(Dispatchers.IO) { bootstrapHtml() }
                if (account.cached()?.bearer != bearer) {
                    status(R.string.xb_web_signed_out, signIn = true)
                    return@launch
                }
                boundBearer = bearer
                val page = newPage(session)
                page.loadDataWithBaseURL(SITE + "/", html, "text/html", "utf-8", null)
                repeat(120) {
                    delay(250)
                    if (web !== page) return@launch
                    page.evaluateJavascript("document.documentElement.dataset.memberReady") { state ->
                        if (web === page && !isFinishing && state == "\"yes\"" && !ready) {
                            ready = true
                            page.clearHistory()
                            showPage()
                        }
                    }
                    if (ready) return@launch
                }
                disposePage()
                status(R.string.xb_web_unavailable, retry = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: XboardSessionExpiredException) {
                bearer?.let { account.clearExpiredSession(it) }
                disposePage()
                status(R.string.xb_expired_session, signIn = true)
            } catch (_: Exception) {
                disposePage()
                status(R.string.xb_web_unavailable, retry = true)
            }
        }
    }

    private fun status(label: Int, retry: Boolean = false, signIn: Boolean = false) {
        findViewById<View>(R.id.xb_member_web_placeholder).isVisible = true
        findViewById<TextView>(R.id.xb_member_web_state).setText(label)
        findViewById<View>(R.id.xb_member_web_retry).isVisible = retry
        findViewById<View>(R.id.xb_member_web_signin).isVisible = signIn
        web?.isVisible = false
    }

    private fun showPage() {
        findViewById<View>(R.id.xb_member_web_placeholder).isVisible = false
        web?.isVisible = true
    }

    private fun bootstrapHtml(): String {
        val conn = URL(SITE + "/").openConnection() as HttpsURLConnection
        val html = try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 12_000
            conn.useCaches = false
            if (conn.responseCode != 200) throw IOException()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (text.length + n > 256 * 1024) throw IOException()
                    text.append(buffer, 0, n)
                }
                text.toString()
            }
        } finally { conn.disconnect() }
        val nonce = java.util.UUID.randomUUID().toString()
        val trustedHtml = Regex("<script(?=[\\s>])", RegexOption.IGNORE_CASE).replace(html, "<script nonce=\"$nonce\"")
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(trustedHtml) ?: throw IOException()
        val guard = resources.openRawResource(R.raw.xb_member_guard).bufferedReader().use { it.readText() }
        val policy = "default-src 'none'; script-src 'nonce-$nonce' 'unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; " +
            "connect-src 'self'; frame-src 'none'; child-src 'none'; worker-src 'none'; " +
            "object-src 'none'; form-action 'none'; base-uri 'none'"
        val bootstrap = "<meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" +
            "<meta name=\"referrer\" content=\"no-referrer\">" +
            "<script nonce=\"$nonce\" data-section=\"$section\" data-empty=\"" + getString(R.string.xb_web_no_docs) + "\">$guard</script>"
        return trustedHtml.replaceRange(head.range.last + 1, head.range.last + 1, bootstrap)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newPage(webSession: String): WebView {
        val page = WebView(this)
        web = page
        page.id = R.id.xb_member_web_page
        page.isVisible = false
        page.isSaveEnabled = false
        page.settings.apply {
            javaScriptEnabled = true
            // The theme uses window-scoped in-memory storage supplied by our bootstrap.
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            @Suppress("DEPRECATION")
            saveFormData = false
        }
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
        var exchanged = false
        page.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (!onSite(uri) || request.isForMainFrame) return blocked()
                val path = uri.encodedPath ?: return blocked()
                if (path == "/api/v1/passport/auth/token2Login" && request.method == "GET") {
                    synchronized(this) {
                        if (exchanged || uri.getQueryParameter("verify") != "member-window" ||
                            uri.getQueryParameter("redirect") != "/$section") return blocked()
                        exchanged = true
                    }
                    return json(JSONObject().put("data", JSONObject().put("auth_data", webSession)).toString())
                }
                if (path == "/api/v1/user/order/getPaymentMethod" && request.method == "GET" && section == ORDERS) {
                    return json("{\"data\":[]}")
                }
                if (path.startsWith("/api/")) {
                    return if (MemberWebPolicy.api(section, request.method, path)) null else blocked()
                }
                if (request.method != "GET" || !MemberWebPolicy.asset(path)) return blocked()
                return null
            }

            @Deprecated("For Android 5 and 6")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = navigate(Uri.parse(url), false)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.isForMainFrame || navigate(request.url, request.hasGesture())

            private fun navigate(uri: Uri, gesture: Boolean): Boolean {
                if (onSite(uri) && uri.path == "/__member_session_expired") {
                    scope.launch {
                        MemberSessions.invalidate(webSession)
                        if (web === page) {
                            disposePage()
                            status(R.string.xb_web_unavailable, retry = true)
                        }
                    }
                } else if (onSite(uri) && uri.path == "/" && MemberWebPolicy.page(section, uri.fragment.orEmpty())) {
                    return false
                } else if (gesture && section in setOf(PROFILE, KNOWLEDGE) && MemberWebPolicy.telegram(uri)) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: android.content.ActivityNotFoundException) { }
                }
                return true
            }
        }
        findViewById<FrameLayout>(R.id.xb_member_web_frame).addView(page)
        return page
    }

    @Deprecated("Android back dispatch compatibility")
    override fun onBackPressed() {
        val page = web
        if (ready && page != null) {
            page.evaluateJavascript("window.memberGoBack && window.memberGoBack()") { handled ->
                if (handled != "true" && web === page && !isFinishing) finish()
            }
        } else super.onBackPressed()
    }

    private fun disposePage() {
        ready = false
        boundBearer = null
        web?.apply {
            stopLoading()
            (parent as? FrameLayout)?.removeView(this)
            clearHistory()
            destroy()
        }
        web = null
    }

    override fun onDestroy() {
        scope.cancel()
        disposePage()
        super.onDestroy()
    }

    companion object {
        const val KNOWLEDGE = "knowledge"
        const val ORDERS = "order"
        const val INVITE = "invite"
        const val TICKETS = "ticket"
        const val PROFILE = "profile"
        private const val SITE = "https://ikun.iil.im"
        private val titles = mapOf(KNOWLEDGE to R.string.xb_web_knowledge, ORDERS to R.string.xb_web_order,
            INVITE to R.string.xb_web_invite, TICKETS to R.string.xb_web_ticket, PROFILE to R.string.xb_web_profile)

        fun intent(context: Context, section: String) = Intent(context, XboardMemberWebActivity::class.java).putExtra("section", section)
        private fun onSite(uri: Uri) = uri.scheme == "https" && uri.host.equals("ikun.iil.im", true) &&
            uri.port in setOf(-1, 443) && uri.userInfo == null
        private fun json(value: String) = WebResourceResponse("application/json", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), value.byteInputStream())
        private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
    }
}

private object MemberSessions {
    private val lock = Mutex()
    private var owner: String? = null
    private var session: String? = null

    suspend fun get(bearer: String, section: String): String = lock.withLock {
        val cached = session
        if (owner == bearer && cached != null) return@withLock cached
        session = null
        owner = bearer
        XboardApi.memberWebSession(bearer, section).also { session = it }
    }

    suspend fun invalidate(value: String) = lock.withLock {
        if (session == value) { session = null; owner = null }
    }
}

private object MemberWebPolicy {
    private val baseGet = setOf("guest/comm/config", "user/comm/config", "user/info", "user/getSubscribe")
    private val read = mapOf(
        "knowledge" to setOf("user/knowledge/fetch", "user/knowledge/getCategory"),
        "order" to setOf("user/order/fetch", "user/order/detail", "user/order/check"),
        "invite" to setOf("user/invite/fetch", "user/invite/details", "user/invite/save"),
        "ticket" to setOf("user/ticket/fetch"),
        "profile" to setOf("user/telegram/getBotInfo", "user/resetSecurity"),
    )
    private val write = mapOf(
        "invite" to setOf("user/transfer", "user/ticket/withdraw"),
        "ticket" to setOf("user/ticket/save", "user/ticket/reply", "user/ticket/close"),
        "profile" to setOf("user/update", "user/changePassword"),
    )

    fun api(section: String, method: String, path: String): Boolean {
        if (!path.startsWith("/api/v1/")) return false
        val endpoint = path.removePrefix("/api/v1/")
        return when (method) {
            "GET" -> endpoint in baseGet || read[section]?.contains(endpoint) == true
            "POST" -> write[section]?.contains(endpoint) == true
            else -> false
        }
    }

    fun asset(path: String): Boolean {
        if (path.contains('%') || path.contains("..") || path.contains('\\')) return false
        if (path.startsWith("/assets/") || path.startsWith("/theme/") || path.startsWith("/storage/")) {
            return path.substringAfterLast('.').lowercase(Locale.ROOT) in setOf(
                "js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf",
            )
        }
        return false
    }

    fun page(section: String, fragment: String): Boolean {
        val path = fragment.substringBefore('?')
        return path == "/$section" || section == "order" && path.matches(Regex("/order/[A-Za-z0-9_-]+")) ||
            section == "ticket" && path.matches(Regex("/ticket/[0-9]+"))
    }

    fun telegram(uri: Uri) = uri.scheme == "https" && uri.host == "t.me" &&
        uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.path.orEmpty().matches(Regex("/[+A-Za-z0-9_][A-Za-z0-9_-]{2,63}"))
}
