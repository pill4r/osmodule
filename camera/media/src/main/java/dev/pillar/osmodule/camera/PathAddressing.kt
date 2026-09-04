package dev.pillar.osmodule.camera

import dev.pillar.osmodule.core.CameraFile
import dev.pillar.osmodule.core.MediaAddressing

/**
 * Path-based addressing — the Osmo camera scheme, and everything `main` supports.
 *
 * ```
 * GET /v2?storage=<mount index>&path=DCIM/DJI_001/DJI_20260329115359_0211_D.MP4
 * ```
 *
 * `storage` is a **mount index resolved by probing**, not a fixed SD/internal mapping: an Xtra served
 * its SD at 0 and internal at 1, a Nano serves its only store at 1. See `StorageRules`.
 *
 * Nothing here knows about DCF indices, and [DcfAddressing][dev.pillar.osmodule.dcf.DcfAddressing]
 * knows nothing about paths.
 */
object PathAddressing : MediaAddressing {

    /**
     * A `/v2` URL for an arbitrary path on [storage] — for files the manifest never listed, such as the
     * `_002…` frames of a burst group, which are enumerated after the fact.
     */
    fun byPath(storage: Int, path: String): String = "/v2?storage=$storage&path=$path"

    override fun original(f: CameraFile): String = byPath(f.storage, f.path)

    override fun thumbnail(f: CameraFile): String = byPath(f.storage, f.thumbPath)

    /**
     * 1. the manifest-listed proxy (the Nano/360 list their `.LRF`/`.LRV`);
     * 2. else the **derived** sidecar proxy for this camera family — the Xtra / Action 5 Pro writes a
     *    low-res `.XRF` next to each clip (same base name and folder) but does NOT list it, so the path
     *    is derived directly rather than probing several extensions;
     * 3. the full-resolution file, as a last resort.
     *
     * Duplicates collapse, so this is normally just `[proxy, full-res]`.
     */
    override fun previewChain(f: CameraFile): List<String> {
        return previewChain(f, preferredProxyExtension = null)
    }

    /**
     * Preview chain with a device-model override for families whose filename prefix is ambiguous.
     * Osmo 360 and the Action family both use `CAM_`, but the 360 writes `.LRF` while an Action 5
     * writes `.XRF`; the caller that knows the connected model supplies the disambiguating extension.
     */
    fun previewChain(f: CameraFile, preferredProxyExtension: String?): List<String> {
        val urls = LinkedHashSet<String>()
        f.proxyPath?.let { urls.add(byPath(f.storage, it)) }
        (preferredProxyExtension ?: proxyExt(f.name))?.let {
            urls.add(byPath(f.storage, "${f.path.substringBeforeLast('.', f.path)}.${it.uppercase()}"))
        }
        urls.add(original(f))
        return urls.toList()
    }

    /**
     * Low-res proxy extension pinned by camera family, read from the file-naming prefix (which is the
     * family tell): the **Action family** (`CAM_` — Xtra / Action 5 Pro) writes an unlisted `.XRF`
     * sidecar; **DJI-proper** (`DJI_` — Nano) uses `.LRF`. Osmo 360 also uses `CAM_`, but writes
     * `.LRF`, so its model-aware caller must use [previewChain] with an override. Null for an unknown
     * convention means no derived proxy, just the full-resolution file.
     */
    private fun proxyExt(name: String): String? = when {
        name.startsWith("CAM_") -> "XRF"
        name.startsWith("DJI_") -> "LRF"
        else -> null
    }
}
