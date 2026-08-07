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
 * EA 登录入口 URL —— 改用 Juno (EA app) 授权 URL。
 * WebView 走 JUNO_PC_CLIENT OAuth 流程：用户登录后 EA 重定向到
 * qrc:///html/login_successful.html?code=XXX，同时下发 remid/sid cookie。
 * 一次登录同时拿到 remid/sid（session 管理）和 authorization_code（换 refresh_token 查 PID）。
 */

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
    val junoUrl = viewModel.junoAuthParams.authUrl
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

                            /**
                             * 对应 EAappEmulater SaveEaCookiesAsync：cookie 写入 WebView 存储
                             * 可能不是瞬间完成的，重试最多 [maxRetries] 次，每次间隔 [delayMs]ms。
                             */
                            fun tryExtractCookies(
                                wv: WebView?,
                                junoCode: String? = null,
                                maxRetries: Int = 10,
                                delayMs: Long = 200
                            ) {
                                val caller = if (junoCode != null) "Juno(qrc)" else "LEGACY"
                                Log.d(TAG, "[WebView] ▶ tryExtractCookies called by=$caller extractionTriggered=$extractionTriggered junoCode=${junoCode?.take(20)}...")

                                if (extractionTriggered) {
                                    Log.d(TAG, "[WebView] ◼ tryExtractCookies SKIP — already triggered")
                                    return
                                }

                                val manager = CookieManager.getInstance()

                                val cookieUrls = listOf(
                                    "https://accounts.ea.com/connect/auth",
                                    "https://accounts.ea.com",
                                    "https://signin.ea.com",
                                    "https://www.ea.com",
                                    "https://pc.ea.com",
                                    "https://ea.com",
                                    "https://test.pulse.ea.com"
                                )

                                fun attempt(remaining: Int) {
                                    manager.flush()

                                    val allCookies = cookieUrls
                                        .map { domain -> domain to manager.getCookie(domain) }
                                        .filter { it.second != null }

                                    Log.d(TAG, "[WebView] extract attempt #${maxRetries - remaining} remaining=$remaining domainsWithCookies=${allCookies.map { it.first }}")

                                    val joinedCookies = allCookies
                                        .joinToString("; ") { it.second!! }

                                    val result = CookieHelper.parseWebViewCookies(joinedCookies)
                                    if (result != null) {
                                        Log.d(TAG, "[WebView] ✔ cookies FOUND — remid=${result.first.take(8)}... sid=${result.second.take(8)}...")
                                        extractionTriggered = true
                                        showingOTCMessage = false
                                        errorMsg = null
                                        wv?.loadUrl("about:blank")
                                        val (remid, sid) = result
                                        if (junoCode != null) {
                                            Log.d(TAG, "[WebView] → onJunoWebViewLogin (code=${junoCode.take(20)}...)")
                                            viewModel.onJunoWebViewLogin(
                                                code = junoCode,
                                                rawCookies = "remid=$remid; sid=$sid"
                                            )
                                        } else {
                                            Log.w(TAG, "[WebView] → cookies found but no junoCode — login abandoned")
                                        }
                                    } else if (remaining > 0) {
                                        Log.d(TAG, "[WebView] ✘ no remid/sid yet, retrying in ${delayMs}ms...")
                                        wv?.postDelayed({ attempt(remaining - 1) }, delayMs)
                                    } else {
                                        Log.w(TAG, "[WebView] ✘ EXHAUSTED — all $maxRetries attempts failed, no remid/sid cookies found")
                                    }
                                }

                                attempt(maxRetries)
                            }

                            webViewClient = object : WebViewClient() {

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    Log.d(TAG, "[WebView] onPageStarted: $url")
                                    view?.evaluateJavascript(INJECTED_JS, null)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val currentUrl = url ?: return

                                    val isOTC = currentUrl.contains("dynamicchallenge")
                                    val is2FA = currentUrl.contains("twofactor")
                                    val isOTC2 = currentUrl.contains("otc")
                                    val matched2FA = isOTC || is2FA || isOTC2

                                    Log.d(TAG, "[WebView] onPageFinished: $currentUrl otc=$isOTC twofactor=$is2FA otc2=$isOTC2 extractionTriggered=$extractionTriggered")

                                    if (matched2FA) {
                                        if (!showingOTCMessage) {
                                            showingOTCMessage = true
                                            errorMsg = "请完成双因素验证，验证后将自动继续"
                                            Log.d(TAG, "[WebView] ⚑ OTC/2FA page detected — showing prompt")
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false

                                    Log.d(TAG, "[WebView] shouldOverrideUrlLoading: $reqUrl")

                                    // 拦截 Juno OAuth 回调：qrc:///html/login_successful.html?code=XXX
                                    if (reqUrl.contains("login_successful.html") && reqUrl.contains("code=")) {
                                        val code = reqUrl.substringAfter("code=", "").substringBefore("&")
                                        Log.d(TAG, "[WebView] → Juno callback DETECTED code=${code.take(30)}...")
                                        if (code.isNotEmpty()) {
                                            tryExtractCookies(view, junoCode = code)
                                        }
                                        return true
                                    }
                                    return false
                                }
                                
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    Log.d(TAG, "[WebView] doUpdateVisitedHistory: $url isReload=$isReload")
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

                            Log.d(TAG, "[WebView] ▶ Loading Juno auth URL: ${junoUrl.take(200)}...")
                            loadUrl(junoUrl)
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
