The binary ffmpeg extension was build with following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

To assemble ``.aar``:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```

## Fork modification: lib-decoder-ffmpeg-release.aar (F5, 2026-08)

`FfmpegAudioRenderer.class` inside `classes.jar` was rebuilt from the reconstruction
source at `ffmpeg-aar-src/FfmpegAudioRenderer.java` (CFR 0.152 decompilation of the
original class, plus the F5 change: `setDeniedTranscodeMimes` — the AC-3 transcode
decision is now per-MIME, consulting an app-supplied denied set as well as the global
`forceOpticalPassthrough` flag). With the set empty the behaviour is equivalent to the
original. All other AAR entries, including every `libffmpegJNI.so`, are byte-identical
to the previous AAR. The custom audio pipeline (transcode/downmix, Java + native) is
the upstream NuvioTV developer's private work, present only in this binary; the
reconstruction source is provided as corresponding source for the modified class.

Rebuild recipe (JDK 21, CFR 0.152, media3-decoder 1.8.0 sources, android.jar API 34):
compile `ffmpeg-aar-src/FfmpegAudioRenderer.java` with `--release 11 -g` against
{android.jar, lib-common classes.jar, lib-exoplayer classes.jar, this AAR's
classes.jar, media3-decoder 1.8.0 classes, androidx-annotation, guava}; `jar uf` the
resulting class into `classes.jar`; `zip` it back into the AAR.
