package com.djcohen.voiceplay

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The offline wake-word model (about 41 MB) is not inside the APK. It is downloaded once, with the user's OK,
 * from this repo's `model-v1` release, checked against a fixed SHA-256, and unpacked into app storage.
 */
object ModelStore {
    const val MODEL_URL = "https://github.com/DanielCohen002/jukebox/releases/download/model-v1/jukebox-model-v1.zip"
    private const val SHA256 = "9e3e3c8ff3c0937b1441fee51193d50d1176caed8b17e683b3dc3b4e77a1af3a"
    const val SIZE_MB = 41

    private fun dir(ctx: Context) = File(ctx.filesDir, "model-v1")

    fun isReady(ctx: Context) = File(dir(ctx), ".complete").exists()

    /** Path of the unpacked model; throws if it has not been downloaded yet. */
    fun ensure(ctx: Context): String {
        check(isReady(ctx)) { "speech model not downloaded" }
        return dir(ctx).absolutePath
    }

    /** Blocking, so call it off the main thread. [onProgress] gets 0..100. Throws on any problem and leaves nothing half-done. */
    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        val tmpZip = File(ctx.cacheDir, "model.zip.part")
        val tmpDir = File(ctx.filesDir, "model-v1.tmp")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val c = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                check(c.responseCode == 200) { "download answered ${c.responseCode}" }
                val total = c.contentLengthLong
                var got = 0L
                c.inputStream.use { i -> tmpZip.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); got += n
                        if (total > 0) onProgress((got * 90 / total).toInt())
                    }
                } }
                check(total <= 0 || got == total) { "download cut short" }
            } finally { c.disconnect() }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            check(hex == SHA256) { "the downloaded file does not match its expected checksum" }

            tmpDir.deleteRecursively(); tmpDir.mkdirs()
            val root = tmpDir.canonicalPath + File.separator
            ZipInputStream(tmpZip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = File(tmpDir, e.name)
                    check(out.canonicalPath.startsWith(root)) { "bad path in the model archive" }
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
            onProgress(98)
            val dest = dir(ctx); dest.deleteRecursively()
            check(tmpDir.renameTo(dest)) { "could not finish installing the model" }
            File(dest, ".complete").writeText("ok")
            onProgress(100)
        } finally { tmpZip.delete(); tmpDir.deleteRecursively() }
    }
}
