package com.apkdomusico.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

val DefaultBookmarks = listOf(
    Bookmark("YouTube", "https://m.youtube.com"),
    Bookmark("Hotmart", "https://consumer.hotmart.com"),
    Bookmark("Kiwify", "https://dashboard.kiwify.com.br"),
    Bookmark("Google", "https://www.google.com"),
)

/**
 * Navegador de aulas. O WebView vive enquanto a Activity existir, para o vídeo
 * não recomeçar quando o músico troca de aba.
 */
class BrowserState(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("navegador", Context.MODE_PRIVATE)

    var url by mutableStateOf(prefs.getString("ultimaUrl", null) ?: "")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var bookmarks by mutableStateOf(loadBookmarks())
        private set
    var fullscreenView by mutableStateOf<View?>(null)
        private set

    /** Já abriu a aba Aulas alguma vez (o WebView existe e está carregado). */
    var started by mutableStateOf(false)
        private set

    /** Onde a tela Aulas reservou espaço para a página, em pixels na tela. */
    var contentBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

    /** Mini player flutuante: o vídeo continua visível e tocando nas outras abas. */
    var mini by mutableStateOf(false)

    /** Janela aberta pela página (login em popup, por exemplo). */
    var popup by mutableStateOf<WebView?>(null)
        private set

    /** A página terminou de carregar mas não mostrou nada. */
    var blankSuspected by mutableStateOf(false)
        private set
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @get:SuppressLint("SetJavaScriptEnabled")
    val webView: WebView by lazy {
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            configure(this)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        // O Google não permite login dentro de WebView: abre essa etapa no navegador seguro.
                        if (uri.host == "accounts.google.com") {
                            openSecure(uri.toString())
                            return true
                        }
                        return false
                    }
                    // intent://, mailto:, apps de pagamento etc.
                    return try {
                        val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        else Intent(Intent.ACTION_VIEW, uri)
                        intent.addCategory(Intent.CATEGORY_BROWSABLE)
                        intent.component = null
                        intent.selector = null
                        activity.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    blankSuspected = false
                    updateNav(url)
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    // O processo da página caiu (falta de memória, por exemplo): recarrega em vez de fechar o app.
                    blankSuspected = true
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    updateNav(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    updateNav(url)
                    CookieManager.getInstance().flush()
                    checkBlankLater(view, url)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@BrowserState.progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, t: String?) {
                    this@BrowserState.title = t ?: ""
                }

                // Vídeos com DRM (Widevine), comuns em áreas de membros. Só esse recurso é liberado.
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenView = view
                    fullscreenCallback = callback
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }

                // Páginas que abrem o login numa janela nova (popup): mostramos essa janela por cima,
                // mantendo a ligação com a página que a abriu, para o login voltar para ela.
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    val child = WebView(activity)
                    configure(child)
                    child.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            return scheme != "http" && scheme != "https"
                        }
                    }
                    child.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView) {
                            closePopup()
                        }
                    }
                    popup?.destroy()
                    popup = child
                    (resultMsg.obj as WebView.WebViewTransport).webView = child
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    closePopup()
                }
            }
            // Sem DownloadListener: o app não baixa vídeos nem arquivos das aulas.
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    fun closePopup() {
        popup?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        popup = null
        canGoBack = webView.canGoBack()
    }

    private fun checkBlankLater(view: WebView, finishedUrl: String) {
        view.postDelayed({
            if (view.url != finishedUrl) return@postDelayed
            view.evaluateJavascript(
                "(function(){var b=document.body;if(!b)return false;" +
                    "if(b.innerText&&b.innerText.trim().length>0)return true;" +
                    "return !!document.querySelector('video,iframe,img,canvas,svg,input');})()",
            ) { result -> blankSuspected = result == "false" }
        }, 5000)
    }

    /** Diz se algum vídeo ou áudio da página está tocando. */
    fun checkPlaying(callback: (Boolean) -> Unit) {
        if (!started) {
            callback(false)
            return
        }
        webView.evaluateJavascript(
            "(function(){return Array.from(document.querySelectorAll('video,audio')).some(function(m){return !m.paused&&!m.ended;});})()",
        ) { callback(it == "true") }
    }

    fun pauseMedia() {
        webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});", null)
    }

    private fun updateNav(current: String) {
        url = current
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
        if (current.startsWith("http")) prefs.edit().putString("ultimaUrl", current).apply()
    }

    fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains('.') && !text.contains(' ') -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        url = target
        webView.loadUrl(target)
    }

    fun ensureLoaded() {
        started = true
        if (webView.url == null) load(url.ifBlank { DefaultBookmarks.first().url })
    }

    fun back() {
        if (popup != null) closePopup() else if (webView.canGoBack()) webView.goBack()
    }
    fun forward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() = webView.reload()

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
    }

    /** Abre no navegador seguro do sistema (Custom Tabs): para logins que bloqueiam WebView. */
    fun openSecure(target: String = url) {
        if (target.isBlank()) return
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(target))
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }

    fun isBookmarked(u: String) = bookmarks.any { it.url == u }

    fun toggleBookmark() {
        val current = url
        if (current.isBlank()) return
        bookmarks = if (isBookmarked(current)) bookmarks.filterNot { it.url == current }
        else bookmarks + Bookmark(title.ifBlank { Uri.parse(current).host ?: current }.take(40), current)
        saveBookmarks()
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks = bookmarks - b
        saveBookmarks()
    }

    private fun loadBookmarks(): List<Bookmark> {
        val raw = prefs.getString("favoritos", null) ?: return DefaultBookmarks
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Bookmark(o.getString("t"), o.getString("u")) } }
        } catch (e: Exception) {
            DefaultBookmarks
        }
    }

    private fun saveBookmarks() {
        val arr = JSONArray()
        bookmarks.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs.edit().putString("favoritos", arr.toString()).apply()
    }

    fun onPause() {
        webView.onPause()
    }

    fun onResume() {
        webView.onResume()
    }
}
