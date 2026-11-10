package com.mihomo.tproxy.data

import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class SubResult(
    val content: String,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val title: String = ""
)

object SubFetcher {

    private const val UA = "clash-verge/v2.0.3"

    /** Accepts any server certificate. Only used when the user opts in (self-signed / intranet). */
    private val insecureSocketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), SecureRandom())
        ctx.socketFactory
    }

    private val insecureHostnameVerifier = HostnameVerifier { _, _ -> true }

    fun fetch(url: String, timeoutMs: Int = 20000, insecure: Boolean = false): SubResult {
        var current = url.trim()
        var redirects = 0
        while (redirects < 6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                if (insecure && this is HttpsURLConnection) {
                    sslSocketFactory = insecureSocketFactory
                    hostnameVerifier = insecureHostnameVerifier
                }
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: error("重定向地址缺失")
                    c.disconnect()
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) error("HTTP " + code)
                val info = c.getHeaderField("subscription-userinfo") ?: c.getHeaderField("Subscription-Userinfo") ?: ""
                val rawTitle = c.getHeaderField("content-disposition")?.let { cd ->
                    Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""").find(cd)?.groupValues?.get(1)
                } ?: ""
                val title = runCatching { java.net.URLDecoder.decode(rawTitle, "UTF-8") }.getOrDefault(rawTitle)
                val text = c.inputStream.bufferedReader().use { it.readText() }
                var up = 0L; var down = 0L; var total = 0L; var expire = 0L
                info.split(";").forEach { kv ->
                    val k = kv.substringBefore("=").trim().lowercase()
                    val v = kv.substringAfter("=", "0").trim().toLongOrNull() ?: 0L
                    when (k) {
                        "upload" -> up = v
                        "download" -> down = v
                        "total" -> total = v
                        "expire" -> expire = v
                    }
                }
                return SubResult(text, up, down, total, expire, title)
            } finally {
                runCatching { c.disconnect() }
            }
        }
        error("重定向次数过多")
    }
}
