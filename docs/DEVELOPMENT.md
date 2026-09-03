# Development guide

[English](DEVELOPMENT.md) | [简体中文](DEVELOPMENT.zh-CN.md)

This document is the maintenance map for the current osmodule codebase. It explains where code
belongs, which boundaries must remain stable, and what must pass before a change is ready to merge.
The architectural rationale and Binder security model live in [ARCHITECTURE.md](ARCHITECTURE.md).

## Toolchain and applications

The repository is a Gradle multi-project Android build pinned to Android Gradle Plugin 8.13.2,
Gradle 8.14.5, Kotlin 1.9.24, compile/target SDK 36 and Java 21 bytecode. The minimum supported Android
version is Android 10 (API 29).

It produces three applications:

| Application | Gradle module | Application ID | Purpose |
|---|---|---|---|
| osmodule Base | `:app` | `dev.konraditurbe.osmosis` | Camera discovery and local media workflows |
| 360 Viewer plugin | `:plugins:panorama360` | `dev.konraditurbe.osmosis.plugin.panorama360` | Optional interactive Osmo 360 playback |
| R-SDK plugin | `:plugins:rsdk` | `dev.konraditurbe.osmosis.plugin.rsdk` | Optional Osmo 360 remote control, preview and GPS sync |

The Base package keeps the historical Osmosis namespace for upgrade and plugin-ABI compatibility.
The product name and release identity are osmodule.

## Module ownership

| Module | Owns | Must not own |
|---|---|---|
| `:app` | Application startup, external-plugin discovery, trust checks and module manager | Camera protocol implementations or plugin UI |
| `:core:common` | Small Android utilities shared across features | Feature-specific behavior |
| `:core:module-api` | In-process module contracts and registry | Concrete feature implementations |
| `:core:plugin-api` | Publishable, versioned AIDL, contract constants and bootstrap provider | Plugin business logic |
| `:core:camera-session` | Process-local camera session lease | Android UI or a transport implementation |
| `:core:panorama-renderer` | Calibration decoding and panorama rendering primitives | Media navigation or plugin lifecycle |
| `:protocol:duml` | DUML frames, CRCs, commands and payload codecs | Android framework dependencies |
| `:protocol:rsdk` | R-SDK packets and status codecs | Bluetooth or UI code |
| `:transport:ble` | BLE scanning, model identification and GATT client | Screens or media policy |
| `:camera:media` | Camera/drone media sessions, manifests, addressing, HTTP and downloads | Activities and module registration |
| `:feature:media` | Base media screens and the core media module | External-plugin implementation |
| `:feature:panorama360` | Plugin-internal 360-degree media viewer | Base application wiring or R-SDK control |
| `:feature:control-rsdk` | Reusable R-SDK controller, live preview and plugin-owned screens | Base application wiring |
| `:plugins:panorama360` | 360 Viewer application, Binder service and manifest | Base UI or media browsing |
| `:plugins:rsdk` | Plugin application, service and manifest | Base UI or direct access to Base internals |

When adding code, place wire formats in `protocol`, Android transports in `transport`, camera media
semantics in `camera`, user-facing workflows in `feature`, and only final composition in an
application module. A lower layer must not depend on a higher layer to save a few lines of glue.

## Dependency and security boundaries

External plugins are separate APKs. Base must never use `DexClassLoader`, merge plugin permissions,
or read plugin resources directly. Communication is limited to the published Plugin SDK and
Android-owned parcelables. The monorepo replaces the SDK Maven coordinate with local
`:core:plugin-api` source while developing. A compatible official plugin must:

1. expose the documented `dev.konraditurbe.osmosis.plugin.BIND` service action;
2. hold the signature-level `dev.konraditurbe.osmosis.permission.BIND_PLUGIN` permission;
3. match a package, plugin ID and capability policy in `OfficialPluginCatalog`;
4. use the same signing lineage as Base;
5. return a descriptor compatible with the AIDL protocol version; and
6. launch private UI only through the immutable `PendingIntent` returned by the service.

Camera access is exclusive. In-process clients use `CameraSessionCoordinator`; Base also queries the
plugin runtime state before opening its media transport. Binder errors fail closed.

## Build and quality gates

Run the repository-wide gate before submitting a change:

