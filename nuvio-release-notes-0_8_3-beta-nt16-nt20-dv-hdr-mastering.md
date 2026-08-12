# NuvioTV Fork — Release Notes
## 0.8.3-beta-nt16 → nt20 (versionCode 1274 → 1278)

**Build date:** 2026-08-12 · **Branch:** `nuvio-test`
**Status:** FINAL — built, installed and device-verified on the Ugoos AM9 Pro (Amlogic, arm64-capable, running the armeabi-v7a build), then pushed. Remote head `f1f688d2c` (nt20 / versionCode 1278). No public release/tag is cut for this build; fold these into the next public release's notes.

One new diagnostic readout, delivered across five builds. **No engine, playback, passthrough, AFR or Dolby Vision output behaviour changed** — this reads the Dolby Vision RPU and reports what it finds; it does not alter a single output byte. It is the read half of the planned RPU-informed HDR10 output work, shipped and proven on its own first.

---

## Added

**Source HDR mastering metadata, read from the Dolby Vision RPU.**
Playing Dolby Vision content now surfaces the master's static HDR metadata — MaxCLL, MaxFALL, and the mastering-display peak luminance — in two places:

- **Diagnostics card** — a new **DV HDR Mastering** row (e.g. `MaxCLL 617 · MaxFALL 496 · MDL peak ~1001 nits`).
- **Stats HUD** — a matching **DV HDR** line in the VIDEO section, next to the DV conversion line.

The values are read once per stream directly from the RPU using the bundled libdovi 3.3.2 readers (no library change), and the mastering-display peak is derived from the RPU via the SMPTE ST 2084 (PQ) transfer function — validated on a known 1000-nit master, which reads back as ~1001 nits. Coverage is complete: MKV and native MP4/fMP4/TS, on both the convert and strip paths. Because it reads the *source* RPU before any conversion, the numbers describe the original master, not the converted output.

This is diagnostics only. It reads what is there; it changes nothing about how anything plays.

---

## Under the hood

**Reads on every container framing.** The native (MP4/TS) path can deliver samples in either length-delimited or Annex-B framing regardless of what the extractor declares. The probe now detects the framing from the sample bytes and reads the RPU either way, so the readout is reliable across sources rather than only on length-delimited streams.

**Shows on the strip path too.** The readout is exempt from the card's "DV-engaged" dashing, so the source mastering data is visible even when Dolby Vision is being stripped to an HDR10 base layer — which is exactly the case where knowing the master's numbers is most useful.

**Diagnostic logging.** A once-per-stream `DVMetaProbe` log line records where the read fired and what it found, for field diagnosis. Low-volume; fires once per playback.

---

## Credits

RPU parsing uses libdovi (MIT), by quietvoid — <https://github.com/quietvoid/dovi_tool>. No libdovi source is reproduced in this tree; upstream copyright and licence headers are preserved.
