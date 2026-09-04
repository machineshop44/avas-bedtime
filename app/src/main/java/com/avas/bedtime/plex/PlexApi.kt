package com.avas.bedtime.plex

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object PlexHeaders {
    const val PRODUCT = "Ava Bedtime"
    val VERSION: String
        get() = runCatching {
            com.avas.bedtime.BuildConfig.VERSION_NAME
        }.getOrDefault("0.6.9")
    const val PLATFORM = "Android"
}

class PlexApi(
    private val clientId: String,
    private val http: OkHttpClient = defaultClient()
) {
    data class PinSession(
        val id: Long,
        val code: String,
        val authUrl: String
    )

    data class ServerInfo(
        val name: String,
        val clientIdentifier: String,
        val accessToken: String,
        val connections: List<Connection>
    ) {
        data class Connection(
            val uri: String,
            val local: Boolean,
            val relay: Boolean
        ) {
            val label: String
                get() = when {
                    local && !relay -> "Home network"
                    relay -> "Plex relay (slower)"
                    else -> "Internet / remote"
                }
        }
    }

    data class LibrarySection(
        val key: String,
        val title: String,
        val type: String
    )

    data class PlaylistSummary(
        val id: String,
        val title: String,
        val leafCount: Int,
        val smart: Boolean
    )

    data class Track(
        val ratingKey: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val partKey: String
    )

    data class UserProfile(
        val username: String,
        val authToken: String
    )

    suspend fun createPin(): Result<PinSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("strong", "true")
                .add("X-Plex-Product", PlexHeaders.PRODUCT)
                .add("X-Plex-Client-Identifier", clientId)
                .add("X-Plex-Version", PlexHeaders.VERSION)
                .build()
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/pins?strong=true")
                .post(body)
                .headers(plexTvHeaders())
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("PIN request failed (${response.code}): $raw")
                }
                val json = JSONObject(raw)
                val id = json.getLong("id")
                val code = json.getString("code")
                // Hash-bang form is what Plex's web auth expects.
                val authUrl =
                    "https://app.plex.tv/auth#!?clientID=${enc(clientId)}&code=${enc(code)}" +
                        "&context[device][product]=${enc(PlexHeaders.PRODUCT)}"
                PinSession(id = id, code = code, authUrl = authUrl)
            }
        }
    }

    suspend fun checkPinOnce(pin: PinSession): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/pins/${pin.id}?code=${enc(pin.code)}")
                .get()
                .headers(plexTvHeaders())
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val json = JSONObject(response.body!!.string())
                val token = json.optString("authToken", "")
                if (token.isNotBlank() && token != "null") token else null
            }
        }
    }

    /** Poll until the parent finishes plex.tv link, or timeout. */
    suspend fun waitForPinAuth(pin: PinSession, timeoutMs: Long = 180_000L): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val token = checkPinOnce(pin).getOrNull()
                    if (!token.isNullOrBlank()) return@runCatching token
                    delay(1_500)
                }
                error("Timed out waiting for Plex sign-in. Return to the app and tap Check again.")
            }
        }

    suspend fun fetchUser(token: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/user")
                .get()
                .headers(plexTvHeaders(token))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Could not load Plex user (${response.code})")
                val json = JSONObject(response.body!!.string())
                UserProfile(
                    username = json.optString("username").ifBlank {
                        json.optString("title").ifBlank { "Plex account" }
                    },
                    authToken = token
                )
            }
        }
    }

    suspend fun listServers(token: String): Result<List<ServerInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
                .get()
                .headers(plexTvHeaders(token))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Could not list servers (${response.code})")
                val array = JSONArray(response.body!!.string())
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val provides = item.optString("provides")
                        if (!provides.contains("server")) continue
                        val connectionsJson = item.optJSONArray("connections") ?: JSONArray()
                        val connections = buildList {
                            for (c in 0 until connectionsJson.length()) {
                                val conn = connectionsJson.getJSONObject(c)
                                add(
                                    ServerInfo.Connection(
                                        uri = conn.getString("uri"),
                                        local = conn.optBoolean("local"),
                                        relay = conn.optBoolean("relay")
                                    )
                                )
                            }
                        }
                        if (connections.isEmpty()) continue
                        add(
                            ServerInfo(
                                name = item.optString("name", "Plex Server"),
                                clientIdentifier = item.optString("clientIdentifier"),
                                accessToken = item.optString("accessToken", token),
                                connections = connections
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun listAudioLibraries(serverUrl: String, token: String): Result<List<LibrarySection>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = serverGet(serverUrl, token, "/library/sections")
                val media = json.optJSONObject("MediaContainer") ?: json
                val dirs = media.optJSONArray("Directory") ?: JSONArray()
                buildList {
                    for (i in 0 until dirs.length()) {
                        val d = dirs.getJSONObject(i)
                        val type = d.optString("type")
                        // Music / audiobook libraries are type=artist in PMS.
                        if (type == "artist") {
                            add(
                                LibrarySection(
                                    key = d.getString("key"),
                                    title = d.optString("title", "Music"),
                                    type = type
                                )
                            )
                        }
                    }
                }
            }
        }

    suspend fun listAudioPlaylists(serverUrl: String, token: String): Result<List<PlaylistSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Prefer audio playlists; fall back to all playlists if the filter returns nothing.
                val audio = fetchPlaylists(serverUrl, token, "/playlists/all?playlistType=audio")
                if (audio.isNotEmpty()) return@runCatching audio
                fetchPlaylists(serverUrl, token, "/playlists/all")
                    .filter { playlist ->
                        // Keep likely music/audiobook lists; drop video playlists when possible.
                        true
                    }
            }
        }

    private fun fetchPlaylists(
        serverUrl: String,
        token: String,
        path: String
    ): List<PlaylistSummary> {
        val json = serverGet(serverUrl, token, path)
        val media = json.optJSONObject("MediaContainer") ?: json
        val meta = media.optJSONArray("Metadata") ?: JSONArray()
        return buildList {
            for (i in 0 until meta.length()) {
                val m = meta.getJSONObject(i)
                val playlistType = m.optString("playlistType").ifBlank {
                    m.optString("type")
                }
                if (playlistType.isNotBlank() &&
                    playlistType != "audio" &&
                    playlistType != "playlist"
                ) {
                    // Skip video photo playlists when type is explicit.
                    if (playlistType == "video" || playlistType == "photo") continue
                }
                add(
                    PlaylistSummary(
                        id = m.getString("ratingKey"),
                        title = m.optString("title", "Playlist"),
                        leafCount = m.optInt("leafCount"),
                        smart = m.optBoolean("smart")
                    )
                )
            }
        }.sortedBy { it.title.lowercase() }
    }


    suspend fun playlistTracks(
        serverUrl: String,
        token: String,
        playlistId: String
    ): Result<List<Track>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = serverGet(serverUrl, token, "/playlists/$playlistId/items")
            parseTracks(json)
        }
    }

    /** All audio tracks in a music/audiobook library section. */
    suspend fun libraryTracks(
        serverUrl: String,
        token: String,
        sectionKey: String,
        limit: Int = 300
    ): Result<List<Track>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/library/sections/$sectionKey/all?type=10&X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"
            val json = serverGet(serverUrl, token, path)
            parseTracks(json)
        }
    }

    fun streamUrl(serverUrl: String, partKey: String): String {
        val base = serverUrl.trimEnd('/')
        val key = if (partKey.startsWith("/")) partKey else "/$partKey"
        // Token is sent via ExoPlayer request headers, not the query string.
        return "$base$key"
    }

    /** Prefer home Wi‑Fi, then remote, then relay — without testing reachability. */
    fun rankedConnections(server: ServerInfo): List<ServerInfo.Connection> {
        return server.connections.distinctBy { it.uri }.sortedWith(
            compareBy<ServerInfo.Connection> { if (it.local && !it.relay) 0 else 1 }
                .thenBy { if (!it.local && !it.relay) 0 else 1 }
                .thenBy { if (it.relay) 1 else 0 }
                .thenBy { if (it.uri.startsWith("https")) 0 else 1 }
        )
    }

    fun bestConnection(server: ServerInfo): String =
        rankedConnections(server).first().uri.trimEnd('/')

    /**
     * Probe each published address and return the first one this device can reach.
     * Fixes tablets that cannot use the PC's LAN IP but can use plex.direct remote.
     */
    suspend fun findReachableConnection(
        server: ServerInfo,
        token: String = server.accessToken
    ): Result<ServerInfo.Connection> = withContext(Dispatchers.IO) {
        runCatching {
            val probeClient = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val errors = mutableListOf<String>()
            for (conn in rankedConnections(server)) {
                val base = conn.uri.trimEnd('/')
                val ok = runCatching {
                    val request = Request.Builder()
                        .url("$base/identity")
                        .get()
                        .header("Accept", "application/json")
                        .header("X-Plex-Token", token)
                        .header("X-Plex-Client-Identifier", clientId)
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        response.isSuccessful || response.code == 401 || response.code == 403
                    }
                }.onFailure {
                    errors += "${conn.label} ($base): ${it.message}"
                }.getOrDefault(false)
                if (ok) return@runCatching conn
                errors += "${conn.label} ($base): no response"
            }
            error(
                "Could not reach ${server.name} from this tablet.\n" +
                    errors.take(4).joinToString("\n") +
                    "\nPut the tablet on the same Wi‑Fi as Plex, or enable Remote Access."
            )
        }
    }

    suspend fun testConnection(uri: String, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val probeClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val base = uri.trimEnd('/')
                val request = Request.Builder()
                    .url("$base/identity")
                    .get()
                    .header("Accept", "application/json")
                    .header("X-Plex-Token", token)
                    .header("X-Plex-Client-Identifier", clientId)
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    if (!(response.isSuccessful || response.code == 401 || response.code == 403)) {
                        error("Server returned ${response.code}")
                    }
                }
            }
        }


    private fun parseTracks(json: JSONObject): List<Track> {
        val media = json.optJSONObject("MediaContainer") ?: json
        val meta = media.optJSONArray("Metadata") ?: JSONArray()
        return buildList {
            for (i in 0 until meta.length()) {
                val m = meta.getJSONObject(i)
                val mediaArr = m.optJSONArray("Media") ?: continue
                if (mediaArr.length() == 0) continue
                val parts = mediaArr.getJSONObject(0).optJSONArray("Part") ?: continue
                if (parts.length() == 0) continue
                val partKey = parts.getJSONObject(0).optString("key")
                if (partKey.isBlank()) continue
                add(
                    Track(
                        ratingKey = m.optString("ratingKey"),
                        title = m.optString("title", "Track"),
                        artist = m.optString("grandparentTitle").ifBlank {
                            m.optString("originalTitle").ifBlank { m.optString("artist", "") }
                        },
                        durationMs = m.optLong("duration"),
                        partKey = partKey
                    )
                )
            }
        }
    }

    private fun serverGet(serverUrl: String, token: String, path: String): JSONObject {
        val base = serverUrl.trimEnd('/')
        val url = if (path.startsWith("http")) path else "$base$path"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("X-Plex-Token", token)
            .header("X-Plex-Client-Identifier", clientId)
            .header("X-Plex-Product", PlexHeaders.PRODUCT)
            .header("X-Plex-Version", PlexHeaders.VERSION)
            .header("X-Plex-Platform", PlexHeaders.PLATFORM)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Plex server error ${response.code} for $path")
            }
            return JSONObject(response.body!!.string())
        }
    }

    private fun plexTvHeaders(token: String? = null): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("Accept", "application/json")
            .add("X-Plex-Product", PlexHeaders.PRODUCT)
            .add("X-Plex-Version", PlexHeaders.VERSION)
            .add("X-Plex-Client-Identifier", clientId)
            .add("X-Plex-Platform", PlexHeaders.PLATFORM)
            .add("X-Plex-Device", "Android")
            .add("X-Plex-Device-Name", "Ava Bedtime")
        if (!token.isNullOrBlank()) {
            builder.add("X-Plex-Token", token)
        }
        return builder.build()
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
