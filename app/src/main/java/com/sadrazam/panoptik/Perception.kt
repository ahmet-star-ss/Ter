package com.sadrazam.panoptik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Target.kind degerleri */
object Kind {
    const val MOTION = 0
    const val PERSON = 1
    const val CAT = 2
    const val DOG = 3
    const val ANIMAL = 4

    fun name(k: Int) = when (k) {
        PERSON -> "PERSON"
        CAT -> "CAT"
        DOG -> "DOG"
        ANIMAL -> "ANIMAL"
        else -> "MOTION"
    }
}

/** Titremeyi azaltan adaptif filtre (One Euro): dururken cok yumusak, hareketteyken gecikmesiz. */
class OneEuro(
    private val minCutoff: Float = 0.8f,
    private val beta: Float = 8f,
    private val dCutoff: Float = 1f
) {
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0L
    private var init = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, tMs: Long): Float {
        if (!init) {
            init = true
            xPrev = x
            dxPrev = 0f
            tPrev = tMs
            return x
        }
        val dt = max((tMs - tPrev) / 1000f, 0.001f)
        tPrev = tMs
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        dxPrev = dxHat
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat
        return xHat
    }
}

/** Modelden gelen ham tespit (0..1 normalize, dik goruntu). */
class RawBeing(val kind: Int, val box: RectF, val conf: Int, val pts: FloatArray?)

/** Kimlikli, yumusatilmis takip. Sekiller git-gel yapmaz. */
class BeingTracker {

    private class Trk(var id: Int, var kind: Int) {
        val fx = Array(4) { OneEuro() } // cx, cy, w, h
        var fp: Array<OneEuro>? = null
        var cx = 0f
        var cy = 0f
        var w = 0f
        var h = 0f
        var pts: FloatArray? = null
        var conf = 0
        var hits = 0
        var miss = 0
        var dirScore = 0f
        var dir = 1
        val votes = IntArray(5)
    }

    private val trks = ArrayList<Trk>()
    private var nextId = 1

    private fun group(k: Int) = if (k == Kind.PERSON) 0 else 1

    fun update(raws: List<RawBeing>, t: Long): List<Target> {
        val used = HashSet<Trk>()
        for (r in raws) {
            val rcx = (r.box.left + r.box.right) / 2f
            val rcy = (r.box.top + r.box.bottom) / 2f
            var best: Trk? = null
            var bd = 0.25f
            for (k in trks) {
                if (k in used) continue
                if (group(k.kind) != group(r.kind)) continue
                val d = hypot(k.cx - rcx, k.cy - rcy)
                if (d < bd) {
                    bd = d
                    best = k
                }
            }
            val k = best ?: Trk(nextId++, r.kind).also {
                it.cx = rcx; it.cy = rcy
                it.w = r.box.width(); it.h = r.box.height()
                trks.add(it)
            }
            used.add(k)
            k.hits++
            k.miss = 0

            // tur oylamasi (kedi/kopek arasi titreme olmasin)
            k.votes[r.kind.coerceIn(0, 4)] += 1
            if (k.votes.sum() > 24) for (i in k.votes.indices) k.votes[i] = k.votes[i] / 2
            var bk = k.kind
            for (i in 1..4) if (k.votes[i] > k.votes[bk.coerceIn(0, 4)]) bk = i
            k.kind = bk

            val ncx = k.fx[0].filter(rcx, t)
            val ncy = k.fx[1].filter(rcy, t)
            val nw = k.fx[2].filter(r.box.width(), t)
            val nh = k.fx[3].filter(r.box.height(), t)
            k.dirScore = k.dirScore * 0.8f + (ncx - k.cx) * 10f
            if (abs(k.dirScore) > 0.05f) k.dir = if (k.dirScore > 0f) 1 else -1
            k.cx = ncx; k.cy = ncy; k.w = nw; k.h = nh
            k.conf = r.conf

            if (r.pts != null) {
                val n = r.pts.size
                var fp = k.fp
                if (fp == null || fp.size != n) {
                    fp = Array(n) { OneEuro() }
                    k.fp = fp
                }
                val out = FloatArray(n)
                var i = 0
                while (i < n) {
                    out[i] = fp[i].filter(r.pts[i], t)
                    out[i + 1] = fp[i + 1].filter(r.pts[i + 1], t)
                    out[i + 2] = r.pts[i + 2] // gorunurluk oldugu gibi
                    i += 3
                }
                k.pts = out
            } else {
                k.pts = null
            }
        }
        for (k in trks) if (k !in used) k.miss++
        trks.removeAll { it.miss > 6 }

        val out = ArrayList<Target>()
        for (k in trks) {
            if (k.hits < 2 || k.miss > 4) continue
            val pts: FloatArray? = when {
                k.kind == Kind.PERSON -> k.pts
                else -> animalPts(k)
            }
            out.add(
                Target(
                    k.id, k.cx, k.cy, k.w, k.h,
                    if (k.miss > 0) max(10, k.conf - 15 * k.miss) else k.conf,
                    k.kind, pts
                )
            )
        }
        return out
    }

    /** Kedi/kopek icin kutuya oturtulmus tahmini iskelet (gercek poz modeli yok). */
    private fun animalPts(k: Trk): FloatArray {
        val tpl = Esp.ANIMAL_TPL
        val n = tpl.size / 2
        val o = FloatArray(n * 3)
        val left = k.cx - k.w / 2f
        val top = k.cy - k.h / 2f
        for (i in 0 until n) {
            val tx = if (k.dir > 0) tpl[2 * i] else 1f - tpl[2 * i]
            o[3 * i] = left + tx * k.w
            o[3 * i + 1] = top + tpl[2 * i + 1] * k.h
            o[3 * i + 2] = 1f
        }
        return o
    }
}

