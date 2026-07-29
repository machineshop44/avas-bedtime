package com.avas.bedtime.plex

import android.util.Log
import com.avas.bedtime.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps Plex PIN polling alive even when the browser is open.
 */
class PlexSignInCoordinator(
    private val repository: SettingsRepository
) {
    data class State(
        val waiting: Boolean = false,
        val pinCode: String? = null,
        val authUrl: String? = null,
        val message: String = "",
        val error: String? = null,
        val signedIn: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var activePin: PlexApi.PinSession? = null
    private var activeClientId: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _openBrowser = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openBrowser: SharedFlow<String> = _openBrowser.asSharedFlow()

    fun startSignIn() {
        pollJob?.cancel()
        pollJob = scope.launch {
            _state.value = State(
                waiting = true,
                message = "Starting Plex sign-in…"
            )
            runCatching {
                val clientId = repository.ensureClientId()
                activeClientId = clientId
                val api = PlexApi(clientId)
                val pin = api.createPin().getOrThrow()
                activePin = pin
                _state.value = State(
                    waiting = true,
                    pinCode = pin.code,
                    authUrl = pin.authUrl,
                    message = "Log into Plex in the browser, then return here."
                )
                _openBrowser.emit(pin.authUrl)
                val token = api.waitForPinAuth(pin, timeoutMs = 5 * 60_000L).getOrThrow()
                finishWithToken(api, clientId, token)
            }.onFailure { err ->
                Log.e(TAG, "Plex sign-in failed", err)
                if (err is kotlinx.coroutines.CancellationException) throw err
                _state.value = State(
                    waiting = false,
                    pinCode = activePin?.code,
                    authUrl = activePin?.authUrl,
                    error = err.message ?: "Sign-in failed",
                    message = "Sign-in did not finish. Tap Check again after logging in."
                )
            }
        }
    }

    fun openBrowserAgain() {
        val url = activePin?.authUrl ?: _state.value.authUrl ?: return
        scope.launch { _openBrowser.emit(url) }
    }

    /** Call when the parent comes back from the browser. */
    fun checkNow() {
        val pin = activePin ?: return
        val clientId = activeClientId ?: return
        scope.launch {
            _state.value = _state.value.copy(
                error = null,
                message = "Checking Plex…"
            )
            runCatching {
                val api = PlexApi(clientId)
                val token = api.checkPinOnce(pin).getOrThrow()
                    ?: error("Not approved yet. Finish signing in on the Plex page, then tap Check again.")
                pollJob?.cancel()
                finishWithToken(api, clientId, token)
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                _state.value = _state.value.copy(
                    waiting = pollJob?.isActive == true,
                    error = err.message,
                    message = "Still waiting — finish Plex sign-in, then Check again"
                )
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        activePin = null
        _state.value = State(message = "Sign-in cancelled")
    }

    private suspend fun finishWithToken(api: PlexApi, clientId: String, token: String) {
        val user = api.fetchUser(token).getOrThrow()
        val servers = api.listServers(token).getOrThrow()
        val preferred = servers.firstOrNull()
        val reachable = preferred?.let { api.findReachableConnection(it).getOrNull() }
        val serverUrl = reachable?.uri?.trimEnd('/')
            ?: preferred?.let { api.bestConnection(it) }.orEmpty()
        val serverToken = preferred?.accessToken ?: token
        repository.update {
            it.copy(
                clientId = clientId,
                plexToken = token,
                serverAccessToken = serverToken,
                plexUsername = user.username,
                serverUrl = serverUrl,
                serverName = preferred?.name.orEmpty(),
                playlistId = "",
                playlistTitle = ""
            )
        }
        activePin = null
        val reachNote = when {
            preferred == null -> "No Plex server found on this account."
            reachable == null -> "Signed in, but could not auto-reach the server. Pick a connection in Settings."
            else -> "Signed in as ${user.username} via ${reachable.label}. Pick a playlist below."
        }
        _state.value = State(
            waiting = false,
            signedIn = true,
            message = reachNote
        )
    }

    companion object {
        private const val TAG = "PlexSignIn"
    }
}
