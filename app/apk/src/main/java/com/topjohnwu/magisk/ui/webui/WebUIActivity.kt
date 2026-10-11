package com.topjohnwu.magisk.ui.webui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
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
    private var webView: WebView? = null
    private var bridge: WebViewInterface? = null
    private val preparation = WebCommandSession()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID)
        if (moduleId == null || !WebUiCommandBuilder.isValidModuleId(moduleId)) {
            finish()
            return
        }
        val moduleName = intent.getStringExtra(EXTRA_MODULE_NAME).orEmpty().ifBlank { moduleId }
        title = moduleName

        val progress = ProgressBar(this)
        setContentView(progress)

        try {
            val view = WebView(this)
            webView = view
            view.apply {
                settings.userAgentString = WebUiUserAgent.normalize(settings.userAgentString)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.setSupportZoom(false)
            }
        } catch (_: Throwable) {
            releaseWebView()
            showWebViewUnavailable()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view?.canGoBack() == true) view.goBack() else finish()
            }
        })

        lifecycleScope.launch {
            val webRoot = withContext(Dispatchers.IO) {
                runCatching {

                    val shell = Shell.getShell()
                    if (!shell.isRoot || !Info.env.isActive) null
                    else prepareWebRoot(moduleId)
                }.getOrNull()
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
        val webView = webView ?: return
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WEB_DOMAIN)
            .addPathHandler("/", RootFsPathHandler(webRoot))
            .build()

        val bridge = WebViewInterface(this, webView, moduleId, moduleName, lifecycleScope).also { bridge = it }
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (this@WebUIActivity.webView === view) {
                    releaseWebView()
                    toast(CoreR.string.webui_renderer_stopped, Toast.LENGTH_LONG)
                    finish()
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ) = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (WebUiCommandBuilder.isInternalUrl(
                        request.url.scheme,
                        request.url.host,
                        request.url.port,
                    )
                ) return false
                if (request.url.scheme != "http" && request.url.scheme != "https") return true
                openExternal(request.url)
                return true
            }
        }
        webView.addJavascriptInterface(bridge, "ksu")
        setContentView(webView)
        webView.loadUrl("https://$WEB_DOMAIN/index.html")
    }

    private fun prepareWebRoot(moduleId: String): File? {
        if (!WebUiCommandBuilder.isValidModuleId(moduleId)) return null
        val source = File(Const.MODULE_PATH, "$moduleId/webroot")
        val targetRoot = File(cacheDir, "webui").canonicalFile
        val target = File(targetRoot, moduleId)
        if (!target.canonicalPath.startsWith(targetRoot.path + File.separator)) return null
        val command = """
            rm -rf ${shellQuote(target.path)} &&
            mkdir -p ${shellQuote(target.path)} &&
            cp -r ${shellQuote(source.path)}/. ${shellQuote(target.path)}/ &&
            chmod -R a+rX ${shellQuote(target.path)}
        """.trimIndent()
        val result = runCatching { preparation.run(command) }.getOrNull() ?: return null
        return target.takeIf { result.code == 0 && File(it, "index.html").isFile }
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun showWebViewUnavailable() {
        android.app.AlertDialog.Builder(this)
            .setTitle(CoreR.string.webui_webview_required)
            .setMessage(CoreR.string.webui_webview_required_summary)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(CoreR.string.webui_install_webview) { _, _ ->
                if (!openExternal("market://details?id=com.google.android.webview".toUri()) &&
                    !openExternal("https://play.google.com/store/apps/details?id=com.google.android.webview".toUri())) {
                    toast(CoreR.string.webui_no_browser, Toast.LENGTH_LONG)
                }
            }
            .setOnDismissListener { if (isFinishing.not()) finish() }
            .show()
    }

    private fun externalViewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    private fun openExternal(uri: Uri): Boolean = try {
        startActivity(externalViewIntent(uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun releaseWebView() {
        bridge?.close()
        bridge = null
        val view = webView ?: return
        webView = null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
    }

    override fun onPause() { webView?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); webView?.onResume() }

    override fun onDestroy() {
        preparation.close()
        releaseWebView()
        super.onDestroy()
    }

    companion object {
        private const val WEB_DOMAIN = "appassets.androidplatform.net"
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
