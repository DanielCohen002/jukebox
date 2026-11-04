package com.djcohen.voiceplay

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Canvas-drawn jukebox: chasing arch lights, pulsing colour bands, and a spinning record. Faster/brighter while [active]. */
class JukeboxView(ctx: Context) : View(ctx) {
    var active = false
    private var angle = 0f
    private var phase = 0f
    private var last = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandColors = intArrayOf(0xFFE4572E.toInt(), 0xFFF4B63F.toInt(), 0xFF3DB8A8.toInt())

    private fun mix(c: Int, k: Float): Int {   // scale colour brightness toward dark
        val f = k.coerceIn(0f, 1f)
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    private fun archPath(cx: Float, cy: Float, r: Float, legEnd: Float) = Path().apply {
        moveTo(cx - r, legEnd); lineTo(cx - r, cy)
        arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f)
        lineTo(cx + r, legEnd)
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else (now - last) / 1e9f
        last = now
        angle = (angle + dt * (if (active) 260f else 40f)) % 360f
        phase += dt * (if (active) 7f else 2f)

        val w = width.toFloat(); val h = height.toFloat()
        val R = min(w * 0.45f, h / 2.3f)
        val cx = w / 2f
        val top = (h - 2.2f * R) / 2f
        val cy = top + R
        val bottom = top + 2.1f * R
        val legEnd = bottom - 0.5f * R

        // glow behind cabinet
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(cx, cy, R * 1.5f, intArrayOf(Color.argb(if (active) 110 else 55, 244, 182, 63), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 1.5f, p); p.shader = null

        // cabinet
        val cab = Path().apply {
            moveTo(cx - R, bottom); lineTo(cx - R, cy)
            arcTo(RectF(cx - R, cy - R, cx + R, cy + R), 180f, 180f)
            lineTo(cx + R, bottom); close()
        }
        p.shader = LinearGradient(cx - R, 0f, cx + R, 0f, intArrayOf(0xFFD9C7A3.toInt(), 0xFFF7EBD0.toInt(), 0xFFD9C7A3.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(cab, p); p.shader = null

        // chasing lights around the outer arch
        val n = 17
        for (i in 0 until n) {
            val a = Math.PI + Math.PI * i / (n - 1)
            val k = (0.5f + 0.5f * sin(phase * 1.5f - i * 0.7f))
            p.color = Color.rgb(255, (150 + 90 * k).toInt(), (40 + 60 * k).toInt())
            p.alpha = (90 + 165 * k).toInt()
            c.drawCircle(cx + cos(a).toFloat() * R * 0.93f, cy + sin(a).toFloat() * R * 0.93f, R * 0.035f, p)
        }
        p.alpha = 255

        // window + bands
        p.style = Paint.Style.FILL; p.color = 0xFF0D1626.toInt()
        c.drawPath(archPath(cx, cy, R * 0.60f, legEnd).apply { close() }, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.085f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0..2) {
            val k = 0.55f + 0.45f * sin(phase - i * 1.1f)
            p.color = mix(bandColors[i], k + 0.2f)
            c.drawPath(archPath(cx, cy, R * (0.80f - 0.125f * i), legEnd - R * 0.02f), p)
        }

        // record
        val rx = cx; val ry = cy + R * 0.12f; val rr = R * 0.46f
        p.style = Paint.Style.FILL; p.color = 0xFF050505.toInt(); c.drawCircle(rx, ry, rr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFF2A2A2A.toInt()
        for (g in 1..5) c.drawCircle(rx, ry, rr * (0.36f + g * 0.12f), p)
        c.save(); c.rotate(angle, rx, ry)
        p.strokeWidth = rr * 0.07f; p.color = Color.argb(70, 255, 255, 255); p.strokeCap = Paint.Cap.ROUND
        val gloss = RectF(rx - rr * 0.78f, ry - rr * 0.78f, rx + rr * 0.78f, ry + rr * 0.78f)
        c.drawArc(gloss, 200f, 40f, false, p)
        c.drawArc(gloss, 20f, 40f, false, p)
        p.style = Paint.Style.FILL; p.color = 0xFFFF8A3D.toInt(); c.drawCircle(rx, ry, rr * 0.34f, p)
        p.color = 0xFF5A1A2B.toInt(); c.drawCircle(rx, ry - rr * 0.2f, rr * 0.05f, p)   // notch so the spin is visible
        c.restore()
        p.color = 0xFF050505.toInt(); c.drawCircle(rx, ry, rr * 0.05f, p)

        // speaker grille
        p.style = Paint.Style.FILL; p.color = 0xFF12233A.toInt()
        val gl = cx - R * 0.6f; val gr = cx + R * 0.6f; val gt = legEnd + R * 0.06f; val gb = bottom - R * 0.1f
        c.drawRoundRect(RectF(gl, gt, gr, gb), R * 0.05f, R * 0.05f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.03f; p.color = 0xFFF2E3C6.toInt(); p.strokeCap = Paint.Cap.ROUND
        for (i in 1..3) { val y = gt + (gb - gt) * i / 4f; c.drawLine(gl + R * 0.1f, y, gr - R * 0.1f, y, p) }

        // base
        p.style = Paint.Style.FILL; p.color = 0xFFE4572E.toInt()
        c.drawRoundRect(RectF(cx - R * 1.08f, bottom - R * 0.02f, cx + R * 1.08f, bottom + R * 0.1f), R * 0.05f, R * 0.05f, p)

        postInvalidateOnAnimation()
    }
}
