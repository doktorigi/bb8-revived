package com.doktorigi.bb8

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** BB-8 palette: deep-space background, droid white, droid orange, a lightsaber-blue accent. */
object Palette {
    const val SPACE = 0xFF070A12.toInt()
    const val CARD = 0xCC121829.toInt()
    const val CARD_EDGE = 0xFF243049.toInt()
    const val CONTROL = 0xFF1B2336.toInt()
    const val CONTROL_EDGE = 0xFF34415E.toInt()
    const val ORANGE = 0xFFF26522.toInt()
    const val ORANGE_LIGHT = 0xFFFF9A4D.toInt()
    const val WHITE = 0xFFF4F4F2.toInt()
    const val TEXT = 0xFFC9CED9.toInt()
    const val MUTED = 0xFF7D879C.toInt()
    const val SABER = 0xFF4FC3F7.toInt()
    const val GOOD = 0xFF4CD787.toInt()
    const val BAD = 0xFFE5484D.toInt()
}

fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Rounded fill (optionally gradient + outline) with a touch ripple. */
fun pill(ctx: Context, colors: IntArray, stroke: Int = 0, radiusDp: Float = 999f): Drawable {
    val shape = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (colors.size == 1) intArrayOf(colors[0], colors[0]) else colors).apply {
        cornerRadius = ctx.dpf(radiusDp)
        if (stroke != 0) setStroke(ctx.dpf(1.5f).roundToInt(), stroke)
    }
    return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null)
}

fun card(ctx: Context) = GradientDrawable().apply {
    cornerRadius = ctx.dpf(22f)
    setColor(Palette.CARD)
    setStroke(ctx.dpf(1f).roundToInt(), Palette.CARD_EDGE)
}

/** Static starfield with two faint nebula glows: drawn once per size, no assets. */
class Starfield(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stars = FloatArray(0)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = Random(8) // same sky every launch
        stars = FloatArray(220 * 4) { i ->
            when (i % 4) {
                0 -> r.nextFloat() * w
                1 -> r.nextFloat() * h
                2 -> context.dpf(0.4f + r.nextFloat() * r.nextFloat() * 1.6f) // mostly tiny, a few bright
                else -> 0.25f + r.nextFloat() * 0.75f
            }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Palette.SPACE)
        glow(c, width * 0.9f, height * 0.12f, width * 0.8f, 0x30F26522)
        glow(c, width * 0.05f, height * 0.85f, width * 0.9f, 0x264FC3F7)
        paint.shader = null
        for (i in stars.indices step 4) {
            paint.color = Palette.WHITE
            paint.alpha = (stars[i + 3] * 255).toInt()
            c.drawCircle(stars[i], stars[i + 1], stars[i + 2], paint)
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.shader = RadialGradient(x, y, r, color, 0, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
    }
}

/** Drag pad styled as BB-8 seen from above: up = forward. Reports amount 0..1 and heading in degrees clockwise. */
class Joystick(ctx: Context, private val onMove: (Float, Int) -> Unit) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var kx = 0f
    private var ky = 0f
    private var heading = 0
    private var active = false

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dpf(6f)

        // pad: dark dish, rings, compass ticks
        fill.shader = RadialGradient(cx, cy, r, 0xFF1D2640.toInt(), 0xFF0C111E.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        line.strokeWidth = context.dpf(2f)
        line.color = if (active) Palette.ORANGE else 0xFF3A4766.toInt()
        c.drawCircle(cx, cy, r, line)
        line.strokeWidth = context.dpf(1f)
        line.color = 0x33FFFFFF
        c.drawCircle(cx, cy, r * 0.66f, line)
        c.drawCircle(cx, cy, r * 0.33f, line)
        line.strokeWidth = context.dpf(3f)
        for (k in 0 until 4) {
            val a = k * Math.PI / 2
            line.color = if (k == 0) Palette.ORANGE else 0x55FFFFFF // orange tick = forward
            c.drawLine(cx + (sin(a) * r * 0.88f).toFloat(), cy - (cos(a) * r * 0.88f).toFloat(),
                cx + (sin(a) * r).toFloat(), cy - (cos(a) * r).toFloat(), line)
        }

        // knob: BB-8 body panel — white disc, orange ring, silver hub
        val kr = r * 0.26f
        val x = cx + kx
        val y = cy + ky
        fill.color = if (active) 0x55F26522 else 0x22F26522
        c.drawCircle(x, y, kr * 1.45f, fill)
        fill.color = Palette.WHITE
        c.drawCircle(x, y, kr, fill)
        line.color = Palette.ORANGE
        line.strokeWidth = kr * 0.22f
        c.drawCircle(x, y, kr * 0.62f, line)
        fill.color = 0xFFAEB4BD.toInt()
        c.drawCircle(x, y, kr * 0.32f, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = minOf(width, height) / 2f - context.dpf(6f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true) // keep the ScrollView from stealing drags
                active = true
                val dx = e.x - width / 2f
                val dy = e.y - height / 2f
                val d = minOf(hypot(dx, dy), r)
                val a = atan2(dx, -dy)
                kx = sin(a) * d
                ky = -cos(a) * d
                heading = Math.toDegrees(a.toDouble()).roundToInt()
                onMove(if (d < r * 0.12f) 0f else d / r, heading) // dead zone: resting thumb = stopped
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = false; kx = 0f; ky = 0f; onMove(0f, heading) }
        }
        invalidate()
        return true
    }
}
