# Plugin distribution and trust model

[English](PLUGIN_MODEL.md) | [简体中文](PLUGIN_MODEL.zh-CN.md)

## Decision

The official osmodule distribution uses an **official-only, same-signature plugin model**. It is not
an open marketplace. The current catalog contains:

| Plugin | Package | Required capability |
|---|---|---|
| 360 Viewer | `dev.pillar.osmodule.plugin.panorama360` | `camera.media.360-view` |
| Osmo 360 RC (R-SDK) | `dev.pillar.osmodule.plugin.rsdk` | `camera.rsdk.remote-panel`, `camera.session.owner` |
| Pocket 4P RC | `dev.pillar.osmodule.plugin.pocket4p` | `camera.pocket4p.remote-panel`, `camera.session.owner` |

Base shows only catalog entries in Modules and rejects an installed or selected APK when its package,
plugin ID or capability set falls outside that catalog. Matching the signing key alone is insufficient.

Each catalog entry also owns an HTTPS URL for its APK in the latest published GitHub Release. The
module manager downloads only repository release URLs, caps the download size, and uses an Android
network validated for internet access so a camera's local Wi-Fi binding cannot intercept the request.
The downloaded archive passes the same package, descriptor, capability and signer checks as a locally
selected APK before Android's user-confirmed Package Installer is opened.

## Why this model

Plugin requests may contain a camera network handle, local media URLs, Wi-Fi credentials or a BLE
camera address. Plugins can also claim exclusive camera-session ownership and return UI launch tokens.
Those powers are too broad for silent trust based only on a public intent action. Same-signature plus
an explicit catalog makes each new capability a reviewed Base release decision.

The isolation still provides product value: optional code and permissions leave Base, plugins can be
installed or removed independently, process crashes are isolated, and the Binder protocol can remain
stable while implementations evolve.

## Publishing

- Base and every official release plugin are built from reviewed source and signed with the same
  release lineage.
- App release tags are `vX.Y.Z`; CI builds Base and all three plugin APKs, uploads each APK as a separate
  Actions artifact, and publishes a release after the quality gate passes.
- Plugin SDK tags are `plugin-sdk-vX.Y.Z`; CI publishes the AAR to GitHub Packages.
- Debug builds share Android's debug signer and are for local testing only.
- Published release assets provide the stable raw APK URLs used by the module manager. Actions
  artifacts are ZIP-wrapped, expire, and are not used as in-app installation sources.
- Base verifies downloaded and locally selected archives before handing them to Android's
  user-confirmed Package Installer.

## Adding an official plugin

1. Reserve a stable application ID and plugin ID.
2. Define the smallest capability and request-key surface in the Plugin SDK.
3. Add the identity, required capabilities and complete allowed-capability set to
   `OfficialPluginCatalog`.
4. Add package/provider visibility and a Modules entry in Base.
5. Add descriptor, archive-verification and Binder-boundary tests.
6. Build and sign the APK with the official lineage; document permissions and camera data received.
7. Update architecture, SDK and distribution documentation in English and Chinese.

Capability expansion is reviewed like a permission expansion. An existing official plugin cannot add
a new capability until Base explicitly allows it.

## Third-party and fork path

Third-party authors may use the MIT-licensed SDK and source to build their own Base/plugin set. They
must use their own signing lineage and change their Base catalog. Their APK cannot be installed as a
trusted plugin into the official osmodule Base. Supporting arbitrary third-party plugins later would
require a separate consent, permission-scoping, revocation and data-disclosure design; it must not be
enabled merely by weakening the signer or catalog checks.
