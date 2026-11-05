package uz.sanjarmurod.fitroomai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: GarmentOverlay
    private lateinit var status: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: PoseDetector
    private var provider: ProcessCameraProvider? = null
    private var front = true
    private var analyzing = false
    private var garment: Bitmap? = null
    private var color = Color.rgb(48, 105, 180)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status.text = "Kamera ruxsati berilmadi."
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadGarment(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 12, 18)
        window.navigationBarColor = Color.rgb(10, 12, 18)
        cameraExecutor = Executors.newSingleThreadExecutor()
        detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        buildUi()
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? Uri)?.let { loadGarment(it) }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(Color.rgb(10, 12, 18))
        }
        root.addView(TextView(this).apply {
            text = "FITROOM  /  AI FREE"; textSize = 17f; letterSpacing = .14f
            setTextColor(Color.rgb(231, 195, 126)); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Kiyimni jonli kiyib ko‘ring"; textSize = 27f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Bepul AR sinov. Kamera tasviri qurilmada qayta ishlanadi."
            textSize = 12f; setTextColor(Color.LTGRAY)
        })
        val cameraFrame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(24, 29, 39)); clipToOutline = true }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = GarmentOverlay(this)
        cameraFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(12) })
        status = TextView(this).apply {
            text = "Tayyor. Kamerani yoqing va tanani beldan yuqori ko‘rsating."
            textSize = 12f; setTextColor(Color.rgb(190, 195, 204))
        }
        root.addView(status)
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Qora" to Color.DKGRAY, "Ko‘k" to Color.rgb(45, 102, 185), "Qizil" to Color.rgb(184, 58, 68), "Yashil" to Color.rgb(46, 130, 91), "Oq" to Color.rgb(228, 228, 220)).forEach { (name, value) ->
            colors.addView(Button(this).apply {
                text = name; textSize = 10f
                setOnClickListener { color = value; overlay.setColor(value); status.text = "$name rang tanlandi." }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(colors)
        root.addView(Button(this).apply {
            text = "KIYIM PNG TANLASH"; setOnClickListener { imagePicker.launch("image/*") }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "KAMERANI YOQISH"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(Button(this).apply {
            text = "KAMERANI ALMASHTIRISH"; setOnClickListener { front = !front; startCamera() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(actions)
        root.addView(Button(this).apply {
            text = "TO‘XTATISH"; setOnClickListener { provider?.unbindAll(); status.text = "Kamera to‘xtatildi." }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(TextView(this).apply {
            text = "Eslatma: bu bepul AR sinov versiyasi. Kiyim grafik qatlam sifatida joylanadi; mato bukilishi va fotorealistik generativ almashtirish mavjud emas. Shaffof fonli kiyim PNG tavsiya qilinadi."
            textSize = 11f; setTextColor(Color.rgb(145, 151, 163)); setPadding(0, dp(8), 0, 0)
        })
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (analyzing) { proxy.close(); return@setAnalyzer }
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    analyzing = true
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector.process(image)
                        .addOnSuccessListener { pose ->
                            overlay.updatePose(pose, proxy.width, proxy.height)
                            runOnUiThread { status.text = if (pose.allPoseLandmarks.isEmpty()) "Tanani kadrga to‘liq kiriting." else "Tana aniqlandi · AR faol" }
                        }
                        .addOnFailureListener { runOnUiThread { status.text = "Tana aniqlashda xatolik." } }
                        .addOnCompleteListener { analyzing = false; proxy.close() }
                }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider?.unbindAll()
                provider?.bindToLifecycle(this, selector, p, analysis)
                status.text = "Kamera ishga tushdi."
            } catch (e: Exception) { status.text = "Kamerani ishga tushirib bo‘lmadi: ${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadGarment(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { stream -> garment = BitmapFactory.decodeStream(stream) }
            overlay.setGarment(garment); status.text = "Kiyim rasmi yuklandi. Endi kamerani yoqing."
        } catch (_: Exception) { status.text = "Rasmni o‘qib bo‘lmadi." }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    override fun onDestroy() { super.onDestroy(); provider?.unbindAll(); detector.close(); cameraExecutor.shutdown() }

    private class GarmentOverlay(ctx: android.content.Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var pose: Pose? = null
        private var sourceWidth = 1
        private var sourceHeight = 1
        private var garment: Bitmap? = null
        private var tint = Color.rgb(48, 105, 180)
        fun setColor(c: Int) { tint = c; invalidate() }
        fun setGarment(b: Bitmap?) { garment = b; invalidate() }
        fun updatePose(p: Pose, w: Int, h: Int) { pose = p; sourceWidth = w.coerceAtLeast(1); sourceHeight = h.coerceAtLeast(1); postInvalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val p = pose ?: return
            val l = p.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) ?: return
            val r = p.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER) ?: return
            val lh = p.getPoseLandmark(PoseLandmark.LEFT_HIP) ?: return
            val rh = p.getPoseLandmark(PoseLandmark.RIGHT_HIP) ?: return
            val sx = width.toFloat() / sourceWidth
            val sy = height.toFloat() / sourceHeight
            fun x(v: Float) = v * sx
            fun y(v: Float) = v * sy
            val sl = PointF(x(l.position.x), y(l.position.y))
            val sr = PointF(x(r.position.x), y(r.position.y))
            val hl = PointF(x(lh.position.x), y(lh.position.y))
            val hr = PointF(x(rh.position.x), y(rh.position.y))
            val body = Path().apply { moveTo(sl.x, sl.y); lineTo(sr.x, sr.y); lineTo(hr.x, hr.y); lineTo(hl.x, hl.y); close() }
            val bitmap = garment
            if (bitmap != null) {
                val extra = abs(sr.y - sl.y) * .25f
                val dst = RectF(min(sl.x, sr.x) - extra, min(sl.y, sr.y), max(sl.x, sr.x) + extra, max(hl.y, hr.y))
                canvas.save(); canvas.clipPath(body); paint.color = Color.WHITE
                canvas.drawBitmap(bitmap, null, dst, paint); canvas.restore()
            } else {
                paint.color = tint; paint.style = Paint.Style.FILL; canvas.drawPath(body, paint)
                paint.color = Color.argb(65, 255, 255, 255); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                canvas.drawPath(body, paint); paint.style = Paint.Style.FILL
            }
        }
    }
}
