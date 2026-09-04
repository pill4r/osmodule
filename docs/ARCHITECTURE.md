# Modular architecture

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE.zh-CN.md)

osmodule has one lean Base APK and three optional official plugin APKs. The 360° Viewer, Osmo 360
RC (R-SDK) and Pocket 4P RC own their code, resources, permissions and Android lifecycle. Base never uses
`DexClassLoader`, loads plugin resources, or merges optional plugin components into its APK.

## APK and module graph

```text
Base — dev.konraditurbe.osmosis
  :app
    ├─ :core:module-api
    ├─ :core:plugin-api                 AIDL client contract
    └─ :feature:media                   pair, browse, flat-preview, download
         ├─ :camera:media
         ├─ :transport:ble
         ├─ :protocol:duml
         └─ :core:camera-session

360 Viewer — dev.konraditurbe.osmosis.plugin.panorama360
  :plugins:panorama360
    ├─ dev.konraditurbe.osmodule:plugin-sdk:1.1.0
    └─ :feature:panorama360
         └─ :core:panorama-renderer

Osmo 360 RC (R-SDK) — dev.konraditurbe.osmosis.plugin.rsdk
  :plugins:rsdk
    ├─ dev.konraditurbe.osmodule:plugin-sdk:1.1.0
    └─ :feature:control-rsdk
         ├─ :protocol:rsdk
         ├─ :transport:ble
         └─ :core:camera-session

Pocket 4P RC — dev.konraditurbe.osmosis.plugin.pocket4p
  :plugins:pocket4p
    ├─ dev.konraditurbe.osmodule:plugin-sdk:1.1.0
    └─ :feature:control-pocket4p
         ├─ :core:common
         ├─ :core:module-api
         ├─ :core:camera-session
         ├─ :camera:media
         └─ :protocol:duml
```

The three plugin build files depend on a versioned Maven coordinate, not directly on
`:core:plugin-api`. This monorepo substitutes that coordinate with the local source project for
atomic development. An out-of-tree plugin can consume the published AAR without including Base.
The official reference plugins still reuse implementation libraries from this repository; that is
source sharing, not an APK/runtime dependency on Base.

## Runtime boundary

All plugins expose `dev.konraditurbe.osmosis.plugin.BIND`. Protocol v1 deliberately has four calls:

- `getProtocolVersion()` selects the wire contract;
- `getDescriptor()` returns identity, version, protocol range and capabilities;
- `getRuntimeState()` reports cross-process ownership such as an active camera session; and
- `createPanelIntent(request)` returns an immutable `PendingIntent` for private plugin UI.

Base binds only for one operation and then unbinds. Plugin activities stay `exported=false`; the
PendingIntent is the launch token. A shared signature-protected bootstrap provider starts or confirms
the plugin process before each short bind, including on OEM Android builds that kill background apps.

When Base opens a 360° clip it sends only the title, Osmo model key, local preview URLs and the
Android `Network` already connected to the camera. The viewer binds its own process to that network
for playback and restores the prior process network when it closes. No viewer Activity is packaged
in Base.

## Official-only trust policy

The public osmodule build intentionally accepts official plugins only. Every discovered or locally
selected APK must pass all of these checks:

1. exact package name is present in Base's official catalog;
2. manifest plugin ID matches the catalog entry;
3. required capabilities are present and no undeclared capability is requested;
4. service and bootstrap provider use the Base-owned signature permission;
5. APK signing lineage intersects Base's signing lineage;
6. protocol range includes the Base protocol; and
7. the Binder descriptor exactly equals the signed manifest descriptor.

The plugin service additionally checks that the Binder caller UID owns the Base package. The SDK is
public for reproducible development and forks; it does not make arbitrary third-party APKs trusted by
the official Base. See [Plugin distribution model](PLUGIN_MODEL.md).

## Camera-session ownership

`core:camera-session` is the process-local lock used by transports inside each APK. Base also queries
plugins advertising `camera.session.owner` before opening its media transport. Osmo 360 RC and GPS
share one R-SDK session hub in their plugin process, while Pocket 4P RC reports its DUML session
through the same cross-process ownership contract. Binder errors fail closed for ownership and
identity checks.

## Build outputs

```sh
./gradlew test lint \
  :app:assembleDebug \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleDebug \
  :plugins:pocket4p:assembleDebug
```

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/panorama360/build/outputs/apk/debug/panorama360-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`
- `plugins/pocket4p/build/outputs/apk/debug/pocket4p-debug.apk`

## Boundary rules

1. Base and plugins communicate only through the versioned Plugin SDK and Android parcelables.
2. External code and resources are never class-loaded into Base.
3. Optional UI, permissions and services belong to their plugin APK.
4. Protocol modules cannot depend on UI or application modules.
5. Base tolerates plugin death or uninstall; plugins tolerate Base being killed.
6. A new official package, plugin ID or capability requires an explicit catalog and documentation change.
