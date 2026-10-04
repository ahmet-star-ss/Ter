package com.sadrazam.panoptik

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────── Veri ─────────────────────────

/** Normalize (0..1) ekran koordinatlarinda hedef. */
data class Target(
    val id: Int,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val conf: Int,
    val kind: Int = 0,
    /** Iskelet noktalari: x,y,gorunurluk uclusu (0..1). Yoksa null. */
    val pts: FloatArray? = null
)

// ───────────────────────── Hareket analizi ─────────────────────────

/**
 * Sallanmaya dayanikli hareket algilama:
 *  1) Kareler arasi global kayma (telefon sallanmasi) tahmin edilip telafi edilir
 *  2) Parlaklik degisimi (otomatik pozlama) cikarilir
 *  3) Jiroskop siddetliyse kare atlanir
 *  4) Ince kenar artefaktlari elenir, hedef 2 karede gorulmeden gosterilmez
 * Geriye sadece GERCEKTEN hareket eden cisimler kalir.
 */
class MotionAnalyzer(
    private val perception: Perception?,
    private val onResult: (List<Target>, Float, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val gw = 64
    private val gh = 48
    private val maxShift = 3
    private var prev: IntArray? = null
    private var lastNs = 0L
    private var fps = 0f
    private var blips = 0

    private class Cand(var t: Target, val id: Int, var hits: Int, var miss: Int)

    private val cands = ArrayList<Cand>()
    private var nextId = 1

    @Volatile
    var threshold = 20

    /** Jiroskop acisal hizi (rad/s), aktivite besler. */
    @Volatile
    var gyro = 0f

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
            perception?.offer(image)
        } finally {
            image.close()
        }
    }

    private fun mapPt(nx: Float, ny: Float, rot: Int): Pair<Float, Float> = when (rot) {
        90 -> Pair(1f - ny, nx)
        180 -> Pair(1f - nx, 1f - ny)
        270 -> Pair(ny, 1f - nx)
        else -> Pair(nx, ny)
    }

    private fun process(image: ImageProxy) {
        val now = System.nanoTime()
        if (lastNs != 0L) {
            val f = 1e9f / (now - lastNs)
            fps = if (fps == 0f) f else fps * 0.85f + f * 0.15f
        }
        lastNs = now

        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val off = if (ps >= 3) 1 else 0 // RGBA -> yesil kanal
        val w = image.width
        val h = image.height
        val rot = image.imageInfo.rotationDegrees

        // Her hucre: 3x3 ornek ortalamasi (alt-hucre titremesini yumusatir)
        val cur = IntArray(gw * gh)
        val cwid = max(1, w / gw)
        val chei = max(1, h / gh)
        for (gy in 0 until gh) {
            val y0 = gy * h / gh
            for (gx in 0 until gw) {
                val x0 = gx * w / gw
                var s = 0
                for (oy in 0..2) {
                    val yy = min(h - 1, y0 + chei * (2 * oy + 1) / 6)
                    for (ox in 0..2) {
                        val xx = min(w - 1, x0 + cwid * (2 * ox + 1) / 6)
                        s += buf.get(yy * rs + xx * ps + off).toInt() and 0xFF
                    }
                }
                cur[gy * gw + gx] = s / 9
            }
        }

        val p = prev
        prev = cur
        if (p == null) {
            onResult(emptyList(), fps, blips)
            return
        }

        // 1) Global kayma tahmini (kirpilmis mutlak fark = hareketli cisimlere dayanikli)
        val r = maxShift
        var bestScore = Long.MAX_VALUE
        var bsx = 0
        var bsy = 0
        for (sy in -r..r) {
            for (sx in -r..r) {
                var sad = 0L
                for (y in r until gh - r) {
                    val py = y - sy
                    for (x in r until gw - r) {
                        val d = abs(cur[y * gw + x] - p[py * gw + (x - sx)])
                        sad += if (d > 40) 40 else d
                    }
                }
                val score = sad + (abs(sx) + abs(sy)) * 6L
                if (score < bestScore) {
                    bestScore = score
                    bsx = sx
                    bsy = sy
                }
            }
        }

        // 2) Parlaklik degisimi (ortalama isaretli fark)
        var sum = 0L
        var cntInner = 0
        for (y in r until gh - r) {
            for (x in r until gw - r) {
                sum += cur[y * gw + x] - p[(y - bsy) * gw + (x - bsx)]
                cntInner++
            }
        }
        val mean = if (cntInner > 0) sum.toFloat() / cntInner else 0f

        val eff = threshold + (gyro * 18f).toInt()
        val active = BooleanArray(gw * gh)
        var cnt = 0
        for (y in 0 until gh) {
            val py = y - bsy
            if (py < 0 || py >= gh) continue
            for (x in 0 until gw) {
                val px = x - bsx
                if (px < 0 || px >= gw) continue
                val d = abs(cur[y * gw + x] - p[py * gw + px] - mean)
                if (d > eff) {
                    active[y * gw + x] = true
                    cnt++
                }
            }
        }

        // size, minx, maxx, miny, maxy, cx, cy
        val found = ArrayList<FloatArray>()
        // 3) Sert sallanma / kare capinda degisim -> yoksay
        val shaking = gyro > 0.9f || cnt > (gw * gh * 0.25f).toInt()
        if (!shaking && cnt > 0) {
            val seen = BooleanArray(gw * gh)
            val stack = IntArray(gw * gh)
            for (s in active.indices) {
                if (!active[s] || seen[s]) continue
                var sp = 0
                stack[sp++] = s
                seen[s] = true
                var size = 0
                var minx = gw
                var maxx = -1
                var miny = gh
                var maxy = -1
                var sx = 0f
                var sy = 0f
                while (sp > 0) {
                    val i = stack[--sp]
                    val x = i % gw
                    val y = i / gw
                    size++
                    minx = min(minx, x); maxx = max(maxx, x)
                    miny = min(miny, y); maxy = max(maxy, y)
                    sx += x; sy += y
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                        val j = ny * gw + nx
                        if (active[j] && !seen[j]) {
                            seen[j] = true
                            stack[sp++] = j
                        }
                    }
                }
                // 4) Ince/dagnik kenar artefaktlarini ele: yeterince dolu bir leke olmali
                val area = (maxx - minx + 1) * (maxy - miny + 1)
                if (size >= 4 && size * 100 >= area * 30) {
                    found.add(
                        floatArrayOf(
                            size.toFloat(), minx.toFloat(), maxx.toFloat(),
                            miny.toFloat(), maxy.toFloat(), sx / size, sy / size
                        )
                    )
                }
            }
        }
        found.sortByDescending { it[0] }

        val raw = ArrayList<Target>()
        for (f in found.take(3)) {
            val c = mapPt((f[5] + 0.5f) / gw, (f[6] + 0.5f) / gh, rot)
            val a = mapPt(f[1] / gw, f[3] / gh, rot)
            val b = mapPt((f[2] + 1f) / gw, (f[4] + 1f) / gh, rot)
            raw.add(
                Target(
                    0, c.first, c.second,
                    abs(a.first - b.first), abs(a.second - b.second),
                    min(99, 40 + (f[0] * 2).toInt())
                )
            )
        }

        // Kimlik eslestirme + kalicilik (2 karede gorulmeyen hedef gosterilmez) + yumusatma
        val used = HashSet<Cand>()
        for (t in raw) {
            val m = cands.filter { it !in used }
                .minByOrNull { hypot(it.t.cx - t.cx, it.t.cy - t.cy) }
            if (m != null && hypot(m.t.cx - t.cx, m.t.cy - t.cy) < 0.25f) {
                val o = m.t
                m.t = t.copy(
                    id = m.id,
                    cx = o.cx * 0.5f + t.cx * 0.5f,
                    cy = o.cy * 0.5f + t.cy * 0.5f,
                    w = o.w * 0.5f + t.w * 0.5f,
                    h = o.h * 0.5f + t.h * 0.5f
                )
                m.hits++
                m.miss = 0
                used.add(m)
            } else {
                val c = Cand(t.copy(id = nextId), nextId, 1, 0)
                nextId++
                cands.add(c)
                used.add(c)
            }
        }
        for (c in cands) if (c !in used) c.miss++
        cands.removeAll { it.miss > 2 }

        val out = cands.filter { it.hits >= 2 && it.miss <= 1 }.map { it.t }
        blips += out.size
        onResult(out, fps, blips)
    }
}

