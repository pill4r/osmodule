# Development guide

This document is the maintenance map for the current osmodule codebase. It explains where code
belongs, which boundaries must remain stable, and what must pass before a change is ready to merge.
The architectural rationale and Binder security model live in [ARCHITECTURE.md](ARCHITECTURE.md).

## Toolchain and applications

The repository is a Gradle multi-project Android build pinned to Android Gradle Plugin 8.13.2,
Gradle 8.14.5, Kotlin 1.9.24, compile/target SDK 36 and Java 21 bytecode. The minimum supported Android
version is Android 10 (API 29).

It produces two applications:

| Application | Gradle module | Application ID | Purpose |
|---|---|---|---|
| osmodule Base | `:app` | `dev.konraditurbe.osmosis` | Camera discovery and local media workflows |
| R-SDK plugin | `:plugins:rsdk` | `dev.konraditurbe.osmosis.plugin.rsdk` | Optional Osmo 360 remote control, preview and GPS sync |

The Base package keeps the historical Osmosis namespace for upgrade and plugin-ABI compatibility.
The product name and release identity are osmodule.

## Module ownership

| Module | Owns | Must not own |
|---|---|---|
| `:app` | Application startup, external-plugin discovery, trust checks and module manager | Camera protocol implementations or plugin UI |
| `:core:common` | Small Android utilities shared across features | Feature-specific behavior |
| `:core:module-api` | In-process module contracts and registry | Concrete feature implementations |
| `:core:plugin-api` | Versioned AIDL, parcelables and bootstrap provider | R-SDK business logic |
| `:core:camera-session` | Process-local camera session lease | Android UI or a transport implementation |
| `:core:panorama-renderer` | Calibration decoding and panorama rendering primitives | Media navigation or plugin lifecycle |
| `:protocol:duml` | DUML frames, CRCs, commands and payload codecs | Android framework dependencies |
| `:protocol:rsdk` | R-SDK packets and status codecs | Bluetooth or UI code |
| `:transport:ble` | BLE scanning, model identification and GATT client | Screens or media policy |
| `:camera:media` | Camera/drone media sessions, manifests, addressing, HTTP and downloads | Activities and module registration |
| `:feature:media` | Base media screens and the core media module | External-plugin implementation |
| `:feature:panorama360` | Bundled optional 360-degree media viewer | R-SDK control |
| `:feature:control-rsdk` | Reusable R-SDK controller, live preview and plugin-owned screens | Base application wiring |
| `:plugins:rsdk` | Plugin application, service and manifest | Base UI or direct access to Base internals |

When adding code, place wire formats in `protocol`, Android transports in `transport`, camera media
semantics in `camera`, user-facing workflows in `feature`, and only final composition in an
application module. A lower layer must not depend on a higher layer to save a few lines of glue.

## Dependency and security boundaries

External plugins are separate APKs. Base must never use `DexClassLoader`, merge plugin permissions,
or read plugin resources directly. Communication is limited to `:core:plugin-api` and Android-owned
parcelables. A compatible plugin must:

1. expose the documented `dev.konraditurbe.osmosis.plugin.BIND` service action;
2. hold the signature-level `dev.konraditurbe.osmosis.permission.BIND_PLUGIN` permission;
3. use the same signing lineage as Base;
4. return a descriptor compatible with the AIDL protocol version; and
5. launch private UI only through the immutable `PendingIntent` returned by the service.

Camera access is exclusive. In-process clients use `CameraSessionCoordinator`; Base also queries the
plugin runtime state before opening its media transport. Binder errors fail closed.

## Build and quality gates

Run the repository-wide gate before submitting a change:

```sh
./gradlew test lint \
  :app:assembleRelease \
  :app:assembleDebug \
  :plugins:rsdk:assembleRelease \
  :plugins:rsdk:assembleDebug
```

This checks pure JVM modules, Android unit tests, all module Lint tasks, and both build variants of
both APKs. A checkout without signing material intentionally produces unsigned release APKs.

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

Tag pushes trigger `.github/workflows/build_app.yml`. CI validates the Gradle wrapper, runs the full
quality gate, builds Base and plugin with the same signing configuration, uploads build artifacts and
creates a draft GitHub release.

The required repository secrets are `APP_KEYSTORE`, `STOREPASSWORD`, `KEYPASSWORD` and `KEYALIAS`.
They are decoded only inside CI and must not be committed. Before tagging a release:

1. update application and plugin versions when their artifacts change;
2. run the full quality gate;
3. hardware-check the affected camera or plugin workflow;
4. update README support claims, `ROADMAP.md` and protocol documentation when behavior changed; and
5. install both signed release APKs together to verify their signing lineage and Binder handshake.

## Documentation maintenance

Documentation is part of the code change:

- update `README.md` when the product scope, installation flow or supported hardware changes;
- update `ARCHITECTURE.md` when module ownership or an APK boundary changes;
- update `MEDIA_PROTOCOL.md` and `docs/01-protocol-map.md` when wire behavior is measured;
- distinguish hardware-verified facts from inference; and
- preserve attribution in `THIRD_PARTY_NOTICES.md` when incorporating external work.
