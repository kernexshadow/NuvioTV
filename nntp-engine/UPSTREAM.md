# StreamNZB engine provenance and modifications

## Upstream source

The packages under `pkg/` and `third_party/rardecode/` were copied from
[Gaisberg/streamnzb](https://github.com/Gaisberg/streamnzb) at commit
`5097e2c1d490dc68d320cd8137334fdd209077ca`.

Only the dependency closure required for NZB parsing, NNTP article fetching,
yEnc decoding, archive handling, and seekable media streaming is included.
Module versions used to build the native executable are pinned in `go.mod` and
`go.sum`.

## NuvioTV modifications

NuvioTV modifications began on 2026-09-01 and were last updated on 2026-09-03.
They comprise:

- a loopback HTTP API and Android entry point under `cmd/nuvio-nntp/`;
- Stremio `fileIdx` and `fileMustInclude` selection after archive metadata is
  available, implemented in `pkg/media/unpack/selection.go` and its RAR call
  site;
- removal of StreamNZB's persistent provider-health and usage-accounting hooks
  from the NNTP client pool, with session-scoped in-memory statistics instead;
- retention of completed read-ahead segments for the lifetime of a playback
  session, allowing backward seeks without fetching the articles again; and
- Android build and application integration code outside the copied packages.

## Licenses and binary distribution

The StreamNZB-derived packages and NuvioTV wrapper are distributed under the
[GNU General Public License v3.0](LICENSE). The vendored rardecode code remains
under its [BSD 2-Clause License](third_party/rardecode/LICENSE), including its
copyright and disclaimer. The combined native executable is distributed under
GPL-3.0 with the BSD notice retained.

Android builds package this file and both complete license texts under the
APK's `assets/licenses/` directory. The corresponding source is this repository,
including the pinned module versions and the build recipe below.

## Rebuilding the Android binaries

The checked-in binaries were built with Go 1.25.6, Android NDK 29.0.14206865,
and Android API level 24. From the repository root on Windows, run:

```powershell
.\nntp-engine\build-android.ps1 -NdkHome "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865"
```

This rebuilds `libnuvionntp.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64`. Update the pinned upstream commit, copied source, `go.mod`, `go.sum`,
this modification notice, toolchain versions, and Android binaries together.
