# osmodule — Roadmap

[English](ROADMAP.md) | [简体中文](ROADMAP.zh-CN.md)

> **Hardware-validation scope:** this project has run on Osmo Pocket 4 Pro and Osmo 360 only.
> Sections for every other camera or aircraft describe inherited compatibility code, fixtures or
> future experiments; they are not evidence that osmodule has tested or supports that hardware.

### Osmo 360 RC (R-SDK)

- [x] Process-wide camera session lease shared by media and R-SDK transports.
- [x] One R-SDK connection hub shared by remote control and GPS sync.
- [x] Public R-SDK version, key, mode, record, status, sleep, wake and restart protocol support.
- [x] Serialized commands with sequence-matched acknowledgements, timeout and retry.
- [x] Stable `CameraRemoteControl` management-plane contract; its implementation is absent from osmodule Base.
- [x] Move Osmo 360 RC and GPS into a separately installed, same-signature plugin APK.
- [x] Add a versioned AIDL boundary, signed plugin discovery and plugin-owned private UI launch.
- [x] Add the Base module manager with local APK verification and system Package Installer handoff.
- [x] Declare per-module camera compatibility and route each remote panel only to its target model.
- [x] Prevent Base media startup while the Osmo 360 RC plugin reports an active cross-process camera lease.
- [x] Add a low-latency local Wi-Fi viewfinder with H.264/H.265 hardware decode to the remote console.
- [ ] Hardware-verify the viewfinder receiver and stream profile across Osmo 360 firmware versions.
- [ ] Hardware-verify every command across Osmo 360 firmware versions and record firmware gates.
- [x] Add an official GitHub Release catalog with verified plugin downloads; retain the local APK picker for offline and development builds.

### Pocket 4P RC (DUML)

- [x] Package Pocket 4P remote control as a separately installed, same-signature plugin APK.
- [x] Adapt the OpenPocketCine registration, three-window ACK and one-shot HEVC live-view flow.
- [x] Add photo, recording, shooting-mode, gimbal-stick, recenter and flip commands with status telemetry.
- [x] Send an explicit neutral gimbal command on touch release, backgrounding, disconnect and teardown.
- [x] Preserve OpenPocketCine's Apache-2.0 attribution and license text.
- [ ] Hardware-verify session setup, HEVC preview, every exposed command and neutral-gimbal safety on Pocket 4 Pro firmware.
- [ ] Add exposure, white-balance, focus and zoom controls after the MVP transport is hardware-verified.

### 360° media

- [x] Add an optional Osmo 360-only equirectangular video viewer with drag and pinch navigation.
- [x] Keep pairing, flat preview and downloads in the core media app.
- [x] Remove the competing scrub-frame decoder during Osmo 360 startup buffering.

### 2. Inherited compatibility paths for the rest of the Osmo line

- **Action 4:** an inherited profile and media path exist, but neither has been hardware-validated
  by this project.

- [ ] Hardware-validate discovery and connection
- [ ] Hardware-validate the media grid
- [ ] Hardware-validate media downloads
- [ ] Delete a file
- [ ] Pagination: scroll past 45 files
- [ ] Favorite a file
- [ ] Load previous favorites in the grid
- [ ] Disconnection handling

### 6. Older Osmo Action generation (index-based list)

