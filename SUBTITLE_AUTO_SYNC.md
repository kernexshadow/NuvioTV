# Subtitle Auto Sync

Auto Sync estimates a constant subtitle delay by correlating subtitle activity/onsets with
WebRTC VAD speech activity. It does not transcribe dialogue or compare spoken words with text.
The confidence value is a heuristic score, not a calibrated probability of correctness.

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
