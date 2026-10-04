package com.sadrazam.panoptik

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/** ESP ayarlari (menuden degisir, kalici) + ortak cizim yardimcilari. */
object Esp {

    // 0 = rengarenk (gokkusagi), digerleri sabit renk
    val palette = intArrayOf(
        0xFF3DFFA0.toInt(), // yesil
        0xFFFF3B3B.toInt(), // kirmizi
        0xFF3DA5FF.toInt(), // mavi
        0xFFFFE23D.toInt(), // sari
        0xFFFFB020.toInt(), // turuncu
        0xFFC03DFF.toInt(), // mor
        0xFFFFFFFF.toInt(), // beyaz
        0xFF00E5FF.toInt(), // camgobegi
        0                   // gokkusagi
    )
    val thickDp = floatArrayOf(1f, 2f, 3.5f, 5f)

    var lineMode = 1      // 0 kapali, 1 ustten, 2 alttan, 3 ust+alt
    var thick = 1         // thickDp indeksi
    var boxStyle = 1      // 0 tam, 1 koseli, 2 dolgulu, 3 3D
    var skeleton = true
    var sens = 1          // 0 dusuk, 1 orta, 2 yuksek
    var boxColor = 0
    var skelColor = 7
    var lineColor = 3

    private const val PREF = "panoptik_esp"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        lineMode = p.getInt("line", lineMode).coerceIn(0, 3)
        thick = p.getInt("thick", thick).coerceIn(0, thickDp.size - 1)
        boxStyle = p.getInt("box", boxStyle).coerceIn(0, 3)
        skeleton = p.getBoolean("skel", skeleton)
        sens = p.getInt("sens", sens).coerceIn(0, 2)
        boxColor = p.getInt("cbox", boxColor).coerceIn(0, palette.size - 1)
        skelColor = p.getInt("cskel", skelColor).coerceIn(0, palette.size - 1)
        lineColor = p.getInt("cline", lineColor).coerceIn(0, palette.size - 1)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("line", lineMode)
            .putInt("thick", thick)
            .putInt("box", boxStyle)
            .putBoolean("skel", skeleton)
            .putInt("sens", sens)
            .putInt("cbox", boxColor)
            .putInt("cskel", skelColor)
            .putInt("cline", lineColor)
            .apply()
    }

    /** Hareket esigi: dusuk hassasiyet = yuksek esik. */
    fun threshold(): Int = when (sens) {
        0 -> 30
        2 -> 12
        else -> 20
    }

    fun color(idx: Int): Int {
        val c = palette[idx.coerceIn(0, palette.size - 1)]
        if (c != 0) return c
        val hue = (System.currentTimeMillis() % 3000L) / 3000f * 360f
        return Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }

    fun strokePx(density: Float): Float = thickDp[thick] * density

    // ── Cizim ──

    fun drawBox(c: Canvas, r: RectF, col: Int, stroke: Paint, fill: Paint, sw: Float) {
        stroke.color = col
        stroke.strokeWidth = sw
        when (boxStyle) {
            1 -> { // KOSELI
                val l = min(r.width(), r.height()) * 0.28f
                c.drawLine(r.left, r.top, r.left + l, r.top, stroke)
                c.drawLine(r.left, r.top, r.left, r.top + l, stroke)
                c.drawLine(r.right, r.top, r.right - l, r.top, stroke)
                c.drawLine(r.right, r.top, r.right, r.top + l, stroke)
                c.drawLine(r.left, r.bottom, r.left + l, r.bottom, stroke)
                c.drawLine(r.left, r.bottom, r.left, r.bottom - l, stroke)
                c.drawLine(r.right, r.bottom, r.right - l, r.bottom, stroke)
                c.drawLine(r.right, r.bottom, r.right, r.bottom - l, stroke)
            }
            2 -> { // DOLGULU
                fill.color = (col and 0x00FFFFFF) or 0x40000000
                c.drawRect(r, fill)
                c.drawRect(r, stroke)
            }
            3 -> { // 3D
                val d = min(r.width(), r.height()) * 0.2f
                val b = RectF(r.left + d, r.top - d, r.right + d, r.bottom - d)
                c.drawRect(r, stroke)
                c.drawRect(b, stroke)
                c.drawLine(r.left, r.top, b.left, b.top, stroke)
                c.drawLine(r.right, r.top, b.right, b.top, stroke)
                c.drawLine(r.left, r.bottom, b.left, b.bottom, stroke)
                c.drawLine(r.right, r.bottom, b.right, b.bottom, stroke)
            }
            else -> c.drawRect(r, stroke) // TAM
        }
    }

    /** Ekranin ustunden ve/veya altindan hedefe cizgi. */
    fun drawLines(c: Canvas, w: Float, h: Float, r: RectF, col: Int, stroke: Paint, sw: Float) {
        if (lineMode == 0) return
        stroke.color = col
        stroke.strokeWidth = sw
        if (lineMode == 1 || lineMode == 3) {
            c.drawLine(w / 2f, 0f, r.centerX(), r.top, stroke)
            c.drawCircle(r.centerX(), r.top, sw * 1.6f, stroke)
        }
        if (lineMode == 2 || lineMode == 3) {
            c.drawLine(w / 2f, h, r.centerX(), r.bottom, stroke)
            c.drawCircle(r.centerX(), r.bottom, sw * 1.6f, stroke)
        }
    }

    // ── Iskelet tanimlari ──

    /** MediaPipe pose 33 nokta: gövde, kollar, eller, bacaklar, ayaklar (yuz haric). */
    val HUMAN_EDGES = intArrayOf(
        11, 12, 11, 23, 12, 24, 23, 24,
        11, 13, 13, 15, 12, 14, 14, 16,
        15, 17, 15, 19, 15, 21, 16, 18, 16, 20, 16, 22,
        23, 25, 25, 27, 24, 26, 26, 28,
        27, 29, 29, 31, 27, 31, 28, 30, 30, 32, 28, 32
    )

    /** Kedi/kopek sablonu (saga bakan), kutuya gore 0..1: x,y cifti. 16 nokta. */
    val ANIMAL_TPL = floatArrayOf(
        1.00f, 0.28f, // 0 burun
        0.90f, 0.22f, // 1 bas
        0.78f, 0.36f, // 2 boyun
        0.68f, 0.40f, // 3 omuz
        0.45f, 0.38f, // 4 sirt
        0.25f, 0.42f, // 5 kalca
        0.18f, 0.38f, // 6 kuyruk dibi
        0.02f, 0.15f, // 7 kuyruk ucu
        0.68f, 0.68f, // 8 on sol dirsek
        0.70f, 1.00f, // 9 on sol pati
        0.60f, 0.68f, // 10 on sag dirsek
        0.58f, 1.00f, // 11 on sag pati
        0.27f, 0.68f, // 12 arka sol diz
        0.25f, 1.00f, // 13 arka sol pati
        0.35f, 0.68f, // 14 arka sag diz
        0.37f, 1.00f  // 15 arka sag pati
    )
    val ANIMAL_EDGES = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7,
        3, 8, 8, 9, 3, 10, 10, 11,
        5, 12, 12, 13, 5, 14, 14, 15
    )
}
