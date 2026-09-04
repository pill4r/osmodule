# Plugin SDK

[English](PLUGIN_SDK.md) | [简体中文](PLUGIN_SDK.zh-CN.md)

`dev.konraditurbe.osmodule:plugin-sdk:1.1.0` is the standalone Android AAR for the osmodule
cross-APK contract. It contains `IOsmosisPlugin.aidl`, `PluginContract`, `PluginDescriptor` and the
reusable signature-protected `PluginBootstrapProvider`. It does not depend on Base or any camera,
transport, protocol or UI implementation.

## Consume from another repository

GitHub Packages requires credentials with package-read access, including for public Maven packages:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/pill4r/osmodule") {
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

```kotlin
// plugin application build.gradle.kts
dependencies {
    implementation("dev.konraditurbe.osmodule:plugin-sdk:1.1.0")
}
```

The reference plugins use that exact coordinate. The root build substitutes the local
`:core:plugin-api` project only while working in this monorepo.

## Minimum plugin shape

A plugin is a normal Android application with its own application ID and lifecycle. Its manifest:

- exports a service for `PluginContract.BIND_ACTION`;
- protects the service with `PluginContract.BIND_PERMISSION`;
- declares ID, name, version, protocol range and capabilities as service metadata;
- exports `PluginBootstrapProvider` at `<applicationId>.bootstrap` under the same permission; and
- keeps feature activities private (`exported=false`).

The Binder service must enforce the Base caller, return a descriptor exactly matching the manifest,
and return immutable, explicit PendingIntents. Request Bundles must use `PluginContract` keys and be
treated as untrusted input. See `plugins/panorama360` for a minimal panel plugin and `plugins/rsdk`
for runtime-state and permission-management examples.

## Versioning

The Maven version and Binder protocol version are separate:

SDK 1.1.0 adds the Pocket 4P official package/plugin identities, its remote-panel capability and the
optional camera-model request key. These are additive Bundle constants, so the Binder protocol stays
at version 1 and existing compatible plugins remain valid.

- Patch/minor SDK releases may add optional keys, constants or helpers without changing protocol 1.
- Removing or changing an AIDL method, key meaning or required value needs a new Binder protocol.
- Plugins declare the inclusive protocol range they actually support.
- Base selects only plugins whose range contains `PluginContract.PROTOCOL_VERSION`.

Update `pluginSdkVersion` in `gradle.properties`, tests and both language versions of this document
together. A `plugin-sdk-vX.Y.Z` tag publishes the release AAR to GitHub Packages. Validate locally:

```sh
./gradlew :core:plugin-api:test \
  :core:plugin-api:publishPluginSdkPublicationToLocalPluginSdkRepository
```

The local Maven repository is written under `core/plugin-api/build/repo`.

## Trust is not granted by the SDK

The public SDK lets another project compile the protocol. The official Base still accepts only
same-signature identities in `OfficialPluginCatalog`; publishing an AAR does not create an open
plugin marketplace. See [Plugin distribution model](PLUGIN_MODEL.md).