// ───────────────────────── HUD katmani ─────────────────────────

class HudView(ctx: Context) : View(ctx) {

    var targets: List<Target> = emptyList()
    var fps = 0f
    var blips = 0
    var telemetry = ""
    var zoomLabel = "1.0x"
    var filterName = "NORMAL"
    var lockedId = -1
    var autoMag = true
    var recording = false
    var mags: Map<Int, Bitmap> = emptyMap()

    private val green = 0xFF3DFFA0.toInt()
    private val orange = 0xFFFFB020.toInt()
    private val panelBg = 0xAA10201A.toInt()
    private var sweep = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = dp(10f)
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            val nx = e.x / width
            val ny = e.y / height
            val t = targets.minByOrNull { hypot(it.cx - nx, it.cy - ny) }
            if (t != null && hypot(t.cx - nx, t.cy - ny) < 0.2f) {
                lockedId = if (lockedId == t.id) -1 else t.id
            } else {
                lockedId = -1
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    // Gorunen degerler hedefe dogru yumusakca kayar -> sekiller titremez
    private val disp = HashMap<Int, FloatArray>()

    private fun state(t: Target): FloatArray {
        val pts = t.pts
        val n = 4 + (pts?.size ?: 0)
        val old = disp[t.id]
        if (old == null || old.size != n) {
            val s = FloatArray(n)
            s[0] = t.cx; s[1] = t.cy; s[2] = t.w; s[3] = t.h
            if (pts != null) System.arraycopy(pts, 0, s, 4, pts.size)
            disp[t.id] = s
            return s
        }
        val k = 0.35f
        old[0] += (t.cx - old[0]) * k
        old[1] += (t.cy - old[1]) * k
        old[2] += (t.w - old[2]) * k
        old[3] += (t.h - old[3]) * k
        if (pts != null) {
            var i = 0
            while (i < pts.size) {
                old[4 + i] += (pts[i] - old[4 + i]) * k
                old[5 + i] += (pts[i + 1] - old[5 + i]) * k
                old[6 + i] = pts[i + 2]
                i += 3
            }
        }
        return old
    }

    private fun drawSkeleton(c: Canvas, s: FloatArray, kind: Int, w: Float, h: Float, sw: Float) {
        val col = Esp.color(Esp.skelColor)
        stroke.color = col
        stroke.strokeWidth = sw
        fill.color = col
        val human = kind == Kind.PERSON
        val edges = if (human) Esp.HUMAN_EDGES else Esp.ANIMAL_EDGES
        val n = (s.size - 4) / 3
        fun vis(i: Int) = i < n && s[6 + 3 * i] >= 0.4f
        fun px(i: Int) = s[4 + 3 * i] * w
        fun py(i: Int) = s[5 + 3 * i] * h
        var i = 0
        while (i < edges.size) {
            val a = edges[i]
            val b = edges[i + 1]
            i += 2
            if (vis(a) && vis(b)) c.drawLine(px(a), py(a), px(b), py(b), stroke)
        }
        for (k in (if (human) 11 else 0) until n) {
            if (vis(k)) c.drawCircle(px(k), py(k), dp(2.5f), fill)
        }
        if (human && vis(0)) {
            // Bas: kulaklardan yaricap, boyun: bastan omuz ortasina
            var hx = px(0)
            var hy = py(0)
            var rad = dp(12f)
            if (vis(7) && vis(8)) {
                hx = (px(7) + px(8)) / 2f
                hy = (py(7) + py(8)) / 2f
                rad = max(dp(10f), hypot(px(7) - px(8), py(7) - py(8)) * 0.75f)
            } else if (vis(11) && vis(12)) {
                rad = max(dp(10f), hypot(px(11) - px(12), py(11) - py(12)) * 0.3f)
            }
            c.drawCircle(hx, hy, rad, stroke)
            if (vis(11) && vis(12)) {
                c.drawLine(hx, hy + rad, (px(11) + px(12)) / 2f, (py(11) + py(12)) / 2f, stroke)
            }
        }
        stroke.strokeWidth = dp(1.5f)
    }

    private fun panel(c: Canvas, r: RectF, color: Int = green) {
        fill.color = panelBg
        c.drawRect(r, fill)
        stroke.color = color
        c.drawRect(r, stroke)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Izgara
        stroke.color = 0x223DFFA0
        stroke.strokeWidth = dp(0.8f)
        for (i in 1..9) {
            c.drawLine(w * i / 10f, 0f, w * i / 10f, h, stroke)
            c.drawLine(0f, h * i / 10f, w, h * i / 10f, stroke)
        }
        stroke.strokeWidth = dp(1.5f)

        // Cerceve koseleri
        stroke.color = green
        val b = dp(24f)
        val m = dp(6f)
        c.drawLine(m, m, m + b, m, stroke); c.drawLine(m, m, m, m + b, stroke)
        c.drawLine(w - m, m, w - m - b, m, stroke); c.drawLine(w - m, m, w - m, m + b, stroke)
        c.drawLine(m, h - m, m + b, h - m, stroke); c.drawLine(m, h - m, m, h - m - b, stroke)
        c.drawLine(w - m, h - m, w - m - b, h - m, stroke); c.drawLine(w - m, h - m, w - m, h - m - b, stroke)

        // Merkez nisangah
        val cx = w / 2f
        val cy = h / 2f
        val k = dp(18f)
        c.drawLine(cx - k, cy, cx + k, cy, stroke)
        c.drawLine(cx, cy - k, cx, cy + k, stroke)
        c.drawCircle(cx, cy, k * 0.5f, stroke)

        // Hedefler (ESP: box + cizgi + iskelet)
        val lineW = Esp.strokePx(resources.displayMetrics.density)
        val seen = HashSet<Int>()
        for (t in targets) {
            seen.add(t.id)
            val st = state(t)
            val locked = t.id == lockedId
            val boxCol = if (locked) orange else Esp.color(Esp.boxColor)
            val bw = max(st[2] * w, dp(36f))
            val bh = max(st[3] * h, dp(36f))
            val r = RectF(st[0] * w - bw / 2, st[1] * h - bh / 2, st[0] * w + bw / 2, st[1] * h + bh / 2)
            Esp.drawLines(c, w, h, r, Esp.color(Esp.lineColor), stroke, lineW)
            if (Esp.skeleton && t.pts != null) drawSkeleton(c, st, t.kind, w, h, lineW)
            Esp.drawBox(c, r, boxCol, stroke, fill, lineW)
            text.color = boxCol
            val nm = if (t.kind == Kind.MOTION) "TRACK-0${t.id % 10}" else "${Kind.name(t.kind)}-${(t.id % 100).toString().padStart(2, '0')}"
            val label = "$nm${if (locked) " // LOCK" else ""} ${t.conf}%"
            c.drawText(label, r.left, r.top - dp(4f), text)
        }
        disp.keys.retainAll(seen)
        stroke.strokeWidth = dp(1.5f)

        // Buyutec panelleri
        if (autoMag) {
            for ((slot, t) in targets.take(2).withIndex()) {
                val bmp = mags[t.id] ?: continue
                val s = dp(112f)
                val top = h * 0.30f
                val r = if (slot == 0) RectF(dp(8f), top, dp(8f) + s, top + s)
                else RectF(w - dp(8f) - s, top, w - dp(8f), top + s)
                c.drawBitmap(bmp, null, r, bmpPaint)
                stroke.color = if (t.id == lockedId) orange else green
                c.drawRect(r, stroke)
                text.color = green
                c.drawText("AUTO MAG-TRACK // ${if (slot == 0) "A" else "B"}", r.left + dp(2f), r.top - dp(3f), text)
                c.drawLine(r.centerX() - dp(8f), r.centerY(), r.centerX() + dp(8f), r.centerY(), stroke)
                c.drawLine(r.centerX(), r.centerY() - dp(8f), r.centerX(), r.centerY() + dp(8f), stroke)
            }
        }

        // Hedef dosyasi
        run {
            val t = targets.firstOrNull { it.id == lockedId } ?: targets.firstOrNull()
            val r = RectF(dp(8f), h * 0.62f, dp(8f) + dp(190f), h * 0.62f + dp(78f))
            panel(c, r)
            text.color = green
            val x = r.left + dp(6f)
            var y = r.top + dp(14f)
            val lh = dp(12f)
            if (t == null) {
                c.drawText("TARGET DOSSIER // IDLE", x, y, text); y += lh
                c.drawText("STATUS: SCANNING...", x, y, text)
            } else {
                c.drawText("TARGET DOSSIER // ${if (t.id == lockedId) "LOCKED" else "STABLE"}", x, y, text); y += lh
                c.drawText("SIGNATURE: ${if (t.kind == Kind.MOTION) "MOTION-BLOB" else Kind.name(t.kind)}", x, y, text); y += lh
                c.drawText("TRACK-${(t.id % 100).toString().padStart(2, '0')}  CONF: ${t.conf}%", x, y, text); y += lh
                c.drawText(
                    String.format(Locale.US, "POS: X%02d Y%02d  SZ: %02d%%", (t.cx * 100).toInt(), (t.cy * 100).toInt(), (max(t.w, t.h) * 100).toInt()),
                    x, y, text
                ); y += lh
                c.drawText("TAP TARGET TO LOCK", x, y, text)
            }
        }

        // Radar
        run {
            val rc = dp(40f)
            val rx = w - dp(52f)
            val ry = h * 0.52f
            stroke.color = green
            c.drawCircle(rx, ry, rc, stroke)
            c.drawCircle(rx, ry, rc * 0.5f, stroke)
            c.drawLine(rx - rc, ry, rx + rc, ry, stroke)
            c.drawLine(rx, ry - rc, rx, ry + rc, stroke)
            sweep = (sweep + 3f) % 360f
            val a = Math.toRadians(sweep.toDouble())
            c.drawLine(rx, ry, rx + (rc * cos(a)).toFloat(), ry + (rc * sin(a)).toFloat(), stroke)
            fill.color = orange
            for (t in targets) {
                c.drawCircle(rx + (t.cx - 0.5f) * 2f * rc * 0.9f, ry + (t.cy - 0.5f) * 2f * rc * 0.9f, dp(3f), fill)
            }
            text.color = green
            c.drawText("RADAR", rx - rc, ry - rc - dp(4f), text)
            c.drawText("BLIPS $blips", rx - rc, ry + rc + dp(12f), text)
        }

        // Ust telemetri
        text.color = green
        var ty = dp(20f)
        c.drawText("PANOPTIK // SIGINT SUITE", dp(14f), ty, text); ty += dp(12f)
        c.drawText(
            String.format(Locale.US, "FPS %.1f | FLT %s | ZOOM %s", fps, filterName, zoomLabel),
            dp(14f), ty, text
        ); ty += dp(12f)
        c.drawText(telemetry, dp(14f), ty, text)
        if (recording) {
            fill.color = Color.RED
            c.drawCircle(w - dp(60f), dp(16f), dp(5f), fill)
            text.color = Color.RED
            c.drawText("REC", w - dp(50f), dp(20f), text)
        }

        postInvalidateOnAnimation()
    }
}

// ───────────────────────── Kamera izleme overlay ─────────────────────────

class CamMotionOverlay(ctx: Context) : View(ctx) {
    var boxes: List<RectF> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sw = Esp.strokePx(resources.displayMetrics.density)
        for (b in boxes) {
            val r = RectF(b.left * w, b.top * h, b.right * w, b.bottom * h)
            Esp.drawLines(c, w, h, r, Esp.color(Esp.lineColor), p, sw)
            Esp.drawBox(c, r, Esp.color(Esp.boxColor), p, f, sw)
        }
        if (boxes.isNotEmpty()) postInvalidateOnAnimation()
    }
}

