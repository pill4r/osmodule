# osmodule

[English](README.md) | [简体中文](README.zh-CN.md)

[![CI](https://github.com/pill4r/osmodule/actions/workflows/ci.yml/badge.svg)](https://github.com/pill4r/osmodule/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)

**A lean, modular Android client for DJI Osmo media.**

osmodule is an independent Android client for browsing, previewing and downloading media from DJI
Osmo cameras. It has no DJI SDK dependency, built-in account system, analytics or cloud upload. A
brand-new camera must still be activated once with DJI Mimo before osmodule can connect to it.

This project is derived from the open-source [Osmosis](https://github.com/KonradIT/osmosis) project,
but is maintained and released as a distinct application. The Base app and its official plugins
use the project-owned `dev.pillar.osmodule` Android namespace; release artifacts and user-facing
storage folders use the osmodule name as well.

## Why osmodule?

DJI Mimo serves many workflows, while some users only need a fast path from an Osmo camera to local
files on an Android device. Shipping every editor, account, cloud and device-control workflow in one
package increases download size, permissions and maintenance surface.

osmodule follows three rules:

- **Keep the Base APK focused:** camera discovery, pairing, media browsing, preview and download.
- **Make specialist features optional:** the Osmo 360 viewer, Osmo 360 control/GPS sync and Pocket
  4P remote control ship in separate APKs.
- **Keep boundaries enforceable:** modules have explicit Gradle dependencies, and external plugins use
  a versioned, same-signature Binder contract instead of loading foreign code into Base.

The project is not intended to reproduce every DJI Mimo feature. It aims to provide a smaller,
auditable local-media client that can grow without turning the Base APK back into a monolith.

## Features

- Camera discovery and pairing over Bluetooth LE.
- Media grid, thumbnails and low-resolution streaming preview.
- Optional, independently installed interactive 360° photo and video viewer with portrait/landscape
  rotation, drag-to-look and pinch-to-zoom on Osmo 360; stitched 2:1 JPEGs and raw OSV clips open in
  it automatically, with OSV playback streaming the paired LRF proxy.
- High-resolution resumable downloads to a user-selected video directory, with
  `Movies/osmodule`, `Pictures/osmodule` and `Download/osmodule` as defaults.
- In/out trimming with original-quality stream copy.
- Camera connection, battery and storage status.
- Multi-camera history, favourites and deletion.
- Module manager for installing and removing signed optional plugins.
- Optional Osmo 360 RC module with low-latency local Wi-Fi preview, R-SDK controls,
  camera status, BLE wake and GPS sync.
- Experimental, optional Pocket 4P RC module with local HEVC preview, photo/record/mode controls and
  gimbal control.

The app does not export or send protocol logs. Developers can still inspect local Android logcat
output with `adb logcat`.

## Tested hardware

osmodule has currently been tested on the following devices only. This is the project's own test
coverage, not a list of every model that may be recognized by inherited protocol code.

| Device | Test status |
|---|---|
| Osmo Pocket 4 Pro (`OsmoPocket4P` / Pocket 4P) | Media workflow and RC live preview tested on hardware; remote controls remain experimental |
| Osmo 360 | Tested on hardware |

All other Osmo cameras and DJI drones are currently untested in osmodule and are not claimed as
supported. Compatibility paths inherited from Osmosis may still be present in the codebase.

## Install and connect

For a published release, install the Base APK, open **Modules**, then use **Install from GitHub** for
the 360 Viewer, Osmo 360 RC or Pocket 4P RC plugin. Base downloads the corresponding APK from the latest published
[GitHub Release](https://github.com/pill4r/osmodule/releases/latest), verifies its official package,
manifest contract and signing certificate, and only then opens Android's Package Installer. The local
APK picker remains available for offline installation and development builds.

Debug APKs must come from the same CI run: install `app-debug.apk`, then select that run's
`panorama360-debug.apk`, `rsdk-debug.apk` and/or `pocket4p-debug.apk` through **Choose local APK**. A Debug Base cannot
install production-signed plugins from GitHub Releases. On Xiaomi/Redmi/POCO devices, complete the **Permissions &amp;
Autostart** step for every installed plugin so HyperOS can start its protected process when Base requests it.

A brand-new camera must first be activated once in DJI Mimo. Until then, the camera keeps its Wi-Fi
access point disabled and osmodule cannot connect to it.

1. Turn on Bluetooth and Wi-Fi and open osmodule.
2. Grant the requested nearby-device permissions.
3. Power on the camera and select it from the Cameras list.
4. Approve pairing on the camera, then approve Android's camera Wi-Fi join prompt.
5. Open Modules to install the optional plugin for the connected camera.
6. Browse, preview or download media. When the viewer plugin is installed, Osmo 360 panoramic JPEGs
   and OSV clips open in it.

## Privacy

osmodule communicates with the camera on its local, internet-less network (normally `192.168.2.1`).
It has no analytics, account system or cloud upload. Cleartext HTTP is enabled because camera models
serve media locally without TLS; application code supplies only camera-local URLs. The only
user-initiated internet operation in Base is downloading an official plugin APK after **Install from
GitHub** is tapped.

## Build

Requirements: Android SDK 36, JDK 21 and Android 10+ (API 29) on the target device.

```sh
./gradlew test lint \
  :app:assembleDebug \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleDebug \
  :plugins:pocket4p:assembleDebug
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/panorama360/build/outputs/apk/debug/panorama360-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`
- `plugins/pocket4p/build/outputs/apk/debug/pocket4p-debug.apk`

All three plugins are optional. Users who only need ordinary media access install the Base APK. Release
builds are unsigned unless a local `keystore.properties` is supplied; Base and plugin releases must
use the same signing lineage. CI uploads Base, Panorama 360, Osmo 360 RC and Pocket 4P RC APKs as four separate workflow
artifacts; GitHub artifact downloads are ZIP archives, while in-app installation uses raw APK assets
from the latest published Release.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — APK boundaries, module graph and plugin trust model.
- [Development guide](docs/DEVELOPMENT.md) — code ownership, quality gates and release workflow.
- [Plugin SDK](docs/PLUGIN_SDK.md) — versioned AAR consumption and plugin implementation contract.
- [Plugin distribution model](docs/PLUGIN_MODEL.md) — official catalog, signing and threat boundary.
- [Media protocol reference](MEDIA_PROTOCOL.md) — reverse-engineered BLE, DUML and HTTP behavior.
- [Protocol map](docs/01-protocol-map.md) — packet-level command and transport index.
- [Roadmap](ROADMAP.md) — completed work, hardware gaps and planned features.
- [Third-party notices](THIRD_PARTY_NOTICES.md) — incorporated research and licensed components.

## Open-source projects, references and license

The same acknowledgements are available in the app under **About**. “Adapted” means osmodule contains
a modified or reimplemented integration; “reference” means the project informed protocol research.

| Project | How osmodule uses it | License / terms |
|---|---|---|
| [Osmosis](https://github.com/KonradIT/osmosis) | Original Android application and inherited foundation | MIT |
| [OpenPocketCine](https://github.com/erik-sutton95/OpenPocketCine) | Pocket 4P DUML commands, status interpretation and HEVC live-view behavior | Apache-2.0 |
| [yesbhautik/osmo360](https://github.com/yesbhautik/osmo360) | Osmo 360 UDP/TCP live-view handshake and AVC framing | MIT |
| [PanoForge](https://github.com/Belenos-Toutatis/PanoForge) | Osmo 360 calibration metadata and projection research | MIT |
| [dji-remote](https://github.com/dimadesu/dji-remote) | Vendored/adapted DUML framing, CRC, byte reader/writer and command payloads | MIT |
| [o-gs](https://github.com/o-gs) | Reverse-engineered protocol references | Per repository |
| [osmo-download](https://github.com/SemiConscious/osmo-download) | Osmo media discovery and download research | Reference only; no repository license file |
| [DJI-Wifi-Connect](https://github.com/sniffingpickles/DJI-Wifi-Connect) | DJI camera Wi-Fi connection and pairing reference | MIT |
| [lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble) | DJI Osmo BLE protocol reference | MIT |
| [DJI Osmo GPS Controller Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo) | Ported and modified R-SDK framing, CRC, BLE session, commands, GPS and status handling | MIT sample code; DJI EULA applies to the R-SDK protocol |

See [Third-party notices](THIRD_PARTY_NOTICES.md) and [LICENSES](LICENSES/) for detailed attribution
and bundled license texts.

osmodule's original code and its Osmosis-derived foundation are licensed under the
[MIT License](LICENSE.txt). Adapted and third-party portions remain under the licenses and terms
listed above. This is an independent project and is not affiliated with, authorized by or endorsed
by DJI. DJI and Osmo are trademarks of their respective owners.
