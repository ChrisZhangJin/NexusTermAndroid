package com.nexusterm.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.util.Collections
import org.json.JSONObject

/**
 * 「浏览」窗口：一个全屏 WebView，加载打包在 assets/viewer 里的页面（viewer.js 顶部注释是
 * 与本类的完整约定）。文档字节**不经过 addJavascriptInterface**（那会暴露给包括 HTML
 * 沙箱 iframe 在内的每个 frame）：页面用 fetch 向假 origin `https://nxviewer.local` 取，
 * 由 [intercept] 应答。
 *
 * 拦截表（只有 origin = https://nxviewer.local 的请求会被应答，其它 origin 原样放行，
 * 以便 Markdown / HTML 里的远程图片能加载）：
 *   /viewer/viewer.html（主框架）  → assets/viewer/viewer.html
 *   /viewer/ 下其它文件（子资源）  → assets/viewer/ 下同名文件，mime 按后缀，nosniff
 *   /doc-meta                      → JSON {name, source, kind, size}
 *   /doc                           → 临时文件字节，永远 application/octet-stream + nosniff
 *   /doc-html[?js=1]（仅 iframe）  → 仅 kind=html，带 CSP / Referrer-Policy / nosniff / no-store
 *   其它                           → 404
 * 除入口页以外的路径都拒绝作为主框架导航被加载：否则哪怕页面里混进一个 `<a href="/doc-html">`
 * 也会让 HTML 文档以 viewer 的 origin 顶层渲染（逃出沙箱）。
 */
class ViewerActivity : Activity() {

    private var docId: String? = null
    private var doc: ViewerDoc? = null
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        docId = intent.getStringExtra(ViewerLauncher.EXTRA_DOC_ID)
        val d = ViewerDocStore.get(docId)
        if (d == null) {
            // 进程被杀后系统恢复到这个 Activity：表是空的，临时文件也已被清掉
            finish()
            return
        }
        doc = d

        // 与 viewer.css 的 --bg / --bg-panel 同色：状态栏接上页面的顶栏，旋转/加载时不闪白
        window.setBackgroundDrawable(ColorDrawable(BG))
        window.statusBarColor = BG_PANEL
        window.navigationBarColor = BG

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val wv = WebView(this)
        webView = wv
        wv.setBackgroundColor(BG)
        wv.settings.apply {
            javaScriptEnabled = true // 页面本身是 JS 应用
            domStorageEnabled = true // nx.viewer.* 偏好存在假 origin 的 localStorage 里
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 安全浏览会把 URL 发给 Google 校验，国内设备上既不可用又泄露浏览的地址
            safeBrowsingEnabled = false
            setSupportMultipleWindows(true) // 让 iframe 的 window.open / target=_blank 走 onCreateWindow，而不是被静默忽略
            javaScriptCanOpenWindowsAutomatically = false // 无用户手势的弹窗直接丢弃
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION") saveFormData = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        wv.webViewClient = ViewerWebViewClient()
        wv.webChromeClient = ViewerChromeClient()

        setContentView(
            FrameLayout(this).also {
                it.setBackgroundColor(BG)
                it.addView(wv, FrameLayout.LayoutParams(-1, -1))
            },
        )
        // 旋转由 configChanges 接管，Activity 不重建，页面状态（滚动/缩放/模式）原样保留
        wv.loadUrl("https://$VIEWER_HOST$VIEWER_ENTRY_PATH")
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        // 切到后台就停掉音视频（WebView.onPause 会暂停 media 与 JS 计时器）
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
        // 只有真正退出才清文档；因配置变化被系统重建时 isFinishing=false，新实例还要用
        if (isFinishing) docId?.let(ViewerDocStore::remove)
        super.onDestroy()
    }

    // ───────────────────────── 外部链接 ─────────────────────────