```sh
./gradlew test lint \
  :app:assembleRelease \
  :app:assembleDebug \
  :plugins:panorama360:assembleRelease \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleRelease \
  :plugins:rsdk:assembleDebug \
  :core:plugin-api:publishPluginSdkPublicationToLocalPluginSdkRepository
```

This checks pure JVM modules, Android unit tests, all module Lint tasks, both build variants of all
three APKs, and the publishable SDK AAR. Release builds run R8 code/resource shrinking; custom
manifest-loaded `AppModule` entries must have a consumer keep rule. A checkout without signing
material intentionally produces unsigned release APKs.

For faster iteration, target the changed module first:

```sh
./gradlew :protocol:duml:test
./gradlew :camera:media:testDebugUnitTest
./gradlew :feature:media:lintDebug
```

Protocol changes should include a deterministic unit test or a sanitized binary fixture. Do not
commit packet captures, BLE snoops, credentials, device logs, signing keys or raw user media. The
repository `.gitignore` excludes these common sources of sensitive or oversized data.

## Device-only verification

Unit tests do not exercise Android package installation, OEM background restrictions, Bluetooth
hardware or the camera network. Changes to those areas need a device pass in addition to Gradle.

The fresh-install plugin bootstrap path can be checked without a camera:

```sh
adb shell am instrument -w \
  dev.konraditurbe.osmosis.test/dev.konraditurbe.osmosis.plugins.PluginBindingInstrumentation
```

For camera-facing changes, record the phone model, Android version, camera model and camera firmware
in the pull request or issue. Never add credentials or an unsanitized capture to the repository.

## Release workflow

`.github/workflows/ci.yml` runs on every push to `main`, every pull request targeting `main`, and on
manual dispatch. It needs no signing secrets. The job validates the Gradle Wrapper, restores the
Gradle cache, runs all JVM and Android unit tests, runs repository-wide Lint, builds all three Debug APKs,
and retains test reports, Lint reports and each APK as a separate workflow artifact for 14 days.
GitHub wraps every Actions artifact in a ZIP even when it contains one APK. Concurrent runs on the
same ref are cancelled so that only the newest revision consumes runner time.

Application tags matching `v*` trigger `.github/workflows/build_app.yml`. CI validates the Gradle
wrapper, runs the full quality gate, builds Base and both plugins with the same signing configuration,
uploads each APK as a separate build artifact and creates a GitHub release with stable raw asset names
(`app-release.apk`, `panorama360-release.apk`, and `rsdk-release.apk`). After all gates pass, the
workflow publishes the release so the module manager's latest-release URLs become available. SDK tags
matching `plugin-sdk-v*` trigger
`.github/workflows/publish_plugin_sdk.yml`, which verifies the tag/version match and publishes the
release AAR to GitHub Packages.

The required repository secrets are `APP_KEYSTORE`, `STOREPASSWORD`, `KEYPASSWORD` and `KEYALIAS`.
They are decoded only inside CI and must not be committed. Before tagging a release:

1. update application and plugin versions when their artifacts change;
2. run the full quality gate;
3. hardware-check the affected camera or plugin workflow;
4. update README support claims, `ROADMAP.md` and protocol documentation when behavior changed; and
5. install Base and each affected signed plugin to verify their signing lineage and Binder handshake.

## Documentation maintenance

Documentation is part of the code change:

- update `README.md` and `README.zh-CN.md` when the product scope, installation flow or supported
  hardware changes;
- update `ARCHITECTURE.md` and `ARCHITECTURE.zh-CN.md` when module ownership or an APK boundary
  changes;
- update `DEVELOPMENT.md` and `DEVELOPMENT.zh-CN.md` when the development process or quality gates
  change;
- update `PLUGIN_SDK.md` and `PLUGIN_SDK.zh-CN.md` when the contract or Maven version changes;
- update `PLUGIN_MODEL.md` and `PLUGIN_MODEL.zh-CN.md` when trust, signing or distribution changes;
- update `ROADMAP.md` and `ROADMAP.zh-CN.md` together when priorities or hardware gaps change;
- update `MEDIA_PROTOCOL.md` and `docs/01-protocol-map.md` when wire behavior is measured;
- distinguish hardware-verified facts from inference; and
- preserve attribution in `THIRD_PARTY_NOTICES.md` when incorporating external work.
