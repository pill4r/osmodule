package dev.pillar.osmodule.ui

import dev.pillar.osmodule.core.CameraFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Compact, versioned last-known gallery used while a shared-Wi-Fi remote handoff refreshes. */
internal object GallerySnapshotCache {
    private const val MAGIC = 0x4F534D47 // OSMG
    private const val VERSION = 1
    private const val MAX_FILES = 500
    private const val MAX_ENCODED_BYTES = 2 * 1024 * 1024

    fun encode(files: List<CameraFile>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            val kept = files.take(MAX_FILES)
            output.writeInt(kept.size)
            kept.forEach { file ->
                output.writeUTF(file.path)
                output.writeUTF(file.thumbPath)
                output.writeInt(file.storage)
                output.writeNullableUtf(file.resLabel)
                output.writeNullableUtf(file.proxyPath)
                output.writeLong(file.handle)
                output.writeLong(file.sizeBytes)
                output.writeBoolean(file.starred)
                output.writeNullableUtf(file.resolution)
                output.writeInt(file.durationSec)
                output.writeLong(file.cmdHandle)
                output.writeInt(file.group)
                output.writeLong(file.fileIndex)
                output.writeLong(file.mtimeEpoch)
                output.writeBoolean(file.storageKnown)
                output.writeBoolean(file.handleShared)
                output.writeInt(file.mediaType)
                output.writeLong(file.handleCandidate)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String?): List<CameraFile>? {
        if (encoded.isNullOrBlank() || encoded.length > MAX_ENCODED_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == VERSION)
                val count = input.readInt()
                require(count in 0..MAX_FILES)
                List(count) {
                    CameraFile(
                        path = input.readUTF(),
                        thumbPath = input.readUTF(),
                        storage = input.readInt(),
                        resLabel = input.readNullableUtf(),
                        proxyPath = input.readNullableUtf(),
                        handle = input.readLong(),
                        sizeBytes = input.readLong(),
                        starred = input.readBoolean(),
                        resolution = input.readNullableUtf(),
                        durationSec = input.readInt(),
                        cmdHandle = input.readLong(),
                        group = input.readInt(),
                        fileIndex = input.readLong(),
                        mtimeEpoch = input.readLong(),
                        storageKnown = input.readBoolean(),
                        handleShared = input.readBoolean(),
                        mediaType = input.readInt(),
                        handleCandidate = input.readLong(),
                    )
                }
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableUtf(): String? =
        if (readBoolean()) readUTF() else null
}
