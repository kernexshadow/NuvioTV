# Subtitle Auto Sync

Auto Sync estimates a constant subtitle delay by correlating the subtitle's speech schedule with
speech detected in the audio. It does not transcribe dialogue or compare spoken words with text.
Confidence values are heuristic scores, not calibrated probabilities of correctness.

## Primary path: listening to played audio

`SubtitleSpeechFeatureTap` sits in the main player's `PlaybackSpeedAwareAudioSink` and reduces
each decoded PCM byte, once, to 32 ms features: RMS of the 300-3400 Hz speech band and broadband
RMS. Surround tracks use the centre channel (`SubtitleDialogueDownmixer`). Features are keyed by
media timestamp, kept per contiguous run, capped at about 60 minutes, and reset when the stream
changes. There is no second player, no extra download, and playback is never suspended.

`SubtitleSpeechAligner` turns the features into a per-frame speech score (level above a rolling
noise floor, syllable-rate energy modulation, speech-band share) and cross-correlates it with the
subtitle schedule by FFT, for all delays at once. Music cues are dropped, and cue spans are capped
at an estimate of spoken duration (70 ms per character) instead of their reading time. Each lag gets
a z-like statistic; a result needs both a tall peak and a high peak-to-sidelobe ratio:

- Narrow tier: +/-15 s, from 20 s of audio, z >= 8 and PSR >= 2.5.
- Full tier: +/-90 s, from 45 s of audio, z >= 4 and PSR >= 5, plus six common frame-rate ratios.
  A scaled timeline must beat a plain delay by 10%; it is reported as a frame-rate mismatch and
  never applied.

When too little audio has been heard, Auto Sync keeps listening while the video plays and retries
at 20/45/90/180 s of captured audio. If the selected track fails, same-language alternatives are
aligned against the same audio and offered as suggestions.

The tap cannot see bitstream passthrough/offload audio, and MPV has no equivalent hook. In those
cases Auto Sync falls back to the separate-player path below.

### Credit and license

This path is derived from the subtitle auto-sync in
[Debrify](https://github.com/varunsalian/debrify) (`SubtitleAligner.kt`, `SpeechFeatureTap.kt`):
tapping played PCM, the speech score, the FFT correlation with z-score and peak-to-sidelobe
gates, the two search tiers, the frame-rate check and the listening ladder, including their
tuning constants. The code was reimplemented for NuvioTV.

Debrify is licensed under the GNU AGPL v3.0, so `SubtitleSpeechAligner.kt` and
`SubtitleSpeechFeatureTap.kt` are licensed under the AGPL v3.0 too (see their headers; the
license text is in `LICENSES/AGPL-3.0.txt`). The rest of NuvioTV remains GPL-3.0; section 13 of
both licenses permits combining them in one program.

## Fallback path: separate audio player

The rest of this document describes the fallback.

## Acquisition

The main player is suspended while a reusable audio-only ExoPlayer reads the selected stream
and audio track. `SubtitleAnalysisAudioSink` consumes decoded PCM without an AudioTrack,
time stretching, or wall-clock playback pacing. Throughput is still limited by the source,
container seeks, decoder, and VAD. Reading a multiplexed file can still transfer video bytes.

PCM collection is checked at 15, 30, 45, and 60 seconds of **decoded media time**. A read gate
stops decoder consumption during scoring. Extending a sample opens that gate again: it does
not seek, discard the decoder state, or redownload the beginning of the sample. The source may
prefetch within its bounded player buffer. Quiet samples get a second block before being
abandoned; there is no separate scout-and-revisit pass.

The sink subtracts Media3's output-stream offset for VAD timestamps, while its renderer clock
retains renderer timestamps. Seek preroll is discarded rather than relabelled as the requested
position. The sink retains decoder output while Media3 is buffering/paused, and only drains
after the renderer starts its clock. This avoids consuming the initial buffer with a frozen clock.
There is still no wall-clock pacing once started. Eight seconds without new decoded PCM (silence
counts as progress; scoring time is excluded) terminates a stalled probe early. If analysis output
stalls, times out, or fails, the attempt switches once to the existing muted, adaptive-speed
AudioTrack path for compatibility, continuing after valid partial PCM instead of discarding it.
Later probes in the same attempt stay in compatibility mode; fallback is not retried recursively.
Multi-period manifests are rejected: mapping each period into the public window timeline is
not implemented, and guessing that mapping could apply an incorrect delay.

## Decisions and evidence reuse

- A 15-second sample can propose a local offset within +/-240 seconds, but cannot authorize an
  automatic change. Its nomination gates are stricter than those for longer evidence.
- Longer samples retain the engine's fine local search and coarse global fallback.
- Discovery results, including high-scoring results, require two fresh, separated holdouts before
  automatic application. Each holdout needs at least 30 seconds/two evidence windows; an
  inconclusive holdout extends in-place to 45/60 seconds. Short discovery candidates require
  stronger and tighter confirmations. Actual PCM overlap is checked after decoding as well.
  Each holdout searches +/-240 seconds around the hypothesis (coarse then fine), rather than
  just its immediate vicinity, so unrelated local peaks must compete with other alignments.
- Early validation is bounded to two candidate attempts, with a total limit of four including
  final rescue. Failed offsets are remembered per track; another offset in that track remains
  eligible. Repeatedly testing the same failed hypothesis until it happens to pass is disallowed.
- All acquired audio, including failed holdouts, is retained for later discovery and alternative
  subtitles. Previously observed audio cannot be counted as a new independent holdout.
- Subtitle cue profiles are prepared once per attempt. Up to eight same-language alternatives
  are downloaded with concurrency two and compared as they become available; one slow provider
  does not block ready tracks. After unsuccessful discovery, pending downloads get up to five
  more seconds. Successful sync cancels unused requests immediately, including stalled bodies.

This adds no library, speech model, or native binary. It reuses the existing Media3, OkHttp,
coroutines, and WebRTC VAD dependencies. Drift, frame-rate conversion, and recuts remain outside
this constant-offset implementation.

## Verification

Unit coverage includes short-sample rejection, prepared-profile equivalence, nomination and
confirmation gates, seek timestamps/preroll, checkpoint backpressure, EOF/flush behavior, and
cancellation while an HTTP body is stalled. Existing engine and credential-scope tests also apply.

On-device checks still needed for performance/decoder integration:

1. The same known-good film/subtitle, including a +50-second mismatch and a late playback seek.
2. A quiet opening, wrong external subtitle, and a matching alternative arriving later.
3. High-bitrate MKV, AC3/DTS/TrueHD, and HLS sources; check analysis versus compatibility mode.
4. Cancel during opening, scoring, and a stalled provider; verify playback resumes at its saved
   position and preserves an intentionally paused state.
5. Compare the complete attempt's `elapsed` log, not just individual probe times. Logs include
   decoding mode, observed PCM, startup/decode/scoring time, and the independent confirmations.
