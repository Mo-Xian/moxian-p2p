package com.cp12064.moxianp2p

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 通用 WebView 包装 加载 NAS 服务的 Web UI
 *
 * 用于没有内置原生客户端的服务兜底（Immich 首页 / Vaultwarden 后台 / AdGuard 仪表等）
 * 特性：
 *   - Cookie 持久化（各服务独立域 登录态常年保持）
 *   - 自签 HTTPS 证书支持（家用 NAS 常见自签）
 *   - JS + DOM storage 全开
 *   - 后退键先让 WebView 回退 没历史了再退出 Activity
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private var currentUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tv_title)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnRefresh = findViewById<ImageButton>(R.id.btn_refresh)
        val btnExternal = findViewById<ImageButton>(R.id.btn_external)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        val svc = NasService.findById(this, svcId) ?: run { finish(); return }
        currentUrl = svc.url
        tvTitle.text = svc.name

        // WebView 配置 尽量给全开
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false  // 允许 Jellyfin 等自动播放
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                url?.let { currentUrl = it }
            }
            // 家用 NAS 自签证书很常见 提示用户信任
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@WebViewActivity)
                    .setTitle("证书警告")
                    .setMessage("该站证书异常（自签或过期）。\n家用 NAS 常见 继续访问？\n\n${error.url}")
                    .setPositiveButton("继续") { _, _ -> handler.proceed() }
                    .setNegativeButton("取消") { _, _ -> handler.cancel() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) tvTitle.text = "${svc.name} · $title"
            }
        }

        webView.loadUrl(svc.url)

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        btnRefresh.setOnClickListener { webView.reload() }
        btnExternal.setOnClickListener {
            ServiceLauncher.openInExternalBrowser(this, currentUrl)
        }

        // 系统后退键同样先走 WebView 历史
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
