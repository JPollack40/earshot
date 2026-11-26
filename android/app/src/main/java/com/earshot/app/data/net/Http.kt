package com.earshot.app.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val USER_AGENT = "Earshot/0.1 (Android; personal podcast player)"

    /**
     * Opens a GET connection and follows redirects manually, including http -> https hops,
     * which HttpURLConnection will not follow on its own. Caller must disconnect().
     */
    @Throws(IOException::class)
    fun open(url: String, maxRedirects: Int = 6): HttpURLConnection {
        var current = URL(url)
        for (attempt in 0..maxRedirects) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect with no location from $current")
                current = URL(current, location)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("Server returned HTTP $code for $current")
            }
            return conn
        }
        throw IOException("Too many redirects for $url")
    }

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
