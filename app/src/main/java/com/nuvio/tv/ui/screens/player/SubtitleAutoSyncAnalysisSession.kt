package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/** Attempt-local cache: parsing/normalizing subtitle cues is independent of the audio sample. */
internal class SubtitleAutoSyncAnalysisSession {
    private val profiles = IdentityHashMap<List<SubtitleSyncCue>, SubtitleAutoSyncEngine.PreparedCues>()

    suspend fun analyze(
        cues: List<SubtitleSyncCue>,
        snapshot: SubtitleSpeechSnapshot,
        minimumOffsetMs: Int? = null,
        maximumOffsetMs: Int? = null,
        allowShortHypothesis: Boolean = false
    ): SubtitleAutoSyncResult = withContext(Dispatchers.Default) {
        val profile = synchronized(profiles) {
            profiles.getOrPut(cues) { SubtitleAutoSyncEngine.prepareCues(cues) }
        }
        SubtitleAutoSyncEngine.findBestOffset(profile, snapshot, minimumOffsetMs, maximumOffsetMs, allowShortHypothesis)
    }
}
