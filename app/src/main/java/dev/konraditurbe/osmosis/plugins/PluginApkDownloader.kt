package dev.konraditurbe.osmosis.plugins

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a catalog-owned release APK over a validated internet network. */
internal object PluginApkDownloader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_APK_BYTES = 100L * 1024L * 1024L

    enum class Failure {
        NO_INTERNET,
        HTTP,
        TOO_LARGE,
        INVALID_SOURCE,
    }

    class DownloadException(
        val failure: Failure,
        val httpStatus: Int? = null,
        cause: Throwable? = null,
    ) : IOException(failure.name, cause)

    fun download(context: Context, source: String, destination: File): File {
        val sourceUrl = runCatching { URL(source) }
            .getOrElse { throw DownloadException(Failure.INVALID_SOURCE, cause = it) }
        if (!isOfficialReleaseUrl(sourceUrl)) throw DownloadException(Failure.INVALID_SOURCE)

        val network = validatedInternetNetwork(context)
            ?: throw DownloadException(Failure.NO_INTERNET)
        val connection = runCatching {
            network.openConnection(sourceUrl) as HttpURLConnection
        }.getOrElse { throw DownloadException(Failure.NO_INTERNET, cause = it) }

        val partial = File(destination.parentFile, "${destination.name}.part")
        var completed = false
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            connection.setRequestProperty("User-Agent", "osmodule-android")

            val status = connection.responseCode
            if (status !in 200..299) throw DownloadException(Failure.HTTP, status)
            if (!isTrustedDownloadTarget(connection.url)) {
                throw DownloadException(Failure.INVALID_SOURCE)
            }
            if (connection.contentLengthLong > MAX_APK_BYTES) {
                throw DownloadException(Failure.TOO_LARGE)
            }

            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) throw DownloadException(Failure.TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the cached plugin APK")
            }
            if (!partial.renameTo(destination)) {
                throw IOException("Could not stage the downloaded plugin APK")
            }
            completed = true
            return destination
        } finally {
            connection.disconnect()
            if (!completed) partial.delete()
        }
    }

    internal fun isOfficialReleaseUrl(url: URL): Boolean =
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.path.startsWith("/pill4r/osmodule/releases/") &&
            url.path.endsWith(".apk")

    private fun isTrustedDownloadTarget(url: URL): Boolean =
        url.protocol.equals("https", ignoreCase = true) &&
            (url.host.equals("github.com", ignoreCase = true) ||
                url.host.endsWith(".githubusercontent.com", ignoreCase = true))

    private fun validatedInternetNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
