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
- **Improved throughput & buffering** -- a multi-connection parallel range downloader with
  adjustable connection count and chunk size, HTTP/2 pooling, and off-heap custom buffers. A
  position-aware chunk-eviction policy stops the downloader re-fetching data it already had, so the
  buffer fills close to the speed the source can deliver and holds through bitrate peaks.
- **Dolby Vision** -- app-side Profile 7 -> 8.1 conversion via libdovi with no per-frame GC stutter,
  correct enhancement-layer handling for single-track remuxes.
- **High-resolution / lossless audio** -- a hardened bitstream passthrough stack (TrueHD, DTS-HD MA,
  Atmos, DTS:X) that resists mid-playback renegotiation, plus lossless-track auto-selection.
- **Kodi-style MAT audio** -- an app-side MAT / IEC61937 packing path (ported from Kodi's
  CPackerMAT) to decouple TrueHD from vendor HALs. Behind an Advanced toggle, off by default,
  experimental.
- **Improved AFR** -- precise 23.976-vs-24 frame-rate matching, seamless-switch detection, and a
  settle-then-resume for HDMI renegotiation on eARC/soundbar chains.
- **MP4 video fixes** -- non-faststart / poorly-interleaved MP4s no longer thrash on seeks.
- **Faster stream start** -- startup instrumentation plus a bootstrap fast-path so first frame
  doesn't wait on a full chunk or the file's tail index.
- **Faster / optimised UI** -- home-grid scroll-jank reduction and poster prefetch/pre-decode.
- **Improved speed-test** -- a revised last-played-stream throughput test.
- **Stats-for-nerds overlay** -- live video/audio bitrate, codec names, measured passthrough
  bitrate, HDR detection, SoC thermal, audio-clock jitter, and underrun cross-checks.

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
