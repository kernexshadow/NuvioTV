# StreamNZB engine provenance

The packages under `pkg/` and `third_party/rardecode/` are copied from
[Gaisberg/streamnzb](https://github.com/Gaisberg/streamnzb) at commit
`5097e2c1d490dc68d320cd8137334fdd209077ca`.

Only the dependency closure required for NZB parsing, NNTP article fetching,
yEnc decoding, archive handling, and seekable media streaming is included.
Nuvio's loopback API and Android entry point live under `cmd/nuvio-nntp/`.
`pkg/media/unpack/selection.go`, its tests, and its RAR call site contain a
small Nuvio extension that applies Stremio `fileIdx` and `fileMustInclude`
after archive metadata is available.
The NNTP client pool omits StreamNZB's persistent provider-health and usage
accounting hooks because the Android engine is session-scoped and keeps those
statistics in memory.

The upstream and wrapper code are distributed under GPL-3.0. Update the pinned
commit, copied source, `go.mod`, `go.sum`, and Android binaries together.
