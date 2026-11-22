# AirPlay Fit

[简体中文](README.zh-CN.md)

AirPlay Fit is a lightweight AirPlay receiver for Android TV and TV boxes. It gives you control over the picture on the TV, including when you mirror a video with scrolling comments.

## Highlights

- **Auto crop with scrolling comments (danmaku).** Detects the black margins on all four sides independently across multiple frames. Once the borders have been stable for about three seconds, it locks the crop and stops sampling. Verified with iPhone mirroring on an Android 9 TV box. Comments inside the cropped area are clipped.
- **Picture controls for the TV remote.** Switch between Auto crop, Original ratio, and Fill with a single press of **OK**. Open **More** for per-edge crop, fixed aspect ratios, zoom and pan, or 90° rotation. Picture settings carry over to the next session.
- **AirPlay mirroring with a selectable 30 or 60 fps ceiling.** The sending device chooses the actual frame rate. Use the on-screen status panel to check the incoming rate, bitrate, and frames discarded by the decoder pipeline.
- **Lightweight Android receiver.** About 295 KB for the current release build, with Android 5.1 as the minimum SDK. Supports iPhone and Mac mirroring, audio streaming, and compatible MP4 and HLS video handoff.

## Picture controls

While mirroring, press **OK** or **Menu** to open the picture menu at the bottom. Use **Left** and **Right** to move through the cards and press **OK** to apply the focused mode. For manual crop or zoom and pan, press **Left** or **Right** on a slider to adjust the picture in real time. Press **Back** to return to the previous menu or close it.

| Picture option | What it does |
|---|---|
| Fit original frame | Shows the whole frame at its original aspect ratio. |
| Crop wide video to 16:9 | Trims the left and right of sources wider than 16:9, then fits the result without stretching. |
| Manual crop | Trims each source edge by up to 40% in 1% steps. |
| Auto crop | Ignores likely scrolling comments in the margins. Locks the crop after the detected borders stay stable for about three seconds. Select **Auto crop** again to redetect the borders. Comments inside the cropped area are clipped. |
| Fill without stretching | Scales to cover the screen and clips overflow. |
| Stretch to screen | Fills both dimensions independently. |
| Original pixels (1:1) | Maps each video pixel to one output pixel. Frames larger than the screen are clipped. |
| Force 4:3, 14:9, or 16:9 | Changes the frame's proportions to the selected ratio, then fits it on screen. |
| Nonlinear stretch | Expands narrow frames horizontally with most of the stretch near the sides. |
| Zoom and pan | Scales from 50% to 200% relative to Fit, with independent horizontal and vertical positioning. |
| Rotation | Turns the picture clockwise in 90° increments. It can be combined with any framing mode. |

On the home screen, **Frame rate limit** switches the advertised maximum between 30 fps (default) and 60 fps.
Reconnect after changing it so the sender can negotiate again.

## Video handoff and playback

AirPlay URL handoff accepts ordinary HTTP or HTTPS MP4 and HLS media that the box can reach. Select AirPlay Fit from a compatible player's AirPlay menu. A handed-off stream can keep playing after the controller disconnects if the box can still reach the media URL. App-specific relay formats such as `mlhls://` and protected video are outside this implementation.

- In direct playback, **OK** pauses or resumes, **Left/Right** seeks ten seconds, **Down** shows playback progress and **Menu** opens picture settings. TV media keys work too.
- In either mode, **Up** or **Info** toggles the status panel. Mirroring reports input frame rate and encoded video bitrate over a one-second window, plus frames discarded by the app's decoder pipeline. Direct playback shows platform metrics where Android provides them.
- After a mirror stream is interrupted, the picture screen stays open for eight seconds. If the sender reconnects, playback resumes with the saved framing. A temporary direct-play I/O failure is retried from the last known position.

AirPlay receiver for older Android TV hardware with H.264 mirroring, AAC-ELD, AAC-LC, ALAC and PCM audio, and photo display. The app is written in Java and runs without root.

Uses legacy AirPlay pairing, FairPlay SAP v1 and the AirPlay 1 timing protocol, the same approach as UxPlay. FairPlay DRM content (Netflix, Apple TV+) cannot work. No PIN pairing. Photo display is implemented but untested.

## Upstream device tests

| Device | OS | Result |
|---|---|---|
| Fire TV 2nd gen (AFTS, MT8173) | Fire OS 5, Android 5.1 | 1080p30, ~6 ms decode latency |
| Chromecast with Google TV 4K (sabrina) | Android 14 | 1080p30, 4K30 from a Mac |

Senders: current iPhone and Mac (October 2026), screen mirroring and AirPlay audio.

## Build and install

Needs JDK 17 and Android SDK 34.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# Android 10+: allow the service to open the video screen from the background
adb shell appops set com.hwz.airplayfit SYSTEM_ALERT_WINDOW allow
```

Open the app to start the receiver. It runs as a foreground service and advertises over mDNS on port 7000. Start on boot is off by default and can be enabled in the app.

## Hardware notes

- **MediaTek decoders hold about 7 frames (~90 ms) by default.** This Fire OS build's ACodec reads the vendor format keys `vdec-lowlatency` and `vdec-no-reorder`. Setting them cut decode latency to ~6 ms. Newer devices use `KEY_LOW_LATENCY` (Android 11+).
- **Android 5.1 has no ALAC decoder.** `AlacDecoder.java` is a port of Apple's reference decoder. Its output matches `afconvert` bit for bit.
- **AAC-ELD needs `csd-0` = `F8 E8 50 00`**, and the `OMX.google.aac.decoder` software decoder.
- **iOS restarts the audio stream on every pause.** Treat each SETUP as a new audio stream.

## Layout

- `protocol/`: platform-independent AirPlay. Covers RTSP/HTTP control, pairing, FairPlay, stream decryption, mDNS responder, binary plist, ALAC.
- `android/`: service, MediaCodec video and audio renderers, UI.

## Credits and license

GPL-3.0. Based on [Android TV AirPlay Receiver](https://github.com/exgota/android-tv-airplay-receiver). FairPlay code derives from [java-airplay-lib](https://github.com/serezhka/java-airplay-lib) (MIT, notice in `protocol/fairplay/`), a Java port of playfair. Protocol details follow [UxPlay](https://github.com/FDH2/UxPlay).
