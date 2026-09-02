# Modular architecture

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE.zh-CN.md)

osmodule is delivered as a core media application, a bundled optional 360° viewer, and an independently
installed remote-control plugin APK. The plugin owns its code, resources, permissions and Android lifecycle;
osmodule Base never uses `DexClassLoader`, loads foreign resources or merges optional permissions into its APK.

## APK and module graph

```text
osmodule Base — dev.konraditurbe.osmosis
  :app
    ├─ :core:module-api
    ├─ :core:plugin-api       (AIDL client contract)
    ├─ :feature:media          (pair, browse, preview, download)
         ├─ :camera:media
         ├─ :transport:ble
         ├─ :protocol:duml
         └─ :core:camera-session
    └─ :feature:panorama360    (optional bundled Osmo 360 viewer)

osmodule R-SDK Plugin — dev.konraditurbe.osmosis.plugin.rsdk
  :plugins:rsdk
    ├─ :core:plugin-api       (AIDL service contract)
    └─ :feature:control-rsdk
         ├─ :protocol:rsdk
         ├─ :transport:ble
         └─ :core:camera-session
```

`core:camera-session` remains the in-process lock used by transports inside each APK. Base additionally
checks the R-SDK plugin's runtime state over Binder before opening media transport, so a background GPS
session cannot race Base for the camera's BLE link.

## AIDL boundary

All plugins expose one service action:

```text
dev.konraditurbe.osmosis.plugin.BIND
```

The version-1 `IOsmosisPlugin` surface is deliberately small:

- `getProtocolVersion()` selects the wire contract.
- `getDescriptor()` returns the plugin id, version, supported protocol range and capabilities.
- `getRuntimeState()` reports cross-process resource ownership such as an active camera session.
- `createPanelIntent(request)` returns an immutable `PendingIntent` for plugin-owned UI.

Base binds only long enough to perform one operation, then unbinds. Plugin activities remain
`exported=false`; the capability-bearing `PendingIntent` is the only UI launch token. Each plugin also
exposes a shared bootstrap ContentProvider under the same signature permission. Base calls it before
every short service bind; the synchronous IPC starts or confirms the plugin process without crossing
OEM background-Activity policy and returns before Base binds. `FLAG_STOPPED` is not used as a readiness
signal because HyperOS can kill the process while leaving the package unstopped. Bluetooth,
location, foreground-service and notification permissions are requested inside the R-SDK plugin,
not Base.

## Trust checks

Base applies all of these checks before using a plugin:

1. Discover only services matching the explicit plugin action declared in Base's `<queries>` block.
2. Require the service to be exported under
   `dev.konraditurbe.osmosis.permission.BIND_PLUGIN`, a signature-level permission owned by Base.
3. Compare the plugin APK signer SHA-256 with Base's current signing lineage.
4. Validate manifest id/version/protocol/capabilities, then compare them with the descriptor returned
   over Binder to catch identity changes between discovery and binding.
5. Require the Binder caller in each plugin service to have Base's package name. Android's signature
   permission is still the primary authorization; this is an additional caller check.

Every release APK must therefore use the same signing lineage. Debug APKs naturally share Android's
debug key. The Gradle release configurations read the same root `keystore.properties` file.

## Installation and updates

The Modules screen shows the always-on core media feature, bundled optional modules, each module's
supported camera models, and external plugin compatibility errors. The 360° viewer and remote-control
module both declare `osmo360`; the media core declares no restriction and therefore applies to all supported
cameras. For local plugin installation:

1. The user selects an APK with Android's document picker.
2. Base copies it to private cache and verifies its exact package name and signing certificate.
3. Base hands a read-only `content://` URI to Android's Package Installer.
4. Android asks the user to approve the install or update; a normal app cannot install silently.
5. On success, Base rediscovers the service and opens the plugin-owned permission guide.

Xiaomi/Redmi/POCO builds add an OEM autostart AppOp to this lifecycle. When HyperOS denies it, both a
valid service bind and provider acquisition can be rejected even though package visibility, signer and
signature permission checks all pass. Base reports this case as an autostart-policy failure and opens
HyperOS's per-app permission editor from the Modules screen; it never attempts to grant the OEM AppOp
itself.

## Runtime flows

### Open R-SDK control

```text
camera selected in Base
  → Base tears down media BLE/Wi-Fi/datalink
  → discover + signature/protocol check
  → call protected bootstrap ContentProvider and wait for process readiness
  → bind IOsmosisPlugin
  → plugin creates immutable PendingIntent with camera target
  → private R-SDK Activity requests its own permissions and connects
  → Base unbinds from the discovery service
```

Remote control and GPS use one `RsdkSessionHub` inside the plugin. The remote Activity disconnects its
consumer when it finishes; GPS may continue as a foreground service. If GPS or a backgrounded remote
panel still owns the session, `getRuntimeState()` causes Base to block media connection until it stops.

## Build outputs

```sh
./gradlew test \
  :app:assembleDebug \
  :plugins:rsdk:assembleDebug
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`

The fresh-install bootstrap/Binder path has a device regression that does not require a camera or UI
automation. Install Base plus R-SDK and its test APK, leave the plugin in `stopped/notLaunched`, then
run:

```sh
adb shell am instrument -w \
  dev.konraditurbe.osmosis.test/dev.konraditurbe.osmosis.plugins.PluginBindingInstrumentation
```

## Boundary rules

1. Base and external plugins communicate only through `:core:plugin-api` AIDL and Android parcelables.
2. External code/resources are never class-loaded into Base.
3. Optional permissions and services belong to their plugin manifests.
4. Protocol modules cannot depend on UI or application modules.
5. A plugin must remain useful when Base is killed, and Base must tolerate plugin death or uninstall.
6. Binder errors fail closed for camera ownership and plugin identity checks.
