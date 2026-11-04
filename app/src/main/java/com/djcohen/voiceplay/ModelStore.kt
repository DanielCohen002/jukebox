package com.djcohen.voiceplay

import android.content.Context
import java.io.File

/** Copies the bundled wake-word model out of the APK assets once, since the Vosk engine needs real files. */
object ModelStore {
    private const val ASSET = "model"

    fun ensure(ctx: Context): String {
        val dest = File(ctx.filesDir, "model-v1")
        val done = File(dest, ".complete")
        if (!done.exists()) {
            dest.deleteRecursively()
            copy(ctx, ASSET, dest)
            done.writeText("ok")
        }
        return dest.absolutePath
    }

    private fun copy(ctx: Context, assetPath: String, target: File) {
        val children = ctx.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        } else {
            target.mkdirs()
            for (c in children) copy(ctx, "$assetPath/$c", File(target, c))
        }
    }
}
