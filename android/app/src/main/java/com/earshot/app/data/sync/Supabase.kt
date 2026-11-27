package com.earshot.app.data.sync

import android.content.Context
import com.earshot.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The signed-in account, or null. */
data class Session(val email: String, val userId: String)

/** A request Supabase refused, with its HTTP status and message. */
class SupabaseException(val status: Int, message: String) : IOException(message)

/**
 * Minimal Supabase client over plain HTTPS: password sign-in (Supabase Auth) and the Data API
 * (PostgREST). Tokens are kept in the app's private preferences; the password never is.
 * Project URL and publishable key come from BuildConfig (android/local.properties).
 */
class Supabase(context: Context) {
    private val prefs = context.getSharedPreferences("sync_auth", Context.MODE_PRIVATE)
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_KEY
    private val refreshLock = Mutex()

    val configured: Boolean get() = base.isNotBlank() && key.isNotBlank()

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    private fun loadSession(): Session? {
        val email = prefs.getString("email", null) ?: return null
        val user = prefs.getString("user_id", null) ?: return null
        if (prefs.getString("refresh_token", null) == null) return null
        return Session(email, user)
    }

    // ---- Auth ----

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val res = request("POST", "/auth/v1/token?grant_type=password", body.toString(), auth = false)
        saveTokens(JSONObject(res), email.trim())
        requireNotNull(_session.value)
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun saveTokens(o: JSONObject, email: String? = null) {
        val user = o.optJSONObject("user")
        val expiresIn = o.optLong("expires_in", 3600)
        prefs.edit()
            .putString("access_token", o.getString("access_token"))
            .putString("refresh_token", o.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000)
            .apply {
                if (email != null) putString("email", email)
                if (user != null) putString("user_id", user.optString("id"))
            }
            .apply()
        _session.value = loadSession()
    }

    /** A valid access token, refreshed when it's about to expire. Throws if signed out. */
    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val token = prefs.getString("access_token", null)
        val expiresAt = prefs.getLong("expires_at", 0)
        if (token != null && !forceRefresh && System.currentTimeMillis() < expiresAt - 60_000) return token
        val refresh = prefs.getString("refresh_token", null) ?: throw SupabaseException(401, "Not signed in")
        try {
            val res = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString(), auth = false)
            saveTokens(JSONObject(res))
        } catch (e: SupabaseException) {
            if (e.status in 400..499) signOut() // the session was revoked or expired: sign in again
            throw e
        }
        prefs.getString("access_token", null) ?: throw SupabaseException(401, "Not signed in")
    }

    // ---- Data API ----

    /** GET /rest/v1/[path] as the signed-in user; returns the JSON array. */
    suspend fun select(path: String): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(authed("GET", "/rest/v1/$path", null, null))
    }

    /** Inserts or updates [rows] in [table], matching on [onConflict] columns. */
    suspend fun upsert(table: String, onConflict: String, rows: JSONArray) = withContext(Dispatchers.IO) {
        if (rows.length() == 0) return@withContext
        authed("POST", "/rest/v1/$table?on_conflict=$onConflict", rows.toString(), "resolution=merge-duplicates,return=minimal")
    }

    private suspend fun authed(method: String, path: String, body: String?, prefer: String?): String {
        return try {
            request(method, path, body, auth = true, token = accessToken(), prefer = prefer)
        } catch (e: SupabaseException) {
            if (e.status != 401) throw e
            request(method, path, body, auth = true, token = accessToken(forceRefresh = true), prefer = prefer)
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        auth: Boolean,
        token: String? = null,
        prefer: String? = null,
    ): String {
        if (!configured) throw IOException("Sync isn't set up in this build (no Supabase URL/key)")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Accept", "application/json")
            if (auth && token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text) }.getOrNull()?.let {
                    it.optString("msg").ifBlank { it.optString("message") }.ifBlank { it.optString("error_description") }
                        .ifBlank { it.optString("error") }
                }?.ifBlank { null } ?: "HTTP $code"
                throw SupabaseException(code, msg)
            }
            return text.ifBlank { "[]" }
        } finally {
            conn.disconnect()
        }
    }
}