// ───────────────────────── Ana ekran ─────────────────────────

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var hud: HudView
    private lateinit var btnMag: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnFlt: TextView
    private lateinit var btnZoom: TextView
    private lateinit var statusTv: TextView
    private lateinit var top: FrameLayout
    private lateinit var drawer: DrawerLayout

    // Kamera izleme (trafik kamerasi web gorunumu + hareket kutulari)
    private var camWeb: WebView? = null
    private lateinit var camOverlay: CamMotionOverlay
    private var camMotionOn = false
    private val camHandler = Handler(Looper.getMainLooper())
    private var prevCamGray: IntArray? = null
    private val camPoll = object : Runnable {
        override fun run() {
            if (camMotionOn) diffWebView()
            camHandler.postDelayed(this, 220L)
        }
    }

    private var camera: Camera? = null
    private var perception: Perception? = null
    private var analyzer: MotionAnalyzer? = null
    private var sensorMgr: SensorManager? = null
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            analyzer?.gyro = g
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val exec = Executors.newSingleThreadExecutor()

    private var autoMag = true
    private var recording = false
    private var csv: FileWriter? = null
    private var filterIdx = 0
    private var zoomIdx = 0
    private var lastMagMs = 0L
    private var lastLoc: Location? = null
    private var locMgr: LocationManager? = null

    private val filters = listOf("NORMAL", "MONO", "NIGHT", "NEGATIVE")
    private val zooms = listOf(1f, 2f, 3f, 5f)

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLoc = location
            updateTelemetry()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)) {
                startCamera()
            } else {
                toast("Kamera izni gerekli")
            }
            startLocation()
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun makeBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFF3DFFA0.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            setColor(0xCC1E2B26.toInt())
            setStroke(dp(1), 0xFF3DFFA0.toInt())
            cornerRadius = dp(8).toFloat()
        }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Esp.load(this)

        val sw = resources.displayMetrics.widthPixels
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val box = FrameLayout(this)
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        hud = HudView(this)
        box.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(box, FrameLayout.LayoutParams(sw, sw * 4 / 3).apply {
            gravity = Gravity.TOP
            topMargin = dp(36)
        })

        btnMag = makeBtn("AUTO MAG: ON") {
            autoMag = !autoMag
            hud.autoMag = autoMag
            btnMag.text = if (autoMag) "AUTO MAG: ON" else "AUTO MAG: OFF"
        }
        val btnSnap = makeBtn("SNAP") { snap() }
        btnRec = makeBtn("DATA REC") { toggleRec() }
        btnFlt = makeBtn("FILTER: NORMAL") {
            filterIdx = (filterIdx + 1) % filters.size
            applyFilter()
        }
        btnZoom = makeBtn("ZOOM: 1x") {
            zoomIdx = (zoomIdx + 1) % zooms.size
            applyZoom()
        }
        val btnMap = makeBtn("MAP") { openMap() }

        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach {
                addView(it, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
            addView(row(btnMag, btnSnap, btnRec))
            addView(row(btnFlt, btnZoom, btnMap))
        }
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        // Hakkinda dugmesi + sistem durumu (ust serit)
        val btnAbout = makeBtn("\u2630  HAKKINDA") { drawer.openDrawer(Gravity.START) }
        root.addView(btnAbout, FrameLayout.LayoutParams(dp(104), dp(30), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(3), 0, 0)
        })
        val btnSet = makeBtn("\u2699") { drawer.openDrawer(Gravity.END) }
        root.addView(btnSet, FrameLayout.LayoutParams(dp(36), dp(30), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(116), dp(3), 0, 0)
        })
        statusTv = TextView(this).apply {
            text = "SISTEM BASLATILIYOR..."
            setTextColor(0xFFFFB020.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        root.addView(statusTv, FrameLayout.LayoutParams(dp(150), dp(30), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(3), dp(10), 0)
        })

        // Cekmece
        drawer = DrawerLayout(this)
        drawer.setScrimColor(0x55000000)
        drawer.addView(root, DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        drawer.addView(buildAbout(), DrawerLayout.LayoutParams(
            dp(290), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
        ))
        drawer.addView(buildSettings(), DrawerLayout.LayoutParams(
            dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
        ))

        top = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        top.addView(drawer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Mod sekmeleri: PANOPTIK / KAMERA IZLEME
        val camScreen = buildCameraScreen()
        camScreen.visibility = View.GONE
        top.addView(camScreen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val tabPanoptik = makeBtn("PANOPTIK") {}
        val tabCam = makeBtn("\uD83D\uDCF7 KAMERA IZLEME") {}
        fun selectTab(cam: Boolean) {
            drawer.visibility = if (cam) View.GONE else View.VISIBLE
            camScreen.visibility = if (cam) View.VISIBLE else View.GONE
            tabPanoptik.alpha = if (cam) 0.45f else 1f
            tabCam.alpha = if (cam) 1f else 0.45f
            camMotionOn = camMotionOn && cam
        }
        tabPanoptik.setOnClickListener { selectTab(false) }
        tabCam.setOnClickListener { selectTab(true) }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0F0D.toInt())
            addView(tabPanoptik, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), dp(4), dp(2), dp(2)) })
            addView(tabCam, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(4), dp(4), dp(2)) })
        }
        top.addView(tabRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { topMargin = dp(36) })
        selectTab(false)

        // Acilis ekrani (her giriste)
        val splash = ImageView(this).apply {
            setImageResource(R.drawable.splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        top.addView(splash, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(top)
        updateTelemetry()

        splash.animate()
            .alpha(0f)
            .setStartDelay(1800L)
            .setDuration(1600L)
            .withEndAction {
                top.removeView(splash)
                activateSystem()
            }
            .start()
    }

    private fun activateSystem() {
        statusTv.text = "\u25CF SISTEM AKTIF"
        statusTv.setTextColor(0xFF3DFFA0.toInt())
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // ── Kamera izleme ──
    private fun buildCameraScreen(): FrameLayout {
        val f = FrameLayout(this)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            loadUrl("https://worldwatcher.live/")
        }
        // YouTube yayinlari icin tam ekran destegi
        var customView: View? = null
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (view == null) return
                customView = view
                f.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            override fun onHideCustomView() {
                customView?.let { f.removeView(it) }
                customView = null
            }
        }
        camWeb = web
        camOverlay = CamMotionOverlay(this)
        f.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(camOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val btnMotion = makeBtn("HAREKET ALGILA: KAPALI") {}
        btnMotion.setOnClickListener {
            camMotionOn = !camMotionOn
            btnMotion.text = if (camMotionOn) "HAREKET ALGILA: ACIK" else "HAREKET ALGILA: KAPALI"
            if (!camMotionOn) { camOverlay.boxes = emptyList(); camOverlay.invalidate(); prevCamGray = null }
        }
        val hint = TextView(this).apply {
            text = "Genel hareket kutulari - kisi/plaka tanima yapmaz"
            setTextColor(0xFF6A7A73.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            addView(btnMotion, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(2) })
        }
        f.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        camHandler.post(camPoll)
        return f
    }

    private fun diffWebView() {
        val web = camWeb ?: return
        if (web.width <= 0 || web.height <= 0) return
        try {
            val gw = 48; val gh = 36
            val bw = web.width; val bh = web.height
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            web.draw(c)
            val cur = IntArray(gw * gh)
            for (gy in 0 until gh) {
                val y = ((gy * 2 + 1) * bh / (gh * 2)).coerceIn(0, bh - 1)
                for (gx in 0 until gw) {
                    val x = ((gx * 2 + 1) * bw / (gw * 2)).coerceIn(0, bw - 1)
                    val px = bmp.getPixel(x, y)
                    val gray = (((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)) / 3
                    cur[gy * gw + gx] = gray
                }
            }
            bmp.recycle()
            val prev = prevCamGray
            prevCamGray = cur
            if (prev == null) return
            val active = BooleanArray(gw * gh)
            var cnt = 0
            for (i in cur.indices) {
                if (abs(cur[i] - prev[i]) > 18) { active[i] = true; cnt++ }
            }
            val boxes = ArrayList<RectF>()
            if (cnt in 1 until (gw * gh * 0.4f).toInt()) {
                val seen = BooleanArray(gw * gh)
                val stack = IntArray(gw * gh)
                for (s in active.indices) {
                    if (!active[s] || seen[s]) continue
                    var sp = 0; stack[sp++] = s; seen[s] = true
                    var size = 0; var minx = gw; var maxx = -1; var miny = gh; var maxy = -1
                    while (sp > 0) {
                        val i = stack[--sp]
                        val x = i % gw; val y = i / gw
                        size++
                        minx = min(minx, x); maxx = max(maxx, x)
                        miny = min(miny, y); maxy = max(maxy, y)
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx; val ny = y + dy
                            if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                            val j = ny * gw + nx
                            if (active[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                        }
                    }
                    if (size >= 3) {
                        boxes.add(RectF(minx.toFloat() / gw, miny.toFloat() / gh, (maxx + 1f) / gw, (maxy + 1f) / gh))
                    }
                }
            }
            camOverlay.boxes = boxes
            camOverlay.invalidate()
        } catch (_: Exception) {
        }
    }

    // ── Hakkinda cekmecesi ──
    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (_: Exception) {
            toast("Link acilamadi")
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFF8A9A93.toInt())
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun linkRow(label: String, url: String) = makeBtn(label) { openUrl(url) }.apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(12), 0, dp(12), 0)
    }

    // ── Ayarlar cekmecesi (sagdan) ──
    private fun buildSettings(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "AYARLAR"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        })

        fun seg(title: String, labels: List<String>, get: () -> Int, set: (Int) -> Unit) {
            col.addView(sectionTitle(title))
            val btns = ArrayList<TextView>()
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun refresh() {
                btns.forEachIndexed { i, b -> b.alpha = if (i == get()) 1f else 0.4f }
            }
            labels.forEachIndexed { i, l ->
                val b = makeBtn(l) {
                    set(i)
                    refresh()
                    Esp.save(this@MainActivity)
                }
                b.textSize = 10f
                btns.add(b)
                rowL.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
            }
            refresh()
            col.addView(rowL, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        fun colors(title: String, get: () -> Int, set: (Int) -> Unit) {
            col.addView(sectionTitle(title))
            val views = ArrayList<View>()
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun refresh() {
                views.forEachIndexed { i, v ->
                    (v.background as GradientDrawable).setStroke(
                        dp(if (i == get()) 3 else 1),
                        if (i == get()) Color.WHITE else 0xFF444444.toInt()
                    )
                }
            }
            Esp.palette.forEachIndexed { i, c ->
                val v = View(this)
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                if (c == 0) {
                    bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
                    bg.setColors(
                        intArrayOf(
                            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
                            0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt()
                        )
                    )
                } else {
                    bg.setColor(c)
                }
                v.background = bg
                v.setOnClickListener {
                    set(i)
                    refresh()
                    Esp.save(this@MainActivity)
                }
                views.add(v)
                rowL.addView(v, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            refresh()
            col.addView(rowL)
        }

        seg("ESP CIZGI (ekranin ustu / alti)", listOf("KAPALI", "USTTEN", "ALTTAN", "UST+ALT"),
            { Esp.lineMode }, { Esp.lineMode = it })
        seg("KALINLIK", listOf("INCE", "ORTA", "KALIN", "COK KALIN"),
            { Esp.thick }, { Esp.thick = it })
        seg("BOX STILI", listOf("TAM", "KOSELI", "DOLGU", "3D"),
            { Esp.boxStyle }, { Esp.boxStyle = it })
        seg("ISKELET (insan / kedi / kopek)", listOf("ACIK", "KAPALI"),
            { if (Esp.skeleton) 0 else 1 }, { Esp.skeleton = (it == 0) })
        seg("HAREKET HASSASIYETI", listOf("DUSUK", "ORTA", "YUKSEK"),
            { Esp.sens }, {
                Esp.sens = it
                analyzer?.threshold = Esp.threshold()
            })
        colors("RENK - BOX", { Esp.boxColor }, { Esp.boxColor = it })
        colors("RENK - ISKELET", { Esp.skelColor }, { Esp.skelColor = it })
        colors("RENK - CIZGI", { Esp.lineColor }, { Esp.lineColor = it })

        col.addView(TextView(this).apply {
            text = "Insan iskeleti gercek poz takibi, kedi/kopek iskeleti tahminidir."
            setTextColor(0xFF5A6A63.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(24), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(0xFF0A0F0D.toInt())
            isClickable = true
            addView(col)
        }
    }

    private fun buildAbout(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(24))
        }
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        col.addView(TextView(this).apply {
            text = "PANOPTIK"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "HER YERDE GOZETIMDE"
            setTextColor(0xFF3DFFA0.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        })

        col.addView(sectionTitle("GELISTIRICI"))
        col.addView(linkRow("@BY_SADRAZAM", "https://t.me/BY_SADRAZAM"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        col.addView(sectionTitle("GELISTIRICI KANALI"))
        col.addView(linkRow("Kanal 1", "https://t.me/+CCJ6lpyYEgkxYmRk"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        col.addView(linkRow("Kanal 2  \u2022  WallHack_Turkiye", "https://t.me/WallHack_Turkiye"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            })

        col.addView(TextView(this).apply {
            text = "v1.0"
            setTextColor(0xFF5A6A63.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(0xFF0A0F0D.toInt())
            isClickable = true
            addView(col)
        }
    }

    // ── Kamera ──
    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            val prev = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            if (perception == null) {
                perception = Perception(applicationContext) { msg ->
                    runOnUiThread { toast(msg) }
                }.also { it.start() }
            }
            val an = MotionAnalyzer(perception) { t, fps, blips ->
                runOnUiThread { onTargets(t, fps, blips) }
            }
            an.threshold = Esp.threshold()
            analyzer = an
            analysis.setAnalyzer(exec, an)
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onTargets(motion: List<Target>, fps: Float, blips: Int) {
        // Canli varliklar (insan/kedi/kopek) + sadece gercekten hareket eden cisimler
        val beings = perception?.beings ?: emptyList()
        val extra = motion
            .filter { m ->
                beings.none { b ->
                    m.cx >= b.cx - b.w / 2 && m.cx <= b.cx + b.w / 2 &&
                        m.cy >= b.cy - b.h / 2 && m.cy <= b.cy + b.h / 2
                }
            }
            .map { it.copy(id = 1_000_000 + it.id) }
        val t = beings + extra
        hud.targets = t
        hud.fps = fps
        hud.blips = blips
        if (hud.lockedId != -1 && t.none { it.id == hud.lockedId }) hud.lockedId = -1

        val now = System.currentTimeMillis()
        if (autoMag && t.isNotEmpty() && now - lastMagMs > 120) {
            lastMagMs = now
            updateMagnifiers(t)
        } else if (t.isEmpty()) {
            hud.mags = emptyMap()
        }

        if (recording) {
            try {
                csv?.let { w ->
                    val loc = lastLoc
                    for (x in t) {
                        w.write(
                            String.format(
                                Locale.US, "%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%s,%s\n",
                                now, x.id, x.cx, x.cy, x.w, x.h, x.conf,
                                loc?.latitude?.toString() ?: "", loc?.longitude?.toString() ?: ""
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun updateMagnifiers(t: List<Target>) {
        val bmp = preview.bitmap ?: return
        val bw = bmp.width
        val bh = bmp.height
        val out = HashMap<Int, Bitmap>()
        for (x in t.take(2)) {
            var side = (max(0.15f, max(x.w, x.h) * 2f) * bw).toInt()
            side = side.coerceIn(32, min(bw, bh))
            val l = (x.cx * bw - side / 2f).toInt().coerceIn(0, bw - side)
            val tp = (x.cy * bh - side / 2f).toInt().coerceIn(0, bh - side)
            try {
                out[x.id] = Bitmap.createBitmap(bmp, l, tp, side, side)
            } catch (_: Exception) {
            }
        }
        hud.mags = out
    }

    // ── Araclar ──
    private fun applyFilter() {
        val name = filters[filterIdx]
        if (name == "NORMAL") {
            preview.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val cm = ColorMatrix()
            when (name) {
                "MONO" -> cm.setSaturation(0f)
                "NIGHT" -> {
                    cm.setSaturation(0f)
                    cm.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                0.2f, 0f, 0f, 0f, 0f,
                                0f, 1.3f, 0f, 0f, 0f,
                                0f, 0f, 0.4f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
                "NEGATIVE" -> cm.set(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            preview.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
        hud.filterName = name
        btnFlt.text = "FILTER: $name"
    }

    private fun applyZoom() {
        val cam = camera ?: return
        val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        val z = min(zooms[zoomIdx], maxZ)
        cam.cameraControl.setZoomRatio(z)
        hud.zoomLabel = String.format(Locale.US, "%.1fx", z)
        btnZoom.text = "ZOOM: ${zooms[zoomIdx].toInt()}x"
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun snap() {
        val src = preview.bitmap
        if (src == null) {
            toast("Goruntu hazir degil")
            return
        }
        try {
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(bmp)
            c.scale(bmp.width / hud.width.toFloat(), bmp.height / hud.height.toFloat())
            hud.draw(c)

            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PANOPTIK_${stamp()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Panoptik")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                toast("Kaydedildi: Pictures/Panoptik")
            }
        } catch (e: Exception) {
            toast("Kayit hatasi: ${e.message}")
        }
    }

    private fun toggleRec() {
        if (!recording) {
            try {
                val f = File(getExternalFilesDir(null), "panoptik_${stamp()}.csv")
                csv = FileWriter(f).also { it.write("time,id,cx,cy,w,h,conf,lat,lon\n") }
                recording = true
                toast("REC: ${f.name}")
            } catch (e: Exception) {
                toast("Dosya acilamadi")
            }
        } else {
            try {
                csv?.close()
            } catch (_: Exception) {
            }
            csv = null
            recording = false
            toast("Kayit durdu")
        }
        hud.recording = recording
        btnRec.text = if (recording) "REC: ON" else "DATA REC"
    }

    private fun openMap() {
        val l = lastLoc
        if (l == null) {
            toast("GPS bekleniyor")
            return
        }
        try {
            val uri = Uri.parse("geo:${l.latitude},${l.longitude}?q=${l.latitude},${l.longitude}(PANOPTIK)")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Harita uygulamasi yok")
        }
    }

    // ── Konum ──
    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locMgr = lm
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2000L, 1f, locListener)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun updateTelemetry() {
        hud.telemetry = lastLoc?.let {
            String.format(Locale.US, "LAT %.5f  LON %.5f", it.latitude, it.longitude)
        } ?: "GPS: WAITING"
    }

    override fun onResume() {
        super.onResume()
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorMgr = sm
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sm.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            sensorMgr?.unregisterListener(gyroListener)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        perception?.close()
        camHandler.removeCallbacks(camPoll)
        try {
            locMgr?.removeUpdates(locListener)
        } catch (_: SecurityException) {
        }
        try {
            csv?.close()
        } catch (_: Exception) {
        }
        exec.shutdown()
    }
}
