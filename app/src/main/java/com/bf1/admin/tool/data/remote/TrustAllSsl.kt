package com.bf1.admin.tool.data.remote

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 信任所有证书的 SSL 工具（仅用于 EA gosredirector 旧端点；CardTool 的 rejectUnauthorized=false 等价物）。 */
object TrustAllSsl {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val socketFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        context.socketFactory
    }
}
