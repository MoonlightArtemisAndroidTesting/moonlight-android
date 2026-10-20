# Artemis Android

Previously named Moonlight Noir

An open source client for [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine).

Artemis Android will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Artemis is currently the best fork of Moonlight with loads of optimizations for office usage.

A more seamless experience with virtual display will be Artemis paired with [Apollo](https://github.com/ClassicOldSong/Apollo).

# Testing branch: HEVC stall diagnostics

> **This branch is for troubleshooting, not for everyday use.** It contains everything from the
> end-user branch (`upstream-pr/s905x5-hevc`) plus tools for investigating the Amlogic HEVC decoder
> hang. For normal streaming, use the end-user branch and its releases.

## The problem being investigated

On boxes with the `c2.amlogic.hevc.decoder` (for example the Onn 4K Pro V2), HEVC streams freeze
about 1.5–2.5 times an hour: the decoder keeps accepting video but stops producing frames. The
stall watchdog on the end-user branch clears each freeze within a fraction of a second by asking
the PC for a keyframe, but the goal is to stop the decoder getting stuck at all.

The tools below let the hang be recorded during a live stream and then reproduced offline on the
box, with no PC, so decoder settings can be tested quickly.

## Tools

All of these are in **Settings → Advanced Settings**. Capture only runs where the stall watchdog
does (HEVC on `c2.amlogic.hevc` decoders), and replay needs a device with the decoder named in the
capture. Slices per frame applies on any device.

### Slices per frame

Sets how many slices the PC splits each video frame into: **Auto** (the decoder's default, which is
1 on Amlogic), 1, 2, 4 or 8. It's sent to the PC when a stream starts, so change it before
connecting. Use it to check whether the slice layout affects how often the decoder hangs.

### Capture video on decoder stall

When the stall watchdog catches a hang, this saves the video the decoder received leading up to it.
It's off by default.

- **How it works:** the app records the HEVC video it sends to the decoder in segments, each
  starting at a keyframe. When the next keyframe arrives, the previous segment is deleted, unless a
  stall happened in it. Recording runs on a low-priority background thread, so it can't slow the
  stream down.
- **Where captures go:** `Android/data/<app package>/files/stall-captures/`. For the debug build
  ("Diana"), that's `/sdcard/Android/data/com.limelight.noirdebug/files/stall-captures/`. Each
  capture is three files:

  | File | Contents |
  |---|---|
  | `stall-<time>.h265` | The raw HEVC video, playable with `ffplay` |
  | `stall-<time>.csv` | One row per piece of video data (frame number, type, byte position), with `#` lines marking where each stall was detected |
  | `stall-<time>.txt` | Decoder, resolution, frame rate, bitrate, bit depth, slices per frame, the exact decoder configuration, and device details |

- **Overlay:** with the performance overlay on, `Stall captures saved: N` shows how many have been
  saved this stream.
- **Limits:** a segment is dropped if it grows past 1 GB without a keyframe. With HEVC
  reference-frame invalidation off, keyframes can be many minutes apart, and 1 GB is only about 2–3
  minutes at 70 Mbps, so many hangs can't be captured. Lost ones are listed in `lost-captures.txt`.
  Saved captures are limited to 2 GB in total, deleting the oldest first. Keep about 3 GB free.

To copy captures to a PC and check whether the video data itself is valid:

```bash
adb pull /sdcard/Android/data/com.limelight.noirdebug/files/stall-captures
```

```bash
ffmpeg -v error -i stall-<time>.h265 -f null -
```

No output from ffmpeg means the video data was valid.

### Replay stall capture

Plays a saved capture through the box's hardware decoder, set up the same way as for a real
stream, and reports whether and where the decoder got stuck. It needs no PC or network. The screen
lists the captures in `stall-captures`; copy others there with `adb push` (all three files).

**Options:**

- **Timing:**
  - **Original timing:** feeds each frame at the moment it was originally received during the
    stream, from the `.csv`. Closest to a live stream.
  - **As fast as possible:** feeds frames as fast as the decoder accepts them (about 2.2× real time
    at 4K).
- **Output handling:**
  - **Like the stream** (default): handles decoder output exactly as the app does during a stream.
    It follows your "prefer lower delays" setting: keep the newest finished frame and drop the rest,
    and use a Choreographer thread for frame pacing.
  - **Render every frame:** shows every decoded frame.
- **Runs:** 1–200. About 165 runs of a 44-second capture is a 2-hour soak.
- **Decoder settings,** each **App default / Off / On**, applied on top of the app's own settings:

  | Setting | App default for `c2.amlogic.hevc` |
  |---|---|
  | Android low latency (`KEY_LOW_LATENCY`) | Off (skipped because it causes a slideshow on this decoder) |
  | Amlogic low latency (`vdec-lowlatency`) | On |
  | Vendor low latency (`vendor.low-latency.enable`) | On |
  | Realtime priority / max operating rate | On (realtime priority, `KEY_PRIORITY = 0`) |

The replay also uses your "ultra low latency" setting and reads the whole capture into memory
before the first run, so storage speed can't affect the results.

**Reading the results.** Each run's result is shown on screen and added to
`stall-captures/replay-results.txt`:

| Result | Meaning |
|---|---|
| `Not reproduced: every frame decoded` | A clean run |
| `STALL REPRODUCED: output stopped after frame N and never resumed, M frames never came out` | **The hang.** The decoder stopped producing frames and stayed stuck for the rest of the run, as it would in a stream until the watchdog requests a keyframe. The result also gives the frame where the watchdog would have fired |
| `Temporary stalls: no output for X ms after frame N ...` | The decoder paused but recovered on its own |
| `Decoder skipped N frames but kept going` | Some frames never came out, but output continued |
| `Replay input fell behind (not a decoder problem): ...` | The replay itself couldn't feed a frame on time, usually because the box was busy. Not a decoder fault |

A stuck Amlogic decoder still passes the end-of-stream marker through, so the replay judges a run
by whether every frame came out, not by whether the stream ended.

The **summary** at the end of a set of runs gives the number of permanent stalls, runs with
temporary stalls or skipped frames, frames decoded, elapsed time, permanent stalls per hour of
video, and the decoder settings used.

**How stalls are detected:**

- **Original timing:** the watchdog's own rule. A stall is when 3 or more frames are waiting and
  the oldest has waited 50 ms or more.
- **As fast as possible:** the decoder's queue is always full, so every frame naturally waits about
  60 ms and that rule would flag every frame. Instead, a stall is no output for 50 ms or more while
  frames are waiting. A decoder that stops accepting input counts as a permanent stall.

**Tips:**

- **Don't test right after booting the box.** For the first few minutes the box is busy and the
  first run tends to fall behind.
- **Change one decoder setting at a time.** Run each for about 2 hours, and also run a baseline
  (everything on App default) on the same day for comparison.
- **The hang is rare, so judge results by counts over long soaks.** At the baseline rate (about 1.7
  an hour), a 2-hour soak should produce about 3–4 permanent stalls. Zero is a lead worth
  repeating; 1–2 isn't conclusive.

## Findings so far

These are from an Onn 4K Pro V2, replaying one 4K60 HEVC capture (8-bit, 70 Mbps setting).

- **The captured video data is valid.** ffmpeg decodes every frame without errors, including the
  ones the decoder hung on.
- **The hang reproduces offline at about the live rate, at random points.** It needs no network,
  PC, audio or input, and specific frames don't trigger it: the same capture hangs at different
  frames on different runs and plays cleanly on most.
- **The app's output handling isn't the cause.** The hang happens with either output handling
  option.
- **Stalls may be tied to elapsed time rather than frames decoded.** Fast mode produced about the
  same number of stalls per hour of testing as original timing, but only half as many per hour of
  video. The counts are too small to be sure.
- **Realtime priority off doesn't stop the hangs.** The first 2-hour soak with it off had 0
  permanent stalls, but the repeat had 2. Across both, the rate was about a third of the baseline
  (0.52 vs 1.72 an hour). With only 7 stalls in total, though, a gap that size happens by chance
  about 1 time in 8, so it may make no difference at all. A same-day baseline is next, to check
  whether the box's rate simply varies from day to day.
- **Lesson for testing:** one zero-stall soak isn't enough. A setting needs 0 stalls in two separate
  2-hour soaks before it counts as a real lead.
- **Where stalls happen:** frames ~878, 1211, 1716, 1719, 1881, 2177 and 2453 of the same capture,
  all P-frames, with no clear pattern.

| Soak (stream-style output) | Test time | Permanent stalls |
|---|---|---|
| Baseline, original timing | 106 min | 3 |
| Baseline, as fast as possible | 68 min | 2 |
| Realtime priority off, original timing | 116 min | 0 |
| Realtime priority off, original timing (repeat) | 116 min | 2 |
| **Baseline total** | **174 min** | **5 (1.72 an hour)** |
| **Realtime priority off total** | **232 min** | **2 (0.52 an hour)** |

# About this fork: Amlogic TV box fixes

This fork of Artemis fixes streaming on Android TV boxes and sticks that use Amlogic's Codec2
decoders (`c2.amlogic.*`). Affected devices include the Onn 4K Pro V2, Xiaomi TV Box S 3rd Gen,
Xiaomi TV Stick 4K 2nd Gen, Ugoos AM9 (S905X5 / S905X5M) and Xiaomi TV Stick MiTV-AYFR0 (S905Y4).
Every change applies only when one of these decoders is in use, so other devices behave exactly
as they do in Artemis.

This fork has been tested on an Onn 4K Pro V2. The HEVC changes were also tested on a Xiaomi TV
Box S 3rd Gen in their original author's build
([ClassicOldSong/moonlight-android#603](https://github.com/ClassicOldSong/moonlight-android/pull/603)).

## AV1 is used automatically (recommended)

Even with the fixes below, the Amlogic HEVC decoder still hangs about 2–3 times an hour (each hang
is caught and cleared within a fraction of a second). On these boxes, AV1 doesn't have this problem:
a 2-hour AV1 test on an Onn 4K Pro V2 had no freezes.

So when the video codec setting (Settings → Advanced Settings) is **Auto**, this fork offers AV1 to
your PC on devices with both the affected HEVC decoder and an Amlogic hardware AV1 decoder. Your PC
uses AV1 if it can encode it, and otherwise falls back to HEVC with all the fixes below.

- **Your PC needs an AV1 encoder:** NVIDIA RTX 40 series, AMD RX 7000 series, Intel Arc, or newer.
- **Choosing a codec explicitly is always respected.** If the setting isn't Auto, set it to Auto
  to get AV1.
- **HDR:** if HDR is on and the box's AV1 decoder can't do 10-bit HDR, the fork stays on HEVC so
  you don't silently lose HDR.

To check which codec is in use, turn on the performance overlay: the decoder line shows a
`c2.amlogic` AV1 decoder, and a `Forced GPU composition` line appears (see below).

## HEVC plays smoothly instead of as a slideshow

`c2.amlogic.hevc.decoder` reports support for Android's low-latency mode, but when that mode is
turned on it decodes every frame and shows only a few per second. This fork skips Android's
low-latency option (`KEY_LOW_LATENCY`) for this decoder and uses Amlogic's own low-latency options
(`vdec-lowlatency`, `vendor.low-latency.enable`) instead. See
[moonlight-android#1504](https://github.com/moonlight-stream/moonlight-android/issues/1504) and
[#1584](https://github.com/moonlight-stream/moonlight-android/issues/1584).

## Fewer HEVC decoder errors

With HEVC reference frame invalidation (RFI) on, the decoder hits bursts of errored frames every
few minutes. RFI is now off for this decoder, and lost frames are recovered with keyframes instead.
This makes the error bursts much rarer, but it doesn't stop them completely.

The bundled `moonlight-common-c` includes three fixes for streaming with RFI off. Without them, a
single out-of-order packet could freeze the video for about 2 seconds and show a "Slow connection"
warning ([moonlight-common-c#147](https://github.com/moonlight-stream/moonlight-common-c/pull/147)).

## HEVC hangs recover automatically

Occasionally the HEVC decoder still gets stuck. It keeps taking in video but shows nothing, and in
Artemis the picture stays frozen until you restart the stream. This fork watches for that: if 3
frames go into the decoder and nothing comes out for 50 ms, it asks the PC for a fresh keyframe,
which clears the error. If the decoder stays stuck, it is flushed, then restarted, then reset. A
hang now shows up as a brief stutter.

With the performance overlay on, the line `Decoder stall recoveries: N (last freeze: X ms)` shows
how many hangs were caught and how long the most recent freeze lasted.

## Forced GPU composition for AV1

When streaming AV1 on a `c2.amlogic` decoder, the app keeps Android's compositor busy every frame
so the video stays under GPU composition instead of being sent straight to the display hardware.
This uses the "Force GPU composition" technique from
[sven253's fork](https://github.com/sven253/artemis-moonlight-android-hevc-fix), but turns on
automatically instead of through a setting. The performance overlay shows
`Forced GPU composition: on (N ticks/s)` while it's active. The tick rate should roughly match your
display's refresh rate. Tested working on an Onn 4k Pro V2.

## Debug builds no longer drop packets on purpose

Debug builds used to compile the streaming library in a test mode that deliberately drops one
packet from every error-correction block, so the packet loss figure and "Slow connection" warnings
in debug builds were not real. That mode also left the native code unoptimized. Debug builds now
compile native code with release settings (also from sven253's fork), so they stream like release
builds. They still install as a separate app called "Diana".

# Features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)
31. SBS 3D for external Displays (Using AI MiDaS v2 Lite)

# Disclaimer

This is the `go away` version of Moonlight Android.

I got kicked from Moonlight and Sunshine's Discord server literally for helping people out.

This is what I got for finding a bug, opened an issue, getting no response, troubleshoot myself, fixed the issue myself, shared it by PR to the main repo hoping my efforts can help someone else during the maintainance gap.

Yes, I'm going away. Fixes and improvements on this fork are not necessarily be merged to the main repo either. I have also started [a fork of Sunshine called Apollo](https://github.com/ClassicOldSong/Apollo) and will add useful features that will never get merged by the main repo shortly. [Apollo](https://github.com/ClassicOldSong/Apollo) and [Moonlight Noir](https://github.com/ClassicOldSong/moonlight-android) will no longer be compatible with OG Sunshine and OG Moonlight eventually, but they'll work even better with much more carefully designed features.

The main repo had stayed silent for 5 months, with nobody actually responding to issues, and people are getting totally no help besides the limited FAQ in their Discord server. I tried to answer issues and questions, solve problems within my ablilty but I got kicked out just for helping others.

**PRs for feature improvements are welcomed here unlike the main repo, your ideas are more likely to be appreciated and your efforts are actually being respected. We welcome people who can and willing to share their efforts, helping yourselves and other people in need.**

**Update**: They have contacted me and apologized for this incident, but the fact it **happened** still motivated me to start my own fork.

## Downloads
* [Download the APK for this fork](https://github.com/MoonlightArtemisAndroidTesting/moonlight-android/releases/tag/ForceGPU%2BWatchdogV2) (pre-release v2: force GPU composition + stall watchdog)

Pick the APK that matches your device's Android build: `arm64-v8a` for 64-bit Android, or
`armeabi-v7a` for 32-bit. Many Amlogic boxes run a 32-bit Android image even though their chip is
64-bit, so if the `arm64-v8a` APK won't install, use `armeabi-v7a`. These are debug builds: they
install as a separate app called "Diana", so they won't replace an existing Artemis install.

For the official Artemis builds without these fixes, see
[ClassicOldSong's releases](https://github.com/ClassicOldSong/moonlight-android/releases).

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
