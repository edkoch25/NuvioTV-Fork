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

## Latest: the speed release -- 0.7.20-beta-nt1

Nearly every wait in the app just got shorter, and every claim was measured on-device:

- **Pressing play reaches the player 2-5x sooner** (~1.7 s -> 0.3-0.7 s) -- sources are searched,
  ranked and the debrid link fetched while you're still browsing, from the details page, the
  episode list, and Continue Watching (which previously always started from cold: 1,254-2,351 ms
  -> 2 ms).
- **4K remuxes open ~2.3 s faster** -- the start-up index read fetches exactly what it needs
  (it was downloading 4.8x too much), and the connection to the content server now opens the
  moment you press play.
- **"Next episode" starts ~2 s sooner** on a deliberate press.
- **Profile switching stays fast all session** -- a compounding background leak meant it got
  slower with every switch and never recovered until restart; now fixed (-34% on a warm switch).
- Plus the full upstream **NuvioTV 0.7.20-beta** merged, smoother home-screen scrolling
  (re-renders per frame halved), reliable fast repeat-plays, and a storage-aware image cache.

Full details, honest caveats and the upstream changelog:
[release notes](https://github.com/ysosrs123/NuvioTV-Fork/releases/tag/0.7.20-beta-nt1).

---

## What this fork adds

Optimisation and playback-quality work, most of it aimed at high-bitrate remux and lossless audio.
The short version: streams start faster, buffer deeper, stall less, and you can *see* that your
device is genuinely delivering lossless audio and the right HDR -- without needing to be the kind
of person who tunes buffer settings for fun.

- **Lighter, faster build** -- unused engine components stripped out, so the app itself is smaller
  and snappier (see *What's been removed*).
- **Device settings assessment -- your settings, tuned for *your* hardware in one press.** Stop
  guessing what connection count, buffer size or Dolby Vision mode your box wants: the assessment
  measures your link against the last thing you actually played, reads what your device and
  display genuinely support, and lays out recommended settings with the reasoning next to every
  one -- what was measured, what was calculated from your hardware, what's a personal trade-off
  (pick a buffer profile; it'll suggest one based on how steady your connection looked), and what
  it can't honestly know so won't touch. Like the answers? Apply them all in one press. Change
  your mind? Revert restores every previous value, even after a restart. You stay in charge; it
  just does the homework.
- **Fewer stalls, less wasted bandwidth** -- upstream's parallel downloading kept too little data
  queued ahead of playback and threw away chunks it had already paid to download (measured ~47% of
  transfer wasted on a 4K remux). This fork keeps the pipeline properly fed and stops the
  re-downloading, so the buffer builds close to the speed your source can deliver, holds through
  bitrate peaks, and roughly halves the debrid data burned per title. If a debrid CDN rate-limits
  you mid-film, speeds now recover on their own instead of staying throttled to the credits. Plus
  off-heap buffers and a seek fix that makes non-faststart MP4s actually watchable.
- **A speed test that tells you what to do about it** -- not a generic number from a test server:
  an adaptive sweep of connection and chunk-size combinations against the *actual* stream you last
  played, over the same path playback uses. It finds the cheapest configuration that comfortably
  sustains the title and says so in plain terms -- and on memory-tight boxes it's smart enough to
  prefer a config that leaves room for a deeper playback buffer, telling you the trade in MB and
  seconds. It also watches how steady your connection held during the test, which feeds the
  assessment's buffer suggestion. Slow source or buffering bug? Now you can tell them apart.
- **Better streams picked for you, automatically** -- source filtering and sorting re-baselined
  around TRaSH-guides-aligned release-group quality tiers, so the best-quality trustworthy release
  floats to the top and auto-play grabs the right one without you reading twenty filenames. Safe
  to experiment: one tap resets to the recommended baseline, another shows everything unfiltered
  while keeping best-first ordering, and the auto-play settings link straight to the quality rules
  that drive them.
- **Dolby Vision that just works** -- app-side Profile 7 and Profile 5 -> 8.1 conversion via
  libdovi with no per-frame stutter, correct enhancement-layer handling for single-track remuxes,
  and fixes for cases that silently produced wrong or static output before. DV titles look the way
  they're meant to on hardware that was never sold as supporting them.
- **Lossless audio you can trust** -- a hardened bitstream passthrough stack (TrueHD, DTS-HD MA,
  Atmos, DTS:X) that resists mid-playback renegotiation dropping you to lossy without telling
  you, format detection that works on native-DV boxes, a hi-res AC-3 transcode fix, and automatic
  selection of the lossless track so you don't start every film in the wrong audio.
- **Kodi-style MAT audio** -- an app-side MAT / IEC61937 packing path (ported from Kodi's
  CPackerMAT) that takes TrueHD delivery out of the hands of flaky vendor audio drivers. Behind an
  Advanced toggle, off by default, experimental -- for when your box's HAL is the problem.
- **Judder-free frame rates** -- precise 23.976-vs-24 matching so films play at their native
  cadence, seamless-switch detection, and a settle-then-resume that stops eARC/soundbar chains
  dropping audio while HDMI renegotiates.
- **MP4s that seek properly** -- non-faststart / poorly-interleaved MP4s no longer thrash and
  stall every time you skip around.
- **Faster stream start** -- the app searches, ranks and pre-resolves your stream while you
  browse, opens the network connection at the press, and no longer over-fetches the file's tail
  index -- so pressing play reaches the player in a few hundred milliseconds instead of a couple
  of seconds.
- **Smoother browsing** -- home-grid scroll-jank reduction and poster prefetch/pre-decode, so the
  UI keeps up with your remote.
- **Stats-for-nerds overlay -- proof, not vibes.** A live diagnostics HUD built for
  high-bitrate/lossless content: measured video bitrate, the audio codec by name with its measured
  passthrough bitrate, whether bitstream is *genuinely* reaching your AVR, HDR/Dolby Vision
  detection, live network throughput and buffer health, the negotiated audio path, SoC stats to know when your SoC is being throtled, audio-clock jitter and underrun cross-checks. When it says
  TrueHD Atmos is hitting the sink, it measured it. The assessment configures; this overlay
  verifies.

## New stats for nerds overlay -

<img width="1920" height="1080" alt="screenshot" src="https://github.com/user-attachments/assets/5b572d80-d3b2-4716-86b5-51404f0b00ad" />


## Revised Last Played Stream Speed Test:

<img width="1920" height="1080" alt="screenshot1" src="https://github.com/user-attachments/assets/1ccb4258-7487-4424-82cb-496a776e3c4f" />


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

Primary/validated target is the **Amlogic S905X4 / 4GB RAM and Amlogic S905X5M / 2GB RAM class (armeabi-v7a)**, tested on a Homatics Box R
4K Plus and a Xiaomi Box S 3rd Gen, in a Samsung HW-Q800F -> LG C9 eARC chain. Other devices (Nvidia Shield, Prism+, Xiaomi) have been
community-tested.

---

## Licence

This fork is derived from [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) and is licensed
under the **GNU General Public License v3.0**. All upstream copyright notices, licence headers, and
author attributions are preserved. Full credit to the upstream maintainers and contributors.
