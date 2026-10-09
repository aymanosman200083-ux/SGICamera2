package com.sgi.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mode = CameraMode.SAMSUNG
    private var flashOn = false
    private var zoom = 1f
    private lateinit var modeS: TextView
    private lateinit var modeG: TextView
    private lateinit var modeI: TextView
    private lateinit var flashButton: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var statusLabel: TextView

    enum class CameraMode { SAMSUNG, PIXEL, IPHONE }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "لازم تسمح للتطبيق باستخدام الكاميرا", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(10, 12, 10, 12) }
        modeS = modeButton("S", "#1685FF") { setMode(CameraMode.SAMSUNG) }
        modeG = modeButton("G", "#F4C84A") { setMode(CameraMode.PIXEL) }
        modeI = modeButton("iPhone", "#D8D9E8") { setMode(CameraMode.IPHONE) }
        header.addView(modeS, weighted()); header.addView(modeG, weighted()); header.addView(modeI, weighted())
        root.addView(header)

        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 8) }
        flashButton = smallButton("⚡ OFF") { flashOn = !flashOn; flashButton.text = if (flashOn) "⚡ ON" else "⚡ OFF"; imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
        val ratio = smallButton("3:4") { Toast.makeText(this, "نسبة المعاينة 3:4 — نسب الحفظ تعتمد على حساس الكاميرا", Toast.LENGTH_SHORT).show() }
        val hdr = smallButton("HDR Auto") { Toast.makeText(this, "HDR الحقيقي يعتمد على دعم الحساس والنظام؛ هذا الخيار لا يفعّل خوارزمية Pixel الأصلية", Toast.LENGTH_LONG).show() }
        val settings = smallButton("⚙") { showSettings() }
        toolbar.addView(flashButton, weighted()); toolbar.addView(ratio, weighted()); toolbar.addView(hdr, weighted()); toolbar.addView(settings, weighted())
        root.addView(toolbar)

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))

        val zoomBar = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(8, 8, 8, 8) }
        zoomLabel = TextView(this).apply { text = "0.5×     1×     2×"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(24, 10, 24, 10); setOnClickListener { zoom = if (zoom < 1.5f) 2f else if (zoom < 2.5f) 4f else 1f; applyZoom() } }
        zoomBar.addView(zoomLabel); root.addView(zoomBar)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(12, 12, 12, 8) }
        val gallery = smallButton("آخر صورة") { openGallery() }
        val shutter = TextView(this).apply { text = "●"; textSize = 58f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(24, 0, 24, 0); setOnClickListener { takePhoto() } }
        val flip = smallButton("⟳") { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; startCamera() }
        controls.addView(gallery, weighted()); controls.addView(shutter, weighted()); controls.addView(flip, weighted()); root.addView(controls)

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(4, 10, 4, 18) }
        listOf("PHOTO", "VIDEO", "PRO", "NIGHT").forEach { label ->
            val t = TextView(this).apply { text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(if (label == "PHOTO") Color.CYAN else Color.LTGRAY); setPadding(10, 8, 10, 8); setOnClickListener { if (label != "PHOTO") Toast.makeText(this@MainActivity, "$label غير متاح في الإصدار الأول؛ التصوير الثابت يعمل", Toast.LENGTH_SHORT).show() } }
            modes.addView(t, weighted())
        }
        root.addView(modes)
        statusLabel = TextView(this).apply { text = "SGI Camera V4 • جاهز"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(4, 0, 4, 6) }
        root.addView(statusLabel)
        setContentView(root)
        setMode(CameraMode.SAMSUNG)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun modeButton(label: String, color: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = if (label == "iPhone") 15f else 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(8, 10, 8, 10); background = rounded(if (label == "S") "#1685FF" else "#20242C"); setOnClickListener { action() }
    }
    private fun smallButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(6, 10, 6, 10); setOnClickListener { action() }
    }
    private fun rounded(color: String): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable(); d.setColor(Color.parseColor(color)); d.cornerRadius = 36f; d.setStroke(1, Color.DKGRAY); return d
    }

    private fun setMode(newMode: CameraMode) {
        mode = newMode
        modeS.background = rounded(if (mode == CameraMode.SAMSUNG) "#1685FF" else "#20242C")
        modeG.background = rounded(if (mode == CameraMode.PIXEL) "#F4C84A" else "#20242C")
        modeI.background = rounded(if (mode == CameraMode.IPHONE) "#BFC4DC" else "#20242C")
        modeI.setTextColor(if (mode == CameraMode.IPHONE) Color.BLACK else Color.WHITE)
        statusLabel.text = when (mode) {
            CameraMode.SAMSUNG -> "S Mode • ألوان زاهية • معالجة SGI"
            CameraMode.PIXEL -> "G Mode • ألوان طبيعية • معالجة SGI"
            CameraMode.IPHONE -> "iPhone Mode • ألوان دافئة • معالجة SGI"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                provider.unbindAll(); camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
                imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                applyZoom()
            } catch (e: Exception) { Toast.makeText(this, "تعذر تشغيل الكاميرا: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoom() {
        val state: ZoomState? = camera?.cameraInfo?.zoomState?.value
        val min = state?.minZoomRatio ?: 1f; val max = state?.maxZoomRatio ?: 4f
        zoom = zoom.coerceIn(min, max)
        camera?.cameraControl?.setZoomRatio(zoom)
        zoomLabel.text = "0.5×      ${String.format(Locale.US, "%.1f×", zoom)}      2×  (اضغط للتغيير)"
    }

    private fun takePhoto() {
        if (!::imageCapture.isInitialized) return
        val name = "SGI_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SGICamera") }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        imageCapture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                runOnUiThread { statusLabel.text = "تم حفظ الصورة • ${modeName()}"; Toast.makeText(this@MainActivity, "تم حفظ الصورة في Pictures/SGICamera", Toast.LENGTH_SHORT).show() }
                result.savedUri?.let { applyColorProfile(it) }
            }
            override fun onError(exception: ImageCaptureException) { runOnUiThread { Toast.makeText(this@MainActivity, "فشل التصوير: ${exception.message}", Toast.LENGTH_LONG).show() } }
        })
    }

    // Approximate color profiles only; this does not reproduce Samsung/Google/Apple proprietary pipelines.
    private fun applyColorProfile(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(input); input.close(); if (bitmap == null) return
            val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val matrix = when (mode) {
                CameraMode.SAMSUNG -> ColorMatrix(floatArrayOf(1.12f,0f,0f,0f,3f, 0f,1.10f,0f,0f,2f, 0f,0f,1.16f,0f,1f, 0f,0f,0f,1f,0f))
                CameraMode.PIXEL -> ColorMatrix(floatArrayOf(1.02f,0f,0f,0f,1f, 0f,1.02f,0f,0f,1f, 0f,0f,1.02f,0f,1f, 0f,0f,0f,1f,0f))
                CameraMode.IPHONE -> ColorMatrix(floatArrayOf(1.04f,0f,0f,0f,3f, 0f,1.02f,0f,0f,1f, 0f,0f,0.98f,0f,0f, 0f,0f,0f,1f,0f))
            }
            paint.colorFilter = ColorMatrixColorFilter(matrix); canvas.drawBitmap(bitmap, 0f, 0f, paint)
            contentResolver.openOutputStream(uri, "wt")?.use { outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle(); outputBitmap.recycle()
        } catch (_: Exception) { }
    }

    private fun modeName() = when (mode) { CameraMode.SAMSUNG -> "S"; CameraMode.PIXEL -> "G"; CameraMode.IPHONE -> "iPhone" }
    private fun showSettings() {
        val options = arrayOf("معلومات التطبيق", "معالجة الألوان تقريبية", "التقاط بأعلى جودة متاحة")
        android.app.AlertDialog.Builder(this).setTitle("SGI Camera V4").setItems(options) { _, which ->
            val message = when (which) { 0 -> "تطبيق كاميرا مستقل باستخدام CameraX."; 1 -> "لا يمكن استنساخ معالجة Google Pixel أو iPhone الأصلية على جهاز سامسونج؛ الأوضاع هنا ملفات ألوان تقريبية."; else -> "يستخدم التطبيق وضع تعظيم جودة الالتقاط الذي تدعمه الكاميرا." }
            android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("تمام", null).show()
        }.setNegativeButton("إغلاق", null).show()
    }
    private fun openGallery() { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) } catch (_: Exception) { Toast.makeText(this, "افتح المعرض من الهاتف", Toast.LENGTH_SHORT).show() } }
    override fun onDestroy() { super.onDestroy(); if (::cameraExecutor.isInitialized) cameraExecutor.shutdown() }
}
