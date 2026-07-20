package com.bf1.admin.tool.ui.login

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.util.CookieHelper

/**
 * EA 登录入口 URL。
 * 注意：不能直接用 OAuth 授权端点，因为 OAuth 回调 test.pulse.ea.com 在 Android 上无法访问。
 * 改用常规登录页，cookie 提取由 onPageStarted 事件驱动。
 */
private const val LOGIN_URL = "https://www.ea.com/login"

/** EA App Desktop User-Agent，对应 EAappEmulater LoginWindow 中的 Settings.UserAgent */
private const val EA_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Origin/10.6.0.00000 EAApp/13.377.0.5890 Chrome/109.0.5414.120 Safari/537.36"

private const val TAG = "BF1Debug"

/**
 * 注入到 WebView 页面中的 JavaScript。
 * 对应 EAappEmulater LoginWindow 中的 AddScriptToExecuteOnDocumentCreatedAsync
 */
private const val INJECTED_JS = """
(function() {
    window.addEventListener('DOMContentLoaded', function() {
        var href = window.location.href;
        if (href.indexOf('pc.ea.com/login.html') !== -1) {
            if (window.EaBridge) {
                window.EaBridge.onRedirect(href);
            }
        }
    });
    if (window.location.href.indexOf('pc.ea.com/login.html') !== -1) {
        if (window.EaBridge) {
            window.EaBridge.onRedirect(window.location.href);
        }
    }
    window.open = function(url) { location.href = url; return null; };
    var links = document.querySelectorAll('a[target="_blank"]');
    for (var i = 0; i < links.length; i++) { links[i].target = '_self'; }
})();
"""

/**
 * JS Bridge：接收 WebView 中 JavaScript 回传的消息。
 * 对应 EAappEmulater 中的 WebMessageReceived 事件处理。
 */
class EaLoginBridge(private val onRedirect: (String) -> Unit) {
    @JavascriptInterface
    fun onRedirect(url: String) {
        onRedirect(url)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: LoginViewModel = viewModel()
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var extractionTriggered by remember { mutableStateOf(false) }
    var showingOTCMessage by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isPageLoading by remember { mutableStateOf(true) }
    var pageProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.loginSuccess.collect { onLoginSuccess() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("EA 登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            errorMsg?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        try {
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = EA_DESKTOP_UA

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        isPageLoading = newProgress < 100
                                        pageProgress = newProgress / 100f
                                    }
                                }

                                val bridge = EaLoginBridge { }
                                addJavascriptInterface(bridge, "EaBridge")

                            fun tryExtractCookies(wv: WebView?) {
                                if (extractionTriggered) return
                                
                                val manager = CookieManager.getInstance()
                                manager.flush() // 立刻同步缓存的 Cookie
                                
                                val cookieUrls = listOf(
                                    "https://accounts.ea.com/connect/auth",
                                    "https://accounts.ea.com",
                                    "https://signin.ea.com",
                                    "https://www.ea.com",
                                    "https://pc.ea.com",
                                    "https://ea.com",
                                    "https://test.pulse.ea.com"
                                )

                                val allCookies = cookieUrls
                                    .map { domain -> manager.getCookie(domain) }
                                    .filterNotNull()
                                    .joinToString("; ")

                                val result = CookieHelper.parseWebViewCookies(allCookies)
                                if (result != null) {
                                    extractionTriggered = true
                                    showingOTCMessage = false
                                    errorMsg = null
                                    val (remid, sid) = result
                                    wv?.loadUrl("about:blank")
                                    viewModel.loginWithCookiesFromWebView(
                                        rawCookies = "remid=$remid; sid=$sid"
                                    )
                                }
                            }

                            webViewClient = object : WebViewClient() {

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    view?.evaluateJavascript(INJECTED_JS, null)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val currentUrl = url ?: return

                                    if (currentUrl.contains("dynamicchallenge") ||
                                        currentUrl.contains("twofactor") ||
                                        currentUrl.contains("otc")) {
                                        if (!showingOTCMessage) {
                                            showingOTCMessage = true
                                            errorMsg = "请完成双因素验证，验证后将自动继续"
                                        }
                                        return
                                    }

                                    tryExtractCookies(view)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    if (reqUrl.startsWith("nucleus:rest") || reqUrl.contains("test.pulse.ea.com")) {
                                        tryExtractCookies(view)
                                    }
                                    return false
                                }
                                
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    tryExtractCookies(view)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    super.onReceivedError(view, errorCode, description, failingUrl)
                                    Log.e(TAG, "[WebView] Error: code=$errorCode desc=$description url=$failingUrl")
                                }
                            }

                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            evaluateJavascript("localStorage.clear(); sessionStorage.clear();", null)

                            loadUrl(LOGIN_URL)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[WebView] Failed to create WebView", e)
                        errorMsg = "WebView 创建失败: ${e.message}"
                        WebView(context)
                    }
                }
            )

            if (isLoading || (isPageLoading && !extractionTriggered)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isLoading) "正在获取并验证 Session..." else "加载中...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
}
