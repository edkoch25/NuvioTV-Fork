## About

This is a **personal fork** of [NuvioTV](https://github.com/NuvioMedia/NuvioTV) for test builds,
fixes, modifications, and optimisation. **It isn't for general use, and no support is provided.**

The focus is **speed, efficiency, and optimised playback of high-bitrate 4K remux video and
lossless bitstream audio** -- the kind of content (100 GB+ remuxes, TrueHD/DTS-HD MA/Atmos/DTS:X
passthrough, Dolby Vision) that stresses the parts of a player most builds don't push hard.

NuvioTV itself is a modern, TV-first media player for Android TV, written in Kotlin. It acts as a
client-side playback interface that integrates with the Stremio addon ecosystem for content
discovery and source resolution through user-installed extensions.

**Please support the official project -- [NuvioMedia on GitHub](https://github.com/NuvioMedia).**
This fork is a set of targeted optimisations layered on top of their work, not a replacement for it.

---

## What this fork adds

Optimisation and playback-quality work, most of it aimed at high-bitrate remux and lossless audio:

- **Lighter, faster build** -- unused engine components stripped out (see *What's been removed*).
- **Improved throughput & buffering** -- two fixes to NuvioTV's parallel-connections downloading.
  First, upstream only schedules a shallow `connections + 1` chunks of read-ahead; the fork deepens
  that to a memory-budgeted `connections x 4`, so more data stays queued ahead of the reader and the
  buffer actually builds. Second, upstream's chunk eviction re-downloaded data it had already
  fetched (measured ~47% of transfer wasted on a 4K remux); the fork makes eviction position-aware
  so it stops discarding chunks the reader is about to need. Together the buffer fills close to the
  speed the source can deliver and holds through bitrate peaks, and about half the wasted debrid
  data goes away. Plus off-heap custom buffers and a seek-reopen fix that makes non-faststart MP4s
  watchable.
- **Dolby Vision** -- app-side Profile 7 and Profile 5 -> 8.1 conversion via libdovi with no
  per-frame GC stutter, correct in-band enhancement-layer stripping for single-track remuxes, and
  fixes so DV5 and preserve-mapping no longer convert to the wrong profile (they were silently
  producing static 8.4 output).
- **High-resolution / lossless audio** -- a hardened bitstream passthrough stack (TrueHD, DTS-HD MA,
  Atmos, DTS:X) that resists mid-playback renegotiation, DTS-HD MA/DTS:X detection that works on
  native-DV boxes, a hi-res AC-3 transcode fix, an audio-path diagnostics row, and lossless-track
  auto-selection.
- **Kodi-style MAT audio** -- an app-side MAT / IEC61937 packing path (ported from Kodi's
  CPackerMAT) to decouple TrueHD from vendor HALs. Behind an Advanced toggle, off by default,
  experimental.
- **Improved AFR** -- precise 23.976-vs-24 frame-rate matching, seamless-switch detection, and a
  settle-then-resume for HDMI renegotiation on eARC/soundbar chains.
- **MP4 video fixes** -- non-faststart / poorly-interleaved MP4s no longer thrash on seeks.
- **Faster stream start** -- startup instrumentation plus a bootstrap fast-path so first frame
  doesn't wait on a full chunk or the file's tail index.
- **Faster / optimised UI** -- home-grid scroll-jank reduction and poster prefetch/pre-decode.
- **Improved speed-test** -- runs a real throughput test against the actual last stream URL (same
  source and path playback uses), so the number reflects what you can really pull from that debrid/
  Emby endpoint -- useful for telling a slow source apart from a buffering bug and for tuning
  connection count / chunk size.
- **Stats-for-nerds overlay** -- a live playback diagnostics HUD built for high-bitrate/lossless
  content: measured video bitrate, audio codec by name and its measured passthrough bitrate, whether
  passthrough is genuinely reaching the sink, HDR/Dolby Vision detection, live network throughput,
  the negotiated audio path, SoC temperature against per-chip throttle thresholds, audio-clock
  jitter, and AudioTrack underrun cross-checks. Confirms you're actually getting lossless audio and
  the right HDR mode, and doubles as a triage tool.

New stats for nerds overlay -

<img width="1920" height="1080" alt="screen" src="https://github.com/user-attachments/assets/0af8b4d3-8dfc-46b8-bc94-eb8893a62be5" />

Revised Last Played Stream Speed Test:

<img width="1920" height="1080" alt="nuvio-ui2" src="https://github.com/user-attachments/assets/4a66fe54-d0ea-4a89-8329-ee36c92a0196" />

---

## What's been removed

- **Home layout picker** -- the app is now **Modern-layout only**. The picker was removed from both
  first-run setup and Settings. The Modern-specific options (landscape posters, full-screen hero
  backdrop, poster/card styling) remain.
- **IAMF** and **MPEG-H** audio decoder components, and an unused UI component -- dropped to slim
  the build. No user-facing loss.
- **Android TV channel / preview-program sync** -- not used on this fork's target setup.
- **In-app update check** -- disabled, because this is a personal fork; it does not phone the
  upstream update feed.

**Not removed** (sometimes assumed otherwise): all **34 language translations**, the **add-on
manager**, and **subtitle add-on support** are intact. Only the layout picker was removed.

---

## Target hardware

Primary/validated target is the **Amlogic S905X4 class (armeabi-v7a)**, tested on a Homatics Box R
4K Plus in a Samsung HW-Q800F -> LG C9 eARC chain. Other devices (Nvidia Shield, Prism+, Xiaomi) are
community-tested but not the primary target -- treat them as such.

---

## Licence

This fork is derived from [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) and is licensed
under the **GNU General Public License v3.0**. All upstream copyright notices, licence headers, and
author attributions are preserved. Full credit to the upstream maintainers and contributors.
