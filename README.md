# osmodule

[English](README.md) | [简体中文](README.zh-CN.md)

[![Build](https://github.com/pill4r/osmodule/actions/workflows/build_app.yml/badge.svg)](https://github.com/pill4r/osmodule/actions/workflows/build_app.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)

**A lean, modular Android client for DJI Osmo media.**

osmodule is an independent Android client for browsing, previewing and downloading media from DJI
Osmo cameras and selected DJI drones. It has no DJI SDK dependency, account, analytics, cloud upload
or activation requirement.

This project is derived from the open-source [Osmosis](https://github.com/KonradIT/osmosis) project,
but is maintained and released as a distinct application. The Android package namespace remains
`dev.konraditurbe.osmosis` for upgrade and plugin-ABI compatibility; the product name, release
artifacts and user-facing storage folders are osmodule.

## Why osmodule?

DJI Mimo serves many workflows, while some users only need a fast path from an Osmo camera to local
files on an Android device. Shipping every editor, account, cloud and device-control workflow in one
package increases download size, permissions and maintenance surface.

osmodule follows three rules:

- **Keep the Base APK focused:** camera discovery, pairing, media browsing, preview and download.
- **Make specialist features optional:** Osmo 360 remote control and GPS sync ship in a separate APK.
- **Keep boundaries enforceable:** modules have explicit Gradle dependencies, and external plugins use
  a versioned, same-signature Binder contract instead of loading foreign code into Base.

The project is not intended to reproduce every DJI Mimo feature. It aims to provide a smaller,
auditable local-media client that can grow without turning the Base APK back into a monolith.

## Features

- Camera discovery and pairing over Bluetooth LE.
- Media grid, thumbnails and low-resolution streaming preview.
- Optional interactive 360° video viewer with drag-to-look and pinch-to-zoom on Osmo 360; raw OSV
  clips open in it automatically and stream their paired LRF proxy.
- High-resolution resumable downloads to a user-selected video directory, with
  `Movies/osmodule`, `Pictures/osmodule` and `Download/osmodule` as defaults.
- In/out trimming with original-quality stream copy.
- Camera battery, shooting mode and storage status.
- Multi-camera history, favourites and deletion.
- Module manager for enabling bundled features and installing signed external modules.
- Optional Osmo 360-only remote console with low-latency local Wi-Fi preview, R-SDK controls,
  camera status, BLE wake and GPS sync.
- QuickTransfer media access for supported DJI drones.

The app does not save protocol logs to shared files and has no option for sending logs to an upstream
author. Normal Android logcat output remains available to local developers through `adb logcat`.

## Supported hardware

| Device | Status |
|---|---|
| Osmo Nano | Verified on hardware |
| Osmo Action 5 Pro / Xtra Edge Pro | Verified on hardware |
| Osmo Action 6 | Verified on hardware |
| Osmo 360 | Verified media access; interactive 360° viewer and remote-control modules |
| Osmo Pocket 3 | Verified on hardware |
| Osmo Pocket 4 / 4 Pro | Verified on hardware |
| DJI Mavic 3 QuickTransfer | Verified on hardware |
| Other Osmo cameras and DJI drones | Experimental |

## Install and connect

Install `app-debug.apk` first. Install `rsdk-debug.apk` only if remote control or GPS sync is needed;
both APKs must be built with the same signing key. On Xiaomi/Redmi/POCO devices, enable Autostart for
the R-SDK plugin if HyperOS blocks its Binder service.

1. Turn on Bluetooth and Wi-Fi and open osmodule.
2. Grant the requested nearby-device permissions.
3. Power on the camera and select it from the Cameras list.
4. Approve pairing on the camera, then approve Android's camera Wi-Fi join prompt.
5. Browse, preview or download media. On Osmo 360 clips, open the interactive 360° viewer from Preview.
6. Open Modules to enable the bundled 360° viewer or install the optional Osmo 360 remote-control plugin.

## Privacy

osmodule communicates with the camera on its local network (`192.168.2.1`). It has no analytics,
account system, activation service or cloud upload. Android's network security configuration limits
the cleartext camera connection to that local address.

## Build

Requirements: Android SDK 36, JDK 21 and Android 10+ (API 29) on the target device.

```sh
./gradlew test lint \
  :app:assembleDebug \
  :plugins:rsdk:assembleDebug
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`

The plugin is optional. Users who only need media access install the Base APK. Release builds are
unsigned unless a local `keystore.properties` is supplied; Base and plugin releases must use the same
signing lineage.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — APK boundaries, module graph and plugin trust model.
- [Development guide](docs/DEVELOPMENT.md) — code ownership, quality gates and release workflow.
- [Media protocol reference](MEDIA_PROTOCOL.md) — reverse-engineered BLE, DUML and HTTP behavior.
- [Protocol map](docs/01-protocol-map.md) — packet-level command and transport index.
- [Roadmap](ROADMAP.md) — completed work, hardware gaps and planned features.
- [Third-party notices](THIRD_PARTY_NOTICES.md) — incorporated research and licensed components.

## Credits and license

osmodule retains the MIT-licensed work and attribution of the Osmosis authors and contributors.
The protocol implementation also builds on work from
[o-gs](https://github.com/o-gs),
[dji-remote](https://github.com/dimadesu/dji-remote),
[osmo-download](https://github.com/SemiConscious/osmo-download),
[DJI-Wifi-Connect](https://github.com/sniffingpickles/DJI-Wifi-Connect),
[lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble), and DJI's
[Osmo GPS Controller demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo). The Osmo 360
live-view transport is adapted from the MIT-licensed
[osmo360 Android prototype](https://github.com/yesbhautik/osmo360), and the factory-calibrated
projection is based on the MIT-licensed [PanoForge](https://github.com/Belenos-Toutatis/PanoForge)
metadata and mapping research.

Licensed under the [MIT License](LICENSE.txt). This is an independent third-party project and is not
affiliated with, authorized by or endorsed by DJI. DJI and Osmo are trademarks of their respective
owners.