We want browse + download on the Action 1/2/3, which use an older list format keyed by numeric
`FileIndex` with no path strings ([MEDIA_PROTOCOL §1](MEDIA_PROTOCOL.md#1-get-media-list), "Parsed —
index-based"). The current tree retains the shared DCF primitives and the documented 65-byte stride;
the Action-specific decoder and its historical `action1_7.bin` fixture remain branch work rather than
shipped support. **None of this has been hardware-validated by this project.** Download is also
unfinished: `/v1?file_index=` is a placeholder.

**Inherited branch note: `support-osmo-action-1`.** It contains the index decoder, captured fixtures
and `DcfTransferProbe`, which asks whether the camera serves files over the datalink and whether `:80`
is gated on playback mode. The current project has not run that probe against Action hardware.

**Blockers:** current-project hardware access and validation of the download endpoint. Decompiled
DJI-derived code and inherited captures suggest an index-based media layer, but that evidence is not a
substitute for an osmodule run on Action hardware.

### 11. Direct USB-C ↔ USB-C media read

We want to offload over a cable, skipping the BLE-pair → wake-AP → WiFi-join dance entirely and running
at cable speed. Approach: enumerate the camera over Android's USB host API with the phone as host; if it
presents as MTP, read it through the MediaStore path.

**Blockers:** unknown whether an Osmo presents as MTP / mass-storage to an Android host or only through
a proprietary DJI USB protocol, and which models do it at all — some default to charge-only and need a
USB-mode toggle first. Needs a USB-C ↔ USB-C cable, a host-capable phone, and probably a capture of a
wired Mimo transfer.

### 15. Background downloads with a progress notification

We want the download queue to survive leaving the app, with an ongoing notification: determinate
progress, current filename, *n of m*, and pause/cancel. Today it is a bare `Thread` started by the
Activity, so a multi-gigabyte transfer is at the mercy of process death and nothing shows in the shade.
Resumable range requests already exist in `MediaDownloader`, so a killed transfer should resume rather
than restart — that is most of the value of doing this.

**Blockers:** the network binding, not the notification. Downloads only work because the process is
bound to the camera AP (`bindProcessToNetwork`); a service has to own or share that binding and react
when the AP drops. Android 14+ needs a declared `foregroundServiceType` (`dataSync`) plus
`POST_NOTIFICATIONS`. And a queue that outlives the foreground session must not keep a drone transfer
lease alive behind the user's back (#14).

### 16. Per-file shooting details (ISO, shutter, EV…)

We want what Mimo's playback screen shows — what the camera was *set to* when the file was shot. We show
duration, fps, resolution and size, all manifest fields, and nothing about exposure.

- **Stills — already on the wire.** The EXIF thumbnail path fetches the original's first 64 kB
  ([EmbeddedJpeg](camera/media/src/main/java/dev/konraditurbe/osmosis/core/EmbeddedJpeg.kt)) and the same `APP1`
  block carries ISO, exposure time, aperture and focal length. We download and discard them today, so
  reading them costs one parse and **zero extra requests**. The obvious first increment.
- **Video — the `djmd` track**, which is protobuf and not encrypted. Needs a range read of the right
  atom rather than the whole clip.
- **Drone — `file_subtype` 11 (`PHOTO_METADATA`) / 13 (`JSON`)**, named in the enum recovered from a
  decompiled DJI-derived app ([MEDIA_PROTOCOL §29](MEDIA_PROTOCOL.md#29-http-media-api-v1--dcf-indexed))
  but never requested by this project against an aircraft. Inherited Neo 2 logs report that subtypes
  3–16 were refused.

### 18. Drones beyond the Mavic 3

This entire section is an inherited research backlog. osmodule has not hardware-tested the Mavic 3,
Neo 2, Mini 3 or any other aircraft, and the profiles remain experimental compatibility paths.

- **Neo 2 (`0x007e`).** Inherited logs report credentials with the `DJI FLY` token and a handshake on
  `udp/9003`, followed by *no drone serial seen in a beacon*. The `0x51` open is expected to
  echo the aircraft's serial, read out of its own `0x51/0x13` beacon
  ([MEDIA_PROTOCOL §27a](MEDIA_PROTOCOL.md#27a-neo-2--the-same-transport-a-different-unlock)). Two
  candidates, now instrumented rather than guessed: our parser required a serial of exactly 20 chars
  (a Mavic 3's length), or the Neo 2 never emits the beacon. A failed open now logs every `0x51` inner
  command and dumps any `0x13` payload, so the next run tells them apart. Secondary: its AP dropped
  ~16 s in, 112 ms *before* the list query went out. None of this has been reproduced here.
- **Mini 3** — inherited notes do not identify a model byte, so it resolves only by the
  `DRONE_ID_FLOOR` guess. Those notes say it enters QuickTransfer with **three quick power-button
  presses** instead of a hold, but this project has not verified that behavior.
- **Delete and favourite are camera-only.** Drone records carry no manifest handle, so
  `CameraFile.deletable` is false and the long-press menu correctly offers neither. Wiring them means
  finding what a drone deletes *by* — plausibly the packed `file_index` itself.
- **Untested, would be cheap:** `/v2?storage=N&path=…` on a drone (believed to work, never exercised —
  everything goes through `/v1`), and `PROXY_MOOV` / `ORIGIN_MOOV` (`file_subtype` 15/16), which serve
  an MP4's `moov` alone and would replace the range request preview pays to find it.
- **Any other aircraft.** Ids at or above `0x40` fall back to drone defaults on a documented guess
  (`CameraModel.DRONE_ID_FLOOR`), which at least gets far enough to be diagnosable.

**Branches: `support-neo2` and `support-mini3`** — the same payload on both, one commit each, because
the diagnostic an unknown airframe needs is the same one. `DroneFrameCensus` answers what the existing
logging can't: `0x51 inner cmds seen: NONE` says the aircraft doesn't talk like a Mavic without saying
what it *does* do. So it censuses every CRC-valid frame by cmdset/cmd (nested included), the raw head
of each transport packet type, and any payload carrying a **serial-shaped run** (12–24 uppercase
alphanumerics) — which identifies the frame that carries the serial on *this* airframe even when it
isn't a `0x51/0x13`. Strictly diagnostic: it never latches a serial or changes what we send, because a
run that merely looks like a serial isn't one. `support-mini3` also carries the three-press line in the
approval dialog. `PcapAnalysis` rides along on both for reading a capture with our own decoder.

**Blockers:** hardware. The current project needs its first controlled run on each aircraft before any
of these paths can be called verified or supported.

### 19. Migrate to CompanionDeviceManager API

CompanionDeviceManager will give us features such as auto-detect, less permissions, better handling for BLE, etc...

### 20. Send HiLights while in playback

Xtra specific, need to check if possible to send hilights

### 21. UI icons overhaul

Design an iconset, stop using emojis

### 22. Show a tick for downloaded media

Just after media is downloaded, stateless. When app is restarted, and camera connects again, tick won't be there. Just visual insight into what was just downloaded.

### 23. Better camera disconnection flow

Works great on osmo nano but xtra just prompts to go back to cameras or input password
