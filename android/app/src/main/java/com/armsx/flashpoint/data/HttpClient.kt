package com.armsx.flashpoint.data

import java.net.HttpURLConnection
import java.net.URL

/** The shape of ARMSX3's HttpClient.doRequest, on HttpURLConnection (GET only). */
object HttpClient {
    class Response(var statusCode: Int = 0, var contentType: String = "", var data: ByteArray = ByteArray(0))

    fun doRequest(url: String, method: String = "GET", @Suppress("UNUSED_PARAMETER") postData: ByteArray? = null, userAgent: String? = null, timeoutMs: Int = 20_000): Response {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            Response(code, c.contentType.orEmpty(), stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            c.disconnect()
        }
    }
}
