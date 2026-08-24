package com.topjohnwu.magisk.ui.webui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.core.cmp
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebUIActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID)
        if (moduleId == null || !MODULE_ID.matches(moduleId)) {
            finish()
            return
        }
        val moduleName = intent.getStringExtra(EXTRA_MODULE_NAME).orEmpty().ifBlank { moduleId }
        title = moduleName

        val progress = ProgressBar(this)
        setContentView(progress)

        try {
            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.setSupportZoom(false)
            }
        } catch (_: Throwable) {
            showWebViewUnavailable()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        lifecycleScope.launch {
            val webRoot = withContext(Dispatchers.IO) {
                if (!Info.isRooted || !Info.env.isActive) null
                else prepareWebRoot(moduleId)
            }
            if (webRoot == null) {
                toast(CoreR.string.webui_root_required, Toast.LENGTH_SHORT)
                finish()
            } else {
                setupWebView(moduleId, moduleName, webRoot)
            }
        }
    }

    private fun setupWebView(moduleId: String, moduleName: String, webRoot: File) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WEB_DOMAIN)
            .addPathHandler("/", RootFsPathHandler(webRoot))
            .build()

        val bridge = WebViewInterface(this, webView, moduleId, moduleName, lifecycleScope)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ) = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == WEB_DOMAIN) return false
                return try {
                    startActivity(externalViewIntent(request.url))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }
        }
        webView.addJavascriptInterface(bridge, "ksu")
        setContentView(webView)
        webView.loadUrl("https://$WEB_DOMAIN/index.html")
    }

    private fun prepareWebRoot(moduleId: String): File? {
        val source = File(Const.MODULE_PATH, "$moduleId/webroot")
        val target = File(cacheDir, "webui/$moduleId")
        val command = """
            rm -rf ${shellQuote(target.path)} &&
            mkdir -p ${shellQuote(target.path)} &&
            cp -r ${shellQuote(source.path)}/. ${shellQuote(target.path)}/ &&
            chmod -R a+rX ${shellQuote(target.path)}
        """.trimIndent()
        val result = runCatching { Shell.cmd(command).exec() }.getOrNull() ?: return null
        return target.takeIf { result.code == 0 && File(it, "index.html").isFile }
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun showWebViewUnavailable() {
        android.app.AlertDialog.Builder(this)
            .setTitle(CoreR.string.webui_webview_required)
            .setMessage(CoreR.string.webui_webview_required_summary)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(CoreR.string.webui_install_webview) { _, _ ->
                val market = externalViewIntent(
                    Uri.parse("market://details?id=com.google.android.webview")
                )
                val browser = externalViewIntent(
                    Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")
                )
                try { startActivity(market) } catch (_: ActivityNotFoundException) { startActivity(browser) }
            }
            .setOnDismissListener { if (isFinishing.not()) finish() }
            .show()
    }

    private fun externalViewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("ksu")
            webView.stopLoading()
        }
        super.onDestroy()
    }

    companion object {
        private const val WEB_DOMAIN = "mui.kernelsu.org"
        private val MODULE_ID = Regex("[A-Za-z0-9._-]+")
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_MODULE_NAME = "module_name"

        fun intent(context: android.content.Context, moduleId: String, moduleName: String) =
            Intent().setComponent(WebUIActivity::class.java.cmp(context.packageName)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_MODULE_ID, moduleId)
                putExtra(EXTRA_MODULE_NAME, moduleName)
            }
    }
}
