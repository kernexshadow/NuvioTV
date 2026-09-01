package com.nuvio.tv.core.usenet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NntpService @Inject constructor(
    private val binary: NntpEngineBinary,
    private val api: NntpEngineApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<NntpState>(NntpState.Idle)
    val state: StateFlow<NntpState> = _state.asStateFlow()

    private var currentSessionId: String? = null
    private var statsJob: Job? = null

    suspend fun startStream(
        nzbUrl: String,
        servers: List<String>,
        fileIdx: Int?,
        fileMustInclude: String?,
        season: Int?,
        episode: Int?
    ): String = withContext(Dispatchers.IO) {
        stopStream()
        require(nzbUrl.isNotBlank()) { "NZB URL is blank" }
        require(servers.isNotEmpty()) { "NNTP servers are missing" }
        servers.forEach(NntpServerConfig::parse)

        _state.value = NntpState.Connecting
        try {
            binary.start()
            val session = api.createSession(
                NntpSessionRequest(
                    nzbUrl = nzbUrl,
                    servers = servers,
                    fileIdx = fileIdx,
                    fileMustInclude = fileMustInclude,
                    season = season,
                    episode = episode
                )
            )
            currentSessionId = session.id
            _state.value = NntpState.Streaming(localUrl = session.streamUrl)
            startStatsPolling(session)
            session.streamUrl
        } catch (error: CancellationException) {
            _state.value = NntpState.Idle
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "Failed to start NNTP stream"
            _state.value = NntpState.Error(message)
            throw if (error is NntpException) error else NntpException(message)
        }
    }

    fun stopStream() {
        statsJob?.cancel()
        statsJob = null
        val sessionId = currentSessionId
        currentSessionId = null
        if (sessionId != null) {
            scope.launch { api.deleteSession(sessionId) }
        }
        _state.value = NntpState.Idle
    }

    /** Leaves playback alive for an external player while dropping Kotlin ownership. */
    fun detachStream() {
        statsJob?.cancel()
        statsJob = null
        currentSessionId = null
        _state.value = NntpState.Idle
    }

    suspend fun shutdown() {
        statsJob?.cancel()
        statsJob = null
        currentSessionId?.let { api.deleteSession(it) }
        currentSessionId = null
        _state.value = NntpState.Idle
        binary.stop()
    }

    fun ownsLocalUrl(url: String?): Boolean =
        url?.startsWith("${binary.baseUrl}/v1/sessions/") == true

    private fun startStatsPolling(session: NntpSession) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && currentSessionId == session.id) {
                try {
                    val stats = api.getStats(session.id)
                    val current = _state.value
                    if (stats != null && current is NntpState.Streaming) {
                        _state.value = current.copy(
                            downloadedBytes = stats.downloadedBytes,
                            downloadSpeed = stats.downloadSpeed,
                            connections = stats.connections
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                }
                delay(1_000L)
            }
        }
    }
}
