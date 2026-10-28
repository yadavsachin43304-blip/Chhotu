package com.sachin.chhotu

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Chhotu ka chehra: ek pyaara robot jiski aankhein jhapakti hain
 * aur munh awaaz ki loudness ke hisaab se hilta hai (lip-sync).
 */
class FaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mood { IDLE, LISTENING, THINKING, SPEAKING }

    var mood: Mood = Mood.IDLE
        set(v) { field = v; invalidate() }

    /** 0..1: bolte waqt awaaz ki loudness, sunte waqt mic ki loudness */
    var level: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    var compact = false // floating bubble ke liye

    private var shown = 0f
    private var prevShown = 0f
    private var blink = 0f
    private var nextBlink = System.currentTimeMillis() + 2500
    private var blinkStart = 0L
    private val t0 = System.currentTimeMillis()
    private var lookX = 0f
    private var lookTarget = 0f
    private var nextLook = System.currentTimeMillis() + 3000

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // blur glow ke liye

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val t = (now - t0) / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2f; val cy = h / 2f

        // smooth level
        prevShown = shown
        shown += (level - shown) * 0.45f

        // blink
        if (now > nextBlink && blinkStart == 0L) blinkStart = now
        if (blinkStart != 0L) {
            val p = (now - blinkStart) / 160f
            blink = if (p < 1f) p else if (p < 2f) 2f - p else 0f
            if (p >= 2f) { blinkStart = 0L; nextBlink = now + 2200 + Random.nextLong(3000) }
        }
        // look around when idle
        if (now > nextLook) { lookTarget = if (mood == Mood.IDLE) (Random.nextFloat() - 0.5f) * 0.5f else 0f; nextLook = now + 2000 + Random.nextLong(3000) }
        lookX += (lookTarget - lookX) * 0.08f

        val bob = sin(t * 1.6f) * s * 0.012f
        val headR = s * 0.42f

        // halo ring (listening = pulse with mic)
        if (mood == Mood.LISTENING || mood == Mood.SPEAKING) {
            val pulse = if (mood == Mood.LISTENING) 0.35f + shown * 0.65f else 0.25f + shown * 0.4f
            glow.style = Paint.Style.STROKE
            glow.strokeWidth = s * 0.02f
            glow.color = if (mood == Mood.LISTENING) Color.argb((90 + 120 * pulse).toInt(), 46, 196, 182) else Color.argb((70 + 100 * pulse).toInt(), 255, 109, 40)
            glow.maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy + bob, headR * (1.04f + pulse * 0.08f), glow)
            glow.maskFilter = null
        }

        // head
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx - headR * 0.3f, cy - headR * 0.4f + bob, headR * 1.5f,
            intArrayOf(Color.parseColor("#FF9A5C"), Color.parseColor("#FF6D28"), Color.parseColor("#C94A10")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        rect.set(cx - headR, cy - headR * 0.92f + bob, cx + headR, cy + headR * 0.92f + bob)
        canvas.drawRoundRect(rect, headR * 0.7f, headR * 0.7f, paint)
        paint.shader = null

        // antenna
        if (!compact) {
            paint.color = Color.parseColor("#FF8A47")
            paint.strokeWidth = s * 0.018f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(cx, rect.top, cx, rect.top - s * 0.06f, paint)
            paint.style = Paint.Style.FILL
            val ant = if (mood == Mood.THINKING) (0.5f + 0.5f * sin(t * 8f)) else 0.6f
            paint.color = Color.argb(255, 46, (150 + 70 * ant).toInt(), 182)
            canvas.drawCircle(cx, rect.top - s * 0.075f, s * 0.025f, paint)
        }

        // visor (screen)
        val vx = headR * 0.78f; val vy = headR * 0.55f
        rect.set(cx - vx, cy - vy * 0.95f + bob, cx + vx, cy + vy * 1.05f + bob)
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.parseColor("#1A1F38"), Color.parseColor("#0B0E1C"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, vy * 0.6f, vy * 0.6f, paint)
        paint.shader = null

        val eyeColor = when (mood) {
            Mood.LISTENING -> Color.parseColor("#4FF5E3")
            Mood.THINKING -> Color.parseColor("#9FB4FF")
            else -> Color.parseColor("#7DF9FF")
        }

        // eyes
        val eyeW = s * 0.085f
        var eyeH = s * 0.11f
        if (mood == Mood.LISTENING) eyeH *= 1.12f
        eyeH *= (1f - blink * 0.9f)
        val eyeDx = s * 0.13f
        val lookY = if (mood == Mood.THINKING) -s * 0.03f else 0f
        val lookXp = if (mood == Mood.THINKING) s * 0.03f * sin(t * 2f) else lookX * s * 0.08f
        val eyeCy = cy - s * 0.04f + bob + lookY
        glow.style = Paint.Style.FILL
        glow.color = eyeColor
        glow.maskFilter = BlurMaskFilter(s * 0.025f, BlurMaskFilter.Blur.SOLID)
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * eyeDx + lookXp
            rect.set(ex - eyeW / 2, eyeCy - eyeH / 2, ex + eyeW / 2, eyeCy + eyeH / 2)
            canvas.drawRoundRect(rect, eyeW / 2, eyeW / 2, glow)
        }
        glow.maskFilter = null

        // cheeks
        paint.color = Color.argb(70, 255, 120, 150)
        canvas.drawCircle(cx - s * 0.25f, cy + s * 0.1f + bob, s * 0.04f, paint)
        canvas.drawCircle(cx + s * 0.25f, cy + s * 0.1f + bob, s * 0.04f, paint)

        // mouth
        val mouthCy = cy + s * 0.11f + bob
        glow.color = eyeColor
        glow.maskFilter = BlurMaskFilter(s * 0.02f, BlurMaskFilter.Blur.SOLID)
        if (mood == Mood.SPEAKING && shown > 0.04f) {
            val open = shown
            val delta = (shown - prevShown)
            // 'aa' = chauda aur khula, 'oo' = gol; badalte level se shape badalta hai
            val round = (0.5f + delta * 6f).coerceIn(0f, 1f)
            val mw = s * (0.16f - 0.05f * round + 0.03f * open)
            val mh = s * (0.018f + 0.10f * open)
            rect.set(cx - mw / 2, mouthCy - mh / 2, cx + mw / 2, mouthCy + mh / 2)
            canvas.drawRoundRect(rect, mh / 2 + s * 0.01f, mh / 2 + s * 0.01f, glow)
            // andar ka hissa
            if (mh > s * 0.04f) {
                paint.color = Color.parseColor("#0B0E1C")
                rect.inset(s * 0.012f, s * 0.012f)
                canvas.drawRoundRect(rect, mh / 2, mh / 2, paint)
                paint.color = Color.argb(160, 255, 120, 140)
                val tongue = RectF(rect.left + rect.width() * 0.25f, rect.centerY(), rect.right - rect.width() * 0.25f, rect.bottom)
                canvas.drawOval(tongue, paint)
            }
        } else if (mood == Mood.THINKING) {
            for (i in 0..2) {
                val a = 0.4f + 0.6f * ((sin(t * 6f - i * 0.9f) + 1f) / 2f)
                glow.alpha = (255 * a).toInt()
                canvas.drawCircle(cx + (i - 1) * s * 0.045f, mouthCy, s * 0.014f, glow)
            }
            glow.alpha = 255
        } else {
            // muskaan
            glow.style = Paint.Style.STROKE
            glow.strokeWidth = s * 0.02f
            glow.strokeCap = Paint.Cap.ROUND
            val mw = s * if (mood == Mood.LISTENING) 0.09f else 0.12f
            path.reset()
            path.moveTo(cx - mw / 2, mouthCy - s * 0.01f)
            path.quadTo(cx, mouthCy + s * 0.045f, cx + mw / 2, mouthCy - s * 0.01f)
            canvas.drawPath(path, glow)
            glow.style = Paint.Style.FILL
        }
        glow.maskFilter = null

        if (mood == Mood.IDLE && shown < 0.02f) postInvalidateDelayed(60) else postInvalidateOnAnimation()
    }
}