    /** 只放行 http / https / mailto；其它协议（javascript: data: file: intent: …）一律丢弃。 */
    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "mailto") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // 没有能处理的应用：什么也不做
        }
    }

    private fun isViewerOrigin(uri: Uri): Boolean =
        uri.host.equals(VIEWER_HOST, ignoreCase = true)

    private inner class ViewerWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            intercept(request)

        /**
         * 页面里**所有**离开 viewer 入口的导航都在这里收口：外链交给系统，其余丢弃。
         * 绝不让 viewer WebView 自己跳走（否则下一次 /doc 之类的请求就会落到别的 origin 上）。
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isViewerOrigin(uri)) {
                // 主框架只能停在入口页；子框架只能去 /doc-html（iframe 自己的 src）
                val ok = if (request.isForMainFrame) {
                    uri.scheme == "https" && uri.path == VIEWER_ENTRY_PATH
                } else {
                    uri.scheme == "https" && uri.path == "/doc-html"
                }
                return !ok
            }
            if (request.isForMainFrame) openExternal(uri)
            return true
        }

        // 渲染进程被系统杀掉（内存紧张）：不处理会连带崩掉整个 app
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            finish()
            return true
        }
    }

    private inner class ViewerChromeClient : WebChromeClient() {

        /**
         * HTML 文档的 iframe 里的 target=_blank / window.open。不能让它在 viewer 里开新页面：
         * 先给它一个一次性的、被锁死的 WebView，只为拿到目标 URL —— http/https 交给系统浏览器，
         * 其余丢弃，然后立刻销毁。这个临时 WebView 不加载任何内容（所有请求都返回空）。
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(this@ViewerActivity)
            popup.settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
            }
            var handled = false
            fun handle(uri: Uri) {
                if (handled) return
                handled = true
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") openExternal(uri)
                // destroy 不能在 WebView 自己的回调栈里直接做
                mainHandler.post { popup.stopLoading(); popup.destroy() }
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    handle(request.url)
                    return true
                }

                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // 兜底：有的 WebView 版本首个导航不走 shouldOverrideUrlLoading
                    if (request.isForMainFrame) {
                        val uri = request.url
                        mainHandler.post { handle(uri) }
                    }
                    return emptyResponse()
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    // ───────────────────────── 请求拦截 ─────────────────────────

    private fun emptyResponse(status: Int = 404, reason: String = "Not Found"): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", status, reason, baseHeaders(), ByteArrayInputStream(ByteArray(0)))

    private fun baseHeaders(): MutableMap<String, String> = mutableMapOf(
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
    )

    /** 非 viewer origin 返回 null（放行）；viewer origin 一律在这里答复，拿不准就 404。 */
    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!isViewerOrigin(uri)) return null
        val d = doc
        if (d == null || uri.scheme != "https" || uri.port != -1 || request.method != "GET") return emptyResponse()
        val path = uri.path ?: return emptyResponse()
        val main = request.isForMainFrame
        return try {
            when {
                // 主框架只许加载入口页
                main && path != VIEWER_ENTRY_PATH -> emptyResponse()
                path.startsWith("/viewer/") -> asset(path)
                path == "/doc-meta" -> docMeta(d)
                path == "/doc" -> docBytes(d)
                path == "/doc-html" -> docHtml(d, allowJs = uri.getQueryParameter("js") == "1")
                else -> emptyResponse()
            }
        } catch (_: IOException) {
            emptyResponse() // 临时文件已被清掉（Activity 正在销毁）等
        }
    }

    private fun asset(path: String): WebResourceResponse {
        val rel = path.removePrefix("/viewer/")
        // uri.path 已经做过 %xx 解码，所以 %2e%2e 也会在这里现形
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') }) {
            return emptyResponse()
        }
        val stream = try {
            assets.open("viewer/$rel")
        } catch (_: IOException) {
            return emptyResponse() // 不存在，或是个目录
        }
        val mime = assetMime(rel)
        return WebResourceResponse(mime, "utf-8", 200, "OK", baseHeaders(), stream)
    }

    private fun docMeta(d: ViewerDoc): WebResourceResponse {
        val json = JSONObject()
            .put("name", d.name)
            .put("source", d.source)
            .put("kind", d.kind.wire)
            .put("size", d.file.length())
        return WebResourceResponse(
            "application/json", "utf-8", 200, "OK", baseHeaders(),
            ByteArrayInputStream(json.toString().toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * 原始字节，永远 octet-stream + nosniff：即便被人当成页面 URL 访问也不会被渲染成同源页面。
     * 页面是 fetch → arrayBuffer → Blob URL 来播放音视频的，所以不需要 Range（拖动进度条靠 Blob）。
     */
    private fun docBytes(d: ViewerDoc): WebResourceResponse {
        val headers = baseHeaders()
        headers["Content-Length"] = d.file.length().toString()
        return WebResourceResponse("application/octet-stream", null, 200, "OK", headers, FileInputStream(d.file))
    }

    /** 只有 iframe（非主框架）能到这里；非 HTML 文档没有这个入口。 */
    private fun docHtml(d: ViewerDoc, allowJs: Boolean): WebResourceResponse {
        if (d.kind != ViewableKind.HTML) return emptyResponse()
        val headers = baseHeaders()
        headers["Content-Security-Policy"] = htmlCsp(allowJs)
        // 合法 UTF-8 才声明；否则不写 charset，由页面自己的 <meta charset> 决定（GBK 老页面）
        val encoding = if (d.htmlIsUtf8) "utf-8" else null
        // iframe 里的普通链接（没写 target）不会走 shouldOverrideUrlLoading，parent 的
        // frame-src 又不让它在 iframe 里打开；给文档塞一个 <base target="_blank">，
        // 点链接就变成带手势的新窗口请求，由 onCreateWindow 交给系统浏览器。
        val input = FileInputStream(d.file)
        val prefix = ByteArray(BASE_PREFIX_BYTES)
        var n = 0
        while (n < prefix.size) {
            val r = input.read(prefix, n, prefix.size - n)
            if (r < 0) break
            n += r
        }
        val at = baseTagInsertOffset(prefix, n)
        val stream = SequenceInputStream(
            Collections.enumeration(
                listOf(
                    ByteArrayInputStream(prefix, 0, at),
                    ByteArrayInputStream(BASE_TARGET_TAG),
                    ByteArrayInputStream(prefix, at, n - at),
                    input,
                ),
            ),
        )
        headers["Content-Length"] = (d.file.length() + BASE_TARGET_TAG.size).toString()
        return WebResourceResponse("text/html", encoding, 200, "OK", headers, stream)
    }

    private companion object {
        val BG = Color.parseColor("#0B1A15")
        val BG_PANEL = Color.parseColor("#0F2A23")
    }
}
