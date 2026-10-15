package com.sgi.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private enum class Profile { S, G, IPHONE }
    private enum class Cap { PHOTO, VIDEO, PRO, NIGHT, PORTRAIT, SLOMO, PANO }
    private enum class Ratio(val label: String, val w: Int, val h: Int) {
        R34("3:4", 3, 4), R916("9:16", 9, 16), R11("1:1", 1, 1), FULL("FULL", 0, 0)
    }

    // ---------- state ----------
    private var profile = Profile.S
    private var cap = Cap.PHOTO
    private var ratio = Ratio.R34
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashState = 0            // 0 off, 1 auto, 2 on
    private var hdr = false
    private var timerSec = 0
    private var gridOn = false
    private var applyProfileColors = true
    private var mirrorFront = true
    private var zoom = 1f
    private var minZ = 1f
    private var maxZ = 8f
    private var lastUri: Uri? = null
    private var evLow = 0
    private var evStep = 1f

    private var cameraProvider: ProcessCameraProvider? = null
    private var extManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())

    // ---------- views ----------
    private lateinit var root: LinearLayout
    private lateinit var topBox: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var holder: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var thirds: ThirdsView
    private lateinit var bottomBox: LinearLayout
    private lateinit var zoomRow: PillRow
    private lateinit var dial: ZoomDial
    private lateinit var thumb: ImageView
    private lateinit var focusRing: View
    private lateinit var countdownTv: TextView
    private lateinit var recTv: TextView
    private lateinit var evBox: LinearLayout
    private lateinit var evTv: TextView
    private lateinit var evBar: SeekBar
    private var shutter: View? = null
    private val toolViews = LinkedHashMap<String, TextView>()
    private val pillTvs = ArrayList<TextView>()
    private var pillVals: List<Float> = listOf(0.5f, 1f, 2f)
    private var recSeconds = 0
    private var infoShown = false

    private val camLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) initCamera() else toast("لازم تسمح للتطبيق باستخدام الكاميرا")
    }
    private val audioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        startRecording(ok)
    }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        buildSkeleton()
        rebuildChrome()
        initThumb()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) initCamera()
        else camLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recording?.stop() } catch (_: Exception) {}
        executor.shutdown()
    }

    // ---------- helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)
    private fun accent(): Int = when (profile) {
        Profile.S -> Color.parseColor("#1685FF")
        Profile.G -> Color.parseColor("#F4C84A")
        Profile.IPHONE -> Color.parseColor("#FFD60A")
    }

    private fun tv(text: String, size: Float = 14f, color: Int = Color.WHITE): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
    }

    private fun bg(color: Int, radiusDp: Int = 20): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun detach(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    // =====================================================================
    //  Skeleton (created once)
    // =====================================================================
    private fun buildSkeleton() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        holder = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        holder.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        thirds = ThirdsView(this).apply { visibility = View.GONE }
        holder.addView(thirds, FrameLayout.LayoutParams(-1, -1))

        focusRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), Color.parseColor("#FFD60A"))
            }
            alpha = 0f
        }
        holder.addView(focusRing, FrameLayout.LayoutParams(dp(64), dp(64)))

        countdownTv = tv("", 90f).apply { visibility = View.GONE; setTypeface(typeface, Typeface.BOLD) }
        holder.addView(countdownTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        recTv = tv("● 00:00", 16f, Color.WHITE).apply {
            background = bg(Color.parseColor("#CCE53935"), 14)
            setPadding(dp(12), dp(4), dp(12), dp(4)); visibility = View.GONE
        }
        holder.addView(recTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })

        // PRO: exposure slider
        evTv = tv("EV +0.0", 13f)
        evBar = SeekBar(this)
        evBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(30), 0, dp(30), 0); visibility = View.GONE
        }
        evBox.addView(evTv); evBox.addView(evBar, LinearLayout.LayoutParams(-1, -2))
        holder.addView(evBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(70) })
        evBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val idx = p + evLow
                if (fromUser) camera?.cameraControl?.setExposureCompensationIndex(idx)
                evTv.text = "EV " + String.format(Locale.US, "%+.1f", idx * evStep)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // zoom dial + pills
        dial = ZoomDial(this).apply { visibility = View.GONE }
        holder.addView(dial, FrameLayout.LayoutParams(-1, dp(130), Gravity.BOTTOM))
        dial.onChange = { z -> setZoom(z, true) }
        dial.onIdle = { handler.removeCallbacks(hideDial); handler.postDelayed(hideDial, 1400) }

        zoomRow = PillRow(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        zoomRow.onDrag = { dx -> showDial(); dial.dragBy(dx) }
        zoomRow.onEnd = { handler.removeCallbacks(hideDial); handler.postDelayed(hideDial, 1400) }
        holder.addView(zoomRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })

        frame.addView(holder, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        bottomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomBox, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRatio() }

        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A2D35"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            setOnClickListener { openGallery() }
        }

        // gestures on preview: pinch zoom, tap to focus, double tap to flip
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor, false); return true }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { flipCamera(); return true }
        })
        previewView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) taps.onTouchEvent(e)
            true
        }
    }

    private fun applyRatio() {
        val fw = frame.width; val fh = frame.height
        if (fw == 0 || fh == 0) return
        val w = fw
        val h = if (ratio == Ratio.FULL) fh else minOf(fh, fw * ratio.h / ratio.w)
        val lp = holder.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
            holder.layoutParams = lp
        }
    }

    // =====================================================================
    //  Chrome (rebuilt when profile changes)
    // =====================================================================
    private fun capsFor(p: Profile): List<Pair<Cap, String>> = when (p) {
        Profile.S -> listOf(Cap.PHOTO to "PHOTO", Cap.VIDEO to "VIDEO", Cap.PRO to "PRO", Cap.NIGHT to "NIGHT")
        Profile.G -> listOf(Cap.PHOTO to "Photo", Cap.PORTRAIT to "Portrait", Cap.NIGHT to "Night Sight")
        Profile.IPHONE -> listOf(Cap.SLOMO to "SLO-MO", Cap.VIDEO to "VIDEO", Cap.PHOTO to "PHOTO", Cap.PORTRAIT to "PORTRAIT", Cap.PANO to "PANO")
    }

    private fun rebuildChrome() {
        val acc = accent()
        val dark = if (profile == Profile.IPHONE) Color.BLACK else Color.parseColor("#0B0D12")
        topBox.removeAllViews(); bottomBox.removeAllViews(); toolViews.clear()
        detach(thumb)
        topBox.setBackgroundColor(dark); bottomBox.setBackgroundColor(dark)
        dial.accent = acc

        // ---- profile chips ----
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(Profile.S to "S", Profile.G to "G", Profile.IPHONE to "iPhone").forEach { (pr, label) ->
            val sel = pr == profile
            val textColor = when {
                !sel -> Color.parseColor("#9AA0AA")
                profile == Profile.G -> Color.BLACK
                else -> Color.WHITE
            }
            val t = tv(label, if (pr == Profile.IPHONE) 14f else 18f, textColor).apply {
                setTypeface(typeface, if (sel) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(20), dp(7), dp(20), dp(7))
                if (sel) background = bg(if (profile == Profile.IPHONE) Color.parseColor("#80808A") else acc, 20)
                setOnClickListener { setProfile(pr) }
            }
            chips.addView(t, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
        }

        if (profile == Profile.IPHONE) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(12)) }
            row.addView(tool("flash"), LinearLayout.LayoutParams(dp(52), dp(40)))
            row.addView(chips, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tool("menu"), LinearLayout.LayoutParams(dp(52), dp(40)))
            topBox.addView(row)
        } else {
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(12), dp(10), dp(8)) }
            head.addView(chips)
            topBox.addView(head)
            val kinds = if (profile == Profile.S) listOf("settings", "flash", "ratio", "hdr", "timer") else listOf("flash", "hdr", "grid", "settings")
            val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(10)) }
            kinds.forEach { tools.addView(tool(it), LinearLayout.LayoutParams(0, dp(40), 1f)) }
            topBox.addView(tools)
        }

        // ---- shutter row ----
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(12), dp(28), dp(6)) }
        row.addView(thumb, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val sh = View(this).apply { setOnClickListener { onShutter() } }
        shutter = sh
        row.addView(sh, LinearLayout.LayoutParams(dp(78), dp(78)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val flip = tv("⟳", 26f).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2A2D35")) }
            setOnClickListener { flipCamera() }
        }
        row.addView(flip, LinearLayout.LayoutParams(dp(52), dp(52)))
        bottomBox.addView(row)

        // ---- capture-mode labels ----
        val labels = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(8), dp(6), dp(18)) }
        capsFor(profile).forEach { (c, label) ->
            val sel = c == cap
            val color = when {
                !sel -> Color.parseColor("#B0B5BF")
                profile == Profile.G -> Color.BLACK
                profile == Profile.S -> Color.parseColor("#3DB8FF")
                else -> acc
            }
            val t = tv(label, 12f, color).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setTypeface(typeface, if (sel) Typeface.BOLD else Typeface.NORMAL)
                if (sel && profile == Profile.G) background = bg(acc, 16)
                setOnClickListener { setCap(c) }
            }
            labels.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bottomBox.addView(labels)

        // ---- zoom pills ----
        pillVals = when (profile) {
            Profile.G -> listOf(0.5f, 1f, 2f, 5f)
            else -> listOf(0.5f, 1f, 2f)
        }
        zoomRow.removeAllViews(); pillTvs.clear()
        zoomRow.background = bg(Color.parseColor("#66000000"), 24)
        zoomRow.setPadding(dp(6), dp(4), dp(6), dp(4))
        pillVals.forEach { v ->
            val t = tv("", 14f).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(44)
                setOnClickListener {
                    if (v < minZ - 0.01f) toast("العدسة الواسعة 0.5x غير متاحة لتطبيقات الطرف الثالث على هذا الجهاز")
                    setZoom(v, false)
                }
                setOnLongClickListener { showDial(); true }
            }
            pillTvs.add(t); zoomRow.addView(t, LinearLayout.LayoutParams(-2, -2))
        }
        updateZoomUi(); updateToolUi(); updateShutter()
    }

    private fun tool(kind: String): TextView {
        val t = tv("", if (kind == "flash") 14f else 15f).apply {
            setOnClickListener { onTool(kind) }
        }
        toolViews[kind] = t
        return t
    }

    private fun onTool(kind: String) {
        when (kind) {
            "flash" -> { flashState = (flashState + 1) % 3; applyFlash() }
            "ratio" -> {
                ratio = Ratio.values()[(ratio.ordinal + 1) % Ratio.values().size]
                applyRatio(); handler.postDelayed({ bind() }, 200)
            }
            "hdr" -> {
                hdr = !hdr
                if (cap != Cap.PHOTO) toast("HDR يعمل في وضع الصور العادي فقط")
                else bind()
            }
            "timer" -> timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            "grid" -> { gridOn = !gridOn; thirds.visibility = if (gridOn) View.VISIBLE else View.GONE }
            "settings", "menu" -> showSettings()
        }
        updateToolUi()
    }

    private fun updateToolUi() {
        val acc = accent()
        toolViews.forEach { (kind, t) ->
            when (kind) {
                "flash" -> {
                    val on = flashState != 0
                    t.text = when (flashState) { 1 -> "⚡ A"; 2 -> "⚡ ON"; else -> "⚡ OFF" }
                    t.setTextColor(if (on) acc else Color.WHITE)
                }
                "ratio" -> { t.text = ratio.label; t.setTextColor(Color.WHITE) }
                "hdr" -> { t.text = if (profile == Profile.G) "HDR+" else "HDR"; t.setTextColor(if (hdr) acc else Color.WHITE) }
                "timer" -> { t.text = "⏱" + (if (timerSec > 0) " ${timerSec}s" else ""); t.setTextColor(if (timerSec > 0) acc else Color.WHITE) }
                "grid" -> { t.text = "▦"; t.setTextColor(if (gridOn) acc else Color.WHITE) }
                "settings" -> { t.text = "⚙"; t.setTextColor(Color.WHITE) }
                "menu" -> { t.text = "◎"; t.setTextColor(Color.WHITE) }
                else -> {}
            }
        }
    }

    private fun updateShutter() {
        val sh = shutter ?: return
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(4), Color.WHITE) }
        val recording = this.recording != null
        val inner = GradientDrawable().apply {
            if (recording) { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#E53935")) }
            else { shape = GradientDrawable.OVAL; setColor(if (cap == Cap.VIDEO || cap == Cap.SLOMO) Color.parseColor("#E53935") else Color.WHITE) }
        }
        val ld = LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(ring, inner))
        val inset = if (recording) dp(24) else dp(8)
        ld.setLayerInset(1, inset, inset, inset, inset)
        sh.background = ld
    }

    private fun updateZoomUi() {
        val acc = accent()
        var sel = 0
        for (i in pillVals.indices) if (zoom >= pillVals[i] - 0.05f) sel = i
        pillTvs.forEachIndexed { i, t ->
            val v = pillVals[i]
            if (i == sel) {
                val near = abs(zoom - zoom.roundToInt()) < 0.05f
                t.text = (if (near) "${zoom.roundToInt()}" else f1(zoom)) + "x"
                t.setTextColor(acc)
                t.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) }
            } else {
                t.text = if (v < 1f) f1(v) else "${v.roundToInt()}"
                t.setTextColor(Color.WHITE); t.background = null
            }
        }
    }

    // =====================================================================
    //  Profile / capture mode
    // =====================================================================
    private fun setProfile(p: Profile) {
        if (p == profile) return
        profile = p
        if (capsFor(p).none { it.first == cap }) { cap = Cap.PHOTO; bind() }
        rebuildChrome()
    }

    private fun setCap(c: Cap) {
        if (c == cap) return
        if (recording != null) { toast("أوقف التسجيل أولاً"); return }
        cap = c
        if (!infoShown && (c == Cap.SLOMO || c == Cap.PANO)) {
            infoShown = true
            toast("SLO-MO وPANO يحتاجان تقنيات خاصة غير مدعومة بعد، المعاينة فقط")
        }
        rebuildChrome()
        bind()
    }

    // =====================================================================
    //  Camera
    // =====================================================================
    private fun initCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            cameraProvider = f.get()
            try {
                val ef = ExtensionsManager.getInstanceAsync(this, cameraProvider!!)
                ef.addListener({
                    try { extManager = ef.get() } catch (_: Exception) {}
                    bind()
                }, ContextCompat.getMainExecutor(this))
            } catch (_: Throwable) { bind() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun extFor(): Int? = when (cap) {
        Cap.NIGHT -> ExtensionMode.NIGHT
        Cap.PORTRAIT -> ExtensionMode.BOKEH
        Cap.PHOTO -> if (hdr) ExtensionMode.HDR else null
        else -> null
    }

    private fun bind(allowExt: Boolean = true) {
        val provider = cameraProvider ?: return
        if (recording != null) return
        var selector = CameraSelector.Builder().requireLensFacing(lens).build()
        val mode = if (allowExt) extFor() else null
        if (mode != null) {
            val em = extManager
            if (em != null && em.isExtensionAvailable(selector, mode)) selector = em.getExtensionEnabledCameraSelector(selector, mode)
            else toast("هذا الوضع غير مدعوم على كاميرا جهازك، سيعمل التصوير العادي")
        }
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(
            if (ratio == Ratio.R916 || ratio == Ratio.FULL) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        ).build()
        val preview = Preview.Builder().setResolutionSelector(rs).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val group = UseCaseGroup.Builder().addUseCase(preview)
        if (cap == Cap.VIDEO) {
            val recorder = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            ).build()
            videoCapture = VideoCapture.withOutput(recorder)
            imageCapture = null
            group.addUseCase(videoCapture!!)
        } else {
            videoCapture = null
            imageCapture = ImageCapture.Builder().setResolutionSelector(rs)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            group.addUseCase(imageCapture!!)
            previewView.viewPort?.let { group.setViewPort(it) }
        }
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group.build())
            onBound()
        } catch (e: Exception) {
            if (mode != null) bind(false)
            else toast("تعذر تشغيل الكاميرا: ${e.localizedMessage}")
        }
    }

    private fun onBound() {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value
        minZ = zs?.minZoomRatio ?: 1f
        maxZ = minOf(zs?.maxZoomRatio ?: 8f, 30f)
        dial.minZ = minZ; dial.maxZ = maxZ
        setZoom(zoom.coerceIn(minZ, maxZ), false)
        applyFlash()
        // PRO exposure slider
        val es = cam.cameraInfo.exposureState
        if (cap == Cap.PRO && es.isExposureCompensationSupported) {
            val r = es.exposureCompensationRange
            evLow = r.lower; evStep = es.exposureCompensationStep.toFloat()
            evBar.max = r.upper - r.lower
            evBar.progress = -r.lower
            evTv.text = "EV +0.0"
            evBox.visibility = View.VISIBLE
        } else {
            evBox.visibility = View.GONE
            if (cap == Cap.PRO) toast("التحكم اليدوي بالتعريض غير مدعوم على هذه الكاميرا")
        }
    }

    private fun flipCamera() {
        if (recording != null) return
        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        zoom = 1f
        bind()
    }

    private fun applyFlash() {
        if (cap == Cap.VIDEO) {
            camera?.cameraControl?.enableTorch(flashState == 2)
        } else {
            imageCapture?.flashMode = when (flashState) {
                1 -> ImageCapture.FLASH_MODE_AUTO
                2 -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }
        updateToolUi()
    }

    private fun setZoom(z: Float, fromDial: Boolean) {
        val nz = z.coerceIn(minZ, maxZ)
        zoom = nz
        camera?.cameraControl?.setZoomRatio(nz)
        dial.value = nz
        if (!fromDial) dial.invalidate()
        updateZoomUi()
    }

    private val hideDial = Runnable {
        zoomRow.visibility = View.VISIBLE
        dial.animate().alpha(0f).setDuration(200).withEndAction { dial.visibility = View.GONE }.start()
    }

    private fun showDial() {
        handler.removeCallbacks(hideDial)
        dial.value = zoom
        dial.visibility = View.VISIBLE
        dial.animate().alpha(1f).setDuration(120).start()
        zoomRow.visibility = View.INVISIBLE
        handler.postDelayed(hideDial, 3500)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val pt = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {}
        focusRing.animate().cancel()
        focusRing.x = x - dp(32); focusRing.y = y - dp(32)
        focusRing.scaleX = 1.4f; focusRing.scaleY = 1.4f; focusRing.alpha = 1f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200)
            .withEndAction { focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).start() }.start()
    }

    // =====================================================================
    //  Shutter / photo / video
    // =====================================================================
    private fun onShutter() {
        when (cap) {
            Cap.VIDEO -> toggleRecord()
            Cap.SLOMO, Cap.PANO -> toast("هذا الوضع غير مدعوم بعد")
            else -> if (timerSec > 0) countdown(timerSec) else takePhoto()
        }
    }

    private fun countdown(n: Int) {
        if (n <= 0) { countdownTv.visibility = View.GONE; takePhoto(); return }
        countdownTv.text = n.toString(); countdownTv.visibility = View.VISIBLE
        handler.postDelayed({ countdown(n - 1) }, 1000)
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SGI_${stamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SGICamera")
        }
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = (lens == CameraSelector.LENS_FACING_FRONT && mirrorFront) }
        val out = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(meta).build()
        val usedProfile = profile
        ic.takePicture(out, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri
                if (uri != null && applyProfileColors) applyColorProfile(uri, usedProfile)
                runOnUiThread {
                    toast("تم حفظ الصورة في Pictures/SGICamera")
                    if (uri != null) { lastUri = uri; loadThumb(uri) }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { toast("فشل التصوير: ${exception.message}") }
            }
        })
    }

    // Approximate colour looks only — not the real Samsung / Pixel / iPhone pipelines.
    private fun applyColorProfile(uri: Uri, p: Profile) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(input); input.close()
            if (bitmap == null) return
            val outBmp = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outBmp); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val m = when (p) {
                Profile.S -> ColorMatrix(floatArrayOf(1.12f, 0f, 0f, 0f, 3f, 0f, 1.10f, 0f, 0f, 2f, 0f, 0f, 1.16f, 0f, 1f, 0f, 0f, 0f, 1f, 0f))
                Profile.G -> ColorMatrix(floatArrayOf(1.02f, 0f, 0f, 0f, 1f, 0f, 1.02f, 0f, 0f, 1f, 0f, 0f, 1.02f, 0f, 1f, 0f, 0f, 0f, 1f, 0f))
                Profile.IPHONE -> ColorMatrix(floatArrayOf(1.04f, 0f, 0f, 0f, 3f, 0f, 1.02f, 0f, 0f, 1f, 0f, 0f, 0.98f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            }
            paint.colorFilter = ColorMatrixColorFilter(m)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            contentResolver.openOutputStream(uri, "wt")?.use { outBmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle(); outBmp.recycle()
        } catch (_: Exception) {}
    }

    private fun toggleRecord() {
        val r = recording
        if (r != null) { r.stop(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording(true)
        else audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording(audio: Boolean) {
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SGI_${stamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SGICamera")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (audio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Start) {
                recSeconds = 0; recTv.text = "● 00:00"; recTv.visibility = View.VISIBLE
                handler.postDelayed(recTick, 1000)
                updateShutter()
            } else if (ev is VideoRecordEvent.Finalize) {
                recording = null
                handler.removeCallbacks(recTick)
                recTv.visibility = View.GONE
                if (!ev.hasError()) {
                    val u = ev.outputResults.outputUri
                    lastUri = u; loadThumb(u)
                    toast("تم حفظ الفيديو في Movies/SGICamera")
                } else toast("فشل تسجيل الفيديو")
                updateShutter()
            }
        }
        updateShutter()
    }

    private val recTick = object : Runnable {
        override fun run() {
            recSeconds++
            recTv.text = String.format(Locale.US, "● %02d:%02d", recSeconds / 60, recSeconds % 60)
            handler.postDelayed(this, 1000)
        }
    }

    // =====================================================================
    //  Gallery thumbnail
    // =====================================================================
    private fun initThumb() {
        executor.execute {
            try {
                val proj = arrayOf(MediaStore.Images.Media._ID)
                val sel = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
                val args = if (Build.VERSION.SDK_INT >= 29) arrayOf("%SGICamera%") else null
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    if (c.moveToFirst()) {
                        val u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        lastUri = u; loadThumb(u)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadThumb(uri: Uri) {
        executor.execute {
            try {
                val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else {
                    val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                }
                if (bmp != null) runOnUiThread { thumb.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }

    private fun openGallery() {
        try {
            val u = lastUri
            if (u != null) {
                startActivity(Intent(Intent.ACTION_VIEW).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
            }
        } catch (_: Exception) { toast("افتح المعرض من الهاتف") }
    }

    // =====================================================================
    //  Settings dialog
    // =====================================================================
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        val dlg = AlertDialog.Builder(this).setTitle("SGI Camera V4").setView(box).setPositiveButton("تمام", null).create()
        fun row(label: () -> String, action: () -> Unit) {
            val t = TextView(this).apply { text = label(); textSize = 16f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(12)) }
            t.setOnClickListener { action(); t.text = label(); updateToolUi() }
            box.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        fun onOff(b: Boolean) = if (b) "تشغيل" else "إيقاف"
        row({ "الشبكة (Grid): ${onOff(gridOn)}" }) { gridOn = !gridOn; thirds.visibility = if (gridOn) View.VISIBLE else View.GONE }
        row({ "المؤقت: " + (if (timerSec == 0) "إيقاف" else "$timerSec ث") }) { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 } }
        row({ "نسبة الصورة: ${ratio.label}" }) {
            ratio = Ratio.values()[(ratio.ordinal + 1) % Ratio.values().size]
            applyRatio(); handler.postDelayed({ bind() }, 200)
        }
        row({ "HDR: ${onOff(hdr)}" }) { hdr = !hdr; if (cap == Cap.PHOTO) bind() }
        row({ "ألوان الوضع (S/G/iPhone): ${onOff(applyProfileColors)}" }) { applyProfileColors = !applyProfileColors }
        row({ "عكس صورة السيلفي: ${onOff(mirrorFront)}" }) { mirrorFront = !mirrorFront }
        box.addView(TextView(this).apply { text = "الأوضاع S/G/iPhone ملفات ألوان تقريبية وليست معالجة الشركات الأصلية."; textSize = 12f; alpha = 0.6f; setPadding(dp(8), dp(12), dp(8), 0) })
        dlg.show()
    }

    // =====================================================================
    //  Custom views
    // =====================================================================
    private class ThirdsView(ctx: Context) : View(ctx) {
        private val p = Paint().apply { color = Color.argb(140, 255, 255, 255); strokeWidth = 2f }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawLine(w / 3, 0f, w / 3, h, p); c.drawLine(2 * w / 3, 0f, 2 * w / 3, h, p)
            c.drawLine(0f, h / 3, w, h / 3, p); c.drawLine(0f, 2 * h / 3, w, 2 * h / 3, p)
        }
    }

    /** Row of zoom pills; a horizontal swipe on it opens/drives the dial. */
    private class PillRow(ctx: Context) : LinearLayout(ctx) {
        var onDrag: ((Float) -> Unit)? = null
        var onEnd: (() -> Unit)? = null
        private var downX = 0f
        private var lastX = 0f
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; lastX = e.x }
            else if (e.actionMasked == MotionEvent.ACTION_MOVE && abs(e.x - downX) > slop * 2) { lastX = e.x; return true }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_MOVE) { onDrag?.invoke(e.x - lastX); lastX = e.x }
            else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { onEnd?.invoke() }
            return true
        }
    }

    /** iPhone-style circular zoom wheel: drag left/right to change zoom on a log scale. */
    private class ZoomDial(ctx: Context) : View(ctx) {
        var minZ = 0.5f
        var maxZ = 10f
        var value = 1f
        var accent = Color.YELLOW
        var onChange: ((Float) -> Unit)? = null
        var onIdle: (() -> Unit)? = null
        private val d = ctx.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val degPerOctave = 34f
        private var lastX = 0f
        private var lastNotch = -1

        init { alpha = 0f }

        private fun radius() = width * 0.62f

        fun dragBy(dx: Float) {
            val pxPerOctave = radius() * Math.toRadians(degPerOctave.toDouble()).toFloat()
            val nv = (value * 2f.pow(-dx / pxPerOctave)).coerceIn(minZ, maxZ)
            value = nv
            val notch = (log2(nv) * 4).roundToInt()
            if (notch != lastNotch) { lastNotch = notch; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            onChange?.invoke(nv)
            invalidate()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; return true }
                MotionEvent.ACTION_MOVE -> { dragBy(e.x - lastX); lastX = e.x }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onIdle?.invoke()
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val vis = 108f * d
            val r = radius()
            val cx = w / 2f; val cy = h - vis + r
            p.style = Paint.Style.FILL; p.color = Color.argb(110, 0, 0, 0)
            c.drawCircle(cx, cy, r, p)

            val base = log2(value)
            var v = 0.5f
            while (v <= maxZ + 0.001f) {
                if (v >= minZ - 0.001f) {
                    val major = v == 0.5f || v == 1f || v == 2f || v == 3f || v == 5f || v == 10f || v == 20f
                    val th = (log2(v) - base) * degPerOctave
                    if (abs(th) < 78f) {
                        val rad = Math.toRadians(th.toDouble())
                        val s = sin(rad).toFloat(); val co = cos(rad).toFloat()
                        val len = (if (major) 16f else 9f) * d
                        p.color = Color.argb(if (major) 255 else 170, 255, 255, 255)
                        p.strokeWidth = (if (major) 2f else 1.4f) * d
                        c.drawLine(cx + r * s, cy - r * co, cx + (r - len) * s, cy - (r - len) * co, p)
                        if (major) {
                            val tr = r - len - 14f * d
                            val tx = cx + tr * s; val ty = cy - tr * co
                            p.style = Paint.Style.FILL; p.textSize = 12f * d; p.textAlign = Paint.Align.CENTER
                            val label = if (v < 1f) "0.5" else "${v.toInt()}"
                            c.save(); c.rotate(th, tx, ty); c.drawText(label, tx, ty + 4f * d, p); c.restore()
                        }
                    }
                }
                v += if (v < 2f) 0.25f else if (v < 5f) 0.5f else 1f
            }
            // centre marker + readout
            p.color = accent; p.strokeWidth = 3f * d
            c.drawLine(cx, cy - r - 4f * d, cx, cy - r + 26f * d, p)
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
            p.textSize = 18f * d; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(f1(value) + "×  " + (value * 24f).roundToInt() + "MM", cx, cy - r + 56f * d, p)
            p.typeface = Typeface.DEFAULT
        }

        private fun f1(x: Float) = String.format(Locale.US, "%.1f", x)
    }
}