/**
 * Canli varlik (insan / kedi / kopek / hayvan) algilama.
 * Modeller assets klasorunde: pose_landmarker_lite.task, efficientdet_lite0.tflite
 * (GitHub Actions bunlari derlemeden once indirir). Model yoksa sadece hareket modu calisir.
 */
class Perception(
    private val ctx: Context,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    var beings: List<Target> = emptyList()

    @Volatile
    var ready = false

    private val ex = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var pose: PoseLandmarker? = null
    private var det: ObjectDetector? = null
    private val tracker = BeingTracker()
    private var cycle = 0
    private var lastObjs: List<RawBeing> = emptyList()
    private var lastObjsAt = 0L

    fun start() {
        ex.execute { load() }
    }

    private fun load() {
        try {
            val o = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(3)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            pose = PoseLandmarker.createFromOptions(ctx, o)
        } catch (_: Throwable) {
            pose = null
        }
        try {
            val o = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.VIDEO)
                .setMaxResults(6)
                .setScoreThreshold(0.4f)
                .setCategoryAllowlist(
                    listOf("person", "cat", "dog", "horse", "sheep", "cow", "bear", "bird")
                )
                .build()
            det = ObjectDetector.createFromOptions(ctx, o)
        } catch (_: Throwable) {
            det = null
        }
        ready = pose != null || det != null
        onStatus(
            when {
                pose != null && det != null -> "AI: INSAN + HAYVAN"
                pose != null -> "AI: SADECE INSAN"
                det != null -> "AI: SADECE NESNE"
                else -> "AI MODELI YOK - SADECE HAREKET"
            }
        )
    }

    /** Analiz karesini ML kuyruguna ver (mesgulse atla). ImageProxy kapanmadan cagrilmali. */
    fun offer(image: ImageProxy) {
        if (!ready) return
        if (!busy.compareAndSet(false, true)) return
        val bmp: Bitmap = try {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            if (rot == 0) raw else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                if (r !== raw) raw.recycle()
                r
            }
        } catch (_: Throwable) {
            busy.set(false)
            return
        }
        ex.execute {
            try {
                run(bmp)
            } catch (_: Throwable) {
            } finally {
                bmp.recycle()
                busy.set(false)
            }
        }
    }

    private fun run(bmp: Bitmap) {
        val now = SystemClock.uptimeMillis()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val mp = BitmapImageBuilder(bmp).build()
        val raws = ArrayList<RawBeing>()

        pose?.let { p ->
            val res = p.detectForVideo(mp, now)
            for (lm in res.landmarks()) {
                val n = min(33, lm.size)
                if (n < 25) continue
                val pts = FloatArray(33 * 3)
                var minx = 1f; var miny = 1f; var maxx = 0f; var maxy = 0f
                var vs = 0f
                var vc = 0
                for (i in 0 until n) {
                    val l = lm[i]
                    val v = l.visibility().orElse(1f)
                    pts[i * 3] = l.x()
                    pts[i * 3 + 1] = l.y()
                    pts[i * 3 + 2] = v
                    if (v >= 0.4f) {
                        minx = min(minx, l.x()); maxx = max(maxx, l.x())
                        miny = min(miny, l.y()); maxy = max(maxy, l.y())
                        vs += v; vc++
                    }
                }
                if (vc < 6 || maxx <= minx || maxy <= miny) continue
                val padX = (maxx - minx) * 0.08f
                val padY = (maxy - miny) * 0.08f
                val box = RectF(
                    (minx - padX).coerceIn(0f, 1f), (miny - padY).coerceIn(0f, 1f),
                    (maxx + padX).coerceIn(0f, 1f), (maxy + padY).coerceIn(0f, 1f)
                )
                raws.add(RawBeing(Kind.PERSON, box, (vs / vc * 100f).toInt().coerceIn(30, 99), pts))
            }
        }

        // Nesne tespiti her 2 turda bir (pil/hiz), arada son sonuc kullanilir
        det?.let { d ->
            if (cycle % 2 == 0) {
                val res = d.detectForVideo(mp, now)
                val list = ArrayList<RawBeing>()
                for (dt in res.detections()) {
                    val cat = dt.categories().firstOrNull() ?: continue
                    val kind = when (cat.categoryName()) {
                        "person" -> Kind.PERSON
                        "cat" -> Kind.CAT
                        "dog" -> Kind.DOG
                        else -> Kind.ANIMAL
                    }
                    val b = dt.boundingBox()
                    val box = RectF(
                        (b.left / bw).coerceIn(0f, 1f), (b.top / bh).coerceIn(0f, 1f),
                        (b.right / bw).coerceIn(0f, 1f), (b.bottom / bh).coerceIn(0f, 1f)
                    )
                    if (box.width() < 0.02f || box.height() < 0.02f) continue
                    list.add(RawBeing(kind, box, (cat.score() * 100f).toInt().coerceIn(1, 99), null))
                }
                lastObjs = list
                lastObjsAt = now
            }
        }
        cycle++
        if (now - lastObjsAt < 500L) {
            for (o in lastObjs) {
                // Poz bulunan kisinin ustune ikinci kutu cizme
                if (o.kind == Kind.PERSON && raws.any { r ->
                        r.kind == Kind.PERSON && inside(r.box, o.box.centerX(), o.box.centerY())
                    }) continue
                raws.add(o)
            }
        }

        beings = tracker.update(raws, now)
    }

    private fun inside(r: RectF, x: Float, y: Float) =
        x >= r.left && x <= r.right && y >= r.top && y <= r.bottom

    fun close() {
        try { pose?.close() } catch (_: Throwable) {}
        try { det?.close() } catch (_: Throwable) {}
        ex.shutdown()
    }
}
