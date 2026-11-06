package com.lfpmonitor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Рисует круговую шкалу заряда в стиле фирменного приложения. */
object GaugeRenderer {
    val GREEN = Color.parseColor("#2BE52B")
    val CYAN = Color.parseColor("#1BC9C0")
    private val TRACK = Color.parseColor("#333333")

    fun socColor(soc: Int): Int = when {
        soc <= 15 -> Color.parseColor("#FF3B30")
        soc <= 30 -> Color.parseColor("#FFB020")
        else -> GREEN
    }

    fun draw(c: Canvas, w: Float, h: Float, d: BmsData?) {
        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val stroke = size * 0.07f
        val r = size / 2f - stroke
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val soc = d?.soc ?: 0
        val color = socColor(soc)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = stroke
        p.color = TRACK
        c.drawArc(rect, 135f, 270f, false, p)

        if (d != null && soc > 0) {
            if (color == GREEN) {
                // Зелёный, переходящий в бирюзовый у конца дуги, как в фирменном приложении.
                val g = SweepGradient(cx, cy, intArrayOf(GREEN, GREEN, CYAN), floatArrayOf(0f, 0.3f, 0.76f))
                val m = android.graphics.Matrix()
                m.setRotate(130f, cx, cy)
                g.setLocalMatrix(m)
                p.shader = g
            } else {
                p.color = color
            }
            c.drawArc(rect, 135f, 270f * soc / 100f, false, p)
            p.shader = null
        }

        // Тонкое внутреннее кольцо.
        val inner = stroke * 1.1f
        val rect2 = RectF(rect.left + inner, rect.top + inner, rect.right - inner, rect.bottom - inner)
        p.strokeWidth = size * 0.006f
        p.color = color
        c.drawArc(rect2, 135f, 270f, false, p)

        // Процент.
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.textAlign = Paint.Align.CENTER
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.color = color
        t.textSize = size * 0.22f
        val socText = if (d == null) "—" else "$soc%"
        drawCentered(c, socText, cx, cy - size * 0.07f, t)

        // Напряжение и ток в «капсулах».
        val vText = if (d == null) "—.— V" else Fmt.f2(d.voltage) + "V"
        val aText = if (d == null) "—.— A" else Fmt.signedA(d.current) + "A"
        pill(c, vText, cx, cy + size * 0.10f, size, color)
        pill(c, aText, cx, cy + size * 0.23f, size, color)
    }

    private fun pill(c: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.textAlign = Paint.Align.CENTER
        t.color = Color.WHITE
        t.textSize = size * 0.085f
        val tw = t.measureText(text)
        val ph = size * 0.11f
        val pw = tw + size * 0.12f
        val box = RectF(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f)
        val b = Paint(Paint.ANTI_ALIAS_FLAG)
        b.style = Paint.Style.STROKE
        b.strokeWidth = size * 0.008f
        b.color = color
        c.drawRoundRect(box, ph / 2f, ph / 2f, b)
        drawCentered(c, text, cx, cy, t)
    }

    private fun drawCentered(c: Canvas, text: String, x: Float, y: Float, p: Paint) {
        val baseline = y - (p.descent() + p.ascent()) / 2f
        c.drawText(text, x, baseline, p)
    }

    fun bitmap(sizePx: Int, d: BmsData?): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), sizePx.toFloat(), sizePx.toFloat(), d)
        return bmp
    }
}

/** Шкала на главном экране. */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var data: BmsData? = null
        set(value) { field = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = (w * 0.85f).toInt()
        setMeasuredDimension(w, s)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        GaugeRenderer.draw(canvas, width.toFloat(), height.toFloat(), data)
    }
}
