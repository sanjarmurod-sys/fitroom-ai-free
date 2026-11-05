package uz.sanjarmurod.fitroomai
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
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
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.*
class MainActivity : ComponentActivity() {
 private lateinit var preview: PreviewView
 private lateinit var overlay: GarmentOverlay
 private lateinit var status: TextView
 private val executor = Executors.newSingleThreadExecutor()
 private lateinit var detector: PoseDetector
 private var provider: ProcessCameraProvider? = null
 private var front = true
 private var analyzing = false
 private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else status.text = "Kamera ruxsati berilmadi" }
 private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::loadGarment) }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor = Color.rgb(10,12,18); window.navigationBarColor = Color.rgb(10,12,18)
  detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
  buildUi()
  if (intent?.action == android.content.Intent.ACTION_SEND) {
   @Suppress("DEPRECATION")
   (intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? Uri)?.let(::loadGarment)
  }
 }
 private fun buildUi() {
  val root = LinearLayout(this).apply { orientation=1; setPadding(dp(16),dp(12),dp(16),dp(12)); setBackgroundColor(Color.rgb(10,12,18)) }
  root.addView(label("FITROOM AI FREE",22f,Color.rgb(231,195,126),true))
  root.addView(label("Kiyimni jonli kiyib ko‘ring",25f,Color.WHITE,true))
  root.addView(label("Bepul AR sinov. Kamera tasviri qurilmada qayta ishlanadi.",12f,Color.LTGRAY,false))
  val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(24,29,39)) }
  preview = PreviewView(this).apply { scaleType=PreviewView.ScaleType.FILL_CENTER; implementationMode=PreviewView.ImplementationMode.COMPATIBLE }
  overlay = GarmentOverlay(this)
  frame.addView(preview); frame.addView(overlay)
  root.addView(frame,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(12); bottomMargin=dp(10) })
  status = label("Tayyor. Kamerani yoqing va tanangizni kadrga kiriting.",12f,Color.LTGRAY,false); root.addView(status)
  val colors = LinearLayout(this).apply { orientation=0 }
  listOf("Qora" to Color.DKGRAY,"Ko‘k" to Color.rgb(45,102,185),"Qizil" to Color.rgb(184,58,68),"Yashil" to Color.rgb(46,130,91),"Oq" to Color.rgb(228,228,220)).forEach { (name,c) ->
   colors.addView(Button(this).apply { text=name; textSize=10f; setOnClickListener { overlay.changeTint(c); status.text="$name rang tanlandi" } },LinearLayout.LayoutParams(0,dp(44),1f))
  }
  root.addView(colors)
  root.addView(Button(this).apply { text="KIYIM PNG TANLASH"; setOnClickListener { picker.launch("image/*") } },LinearLayout.LayoutParams(-1,dp(48)))
  val actions=LinearLayout(this).apply { orientation=0 }
  actions.addView(Button(this).apply { text="KAMERANI YOQISH"; setOnClickListener { if(ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera() else permission.launch(Manifest.permission.CAMERA) } },LinearLayout.LayoutParams(0,dp(50),1f))
  actions.addView(Button(this).apply { text="OLD / ORQA"; setOnClickListener { front=!front; startCamera() } },LinearLayout.LayoutParams(0,dp(50),1f))
  root.addView(actions)
  root.addView(Button(this).apply { text="TO‘XTATISH"; setOnClickListener { provider?.unbindAll(); status.text="Kamera to‘xtatildi" } },LinearLayout.LayoutParams(-1,dp(44)))
  root.addView(label("Bu AR prototip: kiyim grafik qatlam sifatida joylanadi. Fotorealistik generativ almashtirish va mato fizikasi mavjud emas. Shaffof PNG tavsiya qilinadi.",11f,Color.LTGRAY,false))
  setContentView(root)
 }
 private fun startCamera() {
  val future=ProcessCameraProvider.getInstance(this)
  future.addListener({
   try {
    provider=future.get()
    val p=Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
    val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    analysis.setAnalyzer(executor) { proxy ->
     if(analyzing){proxy.close();return@setAnalyzer}
     val media=proxy.image
     if(media==null){proxy.close();return@setAnalyzer}
     analyzing=true
     detector.process(InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees))
      .addOnSuccessListener { pose -> overlay.updatePose(pose,proxy.width,proxy.height); runOnUiThread { status.text=if(pose.allPoseLandmarks.isEmpty()) "Tanani kadrga to‘liq kiriting" else "Tana aniqlandi · AR faol" } }
      .addOnFailureListener { runOnUiThread { status.text="Tana aniqlashda xatolik" } }
      .addOnCompleteListener { analyzing=false; proxy.close() }
    }
    provider?.unbindAll()
    provider?.bindToLifecycle(this,if(front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,p,analysis)
    status.text="Kamera ishga tushdi"
   } catch(e:Exception) { status.text="Kamera xatosi: ${e.localizedMessage}" }
  },ContextCompat.getMainExecutor(this))
 }
 private fun loadGarment(uri:Uri) { try { contentResolver.openInputStream(uri).use { overlay.setGarment(BitmapFactory.decodeStream(it)) }; status.text="Kiyim rasmi tanlandi" } catch(_:Exception) { status.text="Rasmni ochib bo‘lmadi" } }
 private fun label(t:String,s:Float,c:Int,b:Boolean)=TextView(this).apply { text=t; textSize=s; setTextColor(c); if(b) typeface=Typeface.DEFAULT_BOLD; setPadding(0,dp(6),0,dp(6)) }
 private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
 override fun onDestroy(){ super.onDestroy(); provider?.unbindAll(); detector.close(); executor.shutdown() }
 private class GarmentOverlay(ctx:android.content.Context):View(ctx){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private var pose:Pose?=null; private var sw=1; private var sh=1; private var bitmap:Bitmap?=null; private var tint=Color.rgb(48,105,180)
  fun changeTint(c:Int){tint=c;invalidate()}
  fun setGarment(b:Bitmap?){bitmap=b;invalidate()}
  fun updatePose(p:Pose,w:Int,h:Int){pose=p;sw=w.coerceAtLeast(1);sh=h.coerceAtLeast(1);postInvalidate()}
  override fun onDraw(canvas:Canvas){
   super.onDraw(canvas)
   val p=pose?:return
   val l=p.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)?:return
   val r=p.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)?:return
   val lh=p.getPoseLandmark(PoseLandmark.LEFT_HIP)?:return
   val rh=p.getPoseLandmark(PoseLandmark.RIGHT_HIP)?:return
   val sx=width.toFloat()/sw; val sy=height.toFloat()/sh
   fun x(v:Float)=v*sx
   fun y(v:Float)=v*sy
   val sl=PointF(x(l.position.x),y(l.position.y)); val sr=PointF(x(r.position.x),y(r.position.y))
   val hl=PointF(x(lh.position.x),y(lh.position.y)); val hr=PointF(x(rh.position.x),y(rh.position.y))
   val body=Path().apply { moveTo(sl.x,sl.y); lineTo(sr.x,sr.y); lineTo(hr.x,hr.y); lineTo(hl.x,hl.y); close() }
   val bmp=bitmap
   if(bmp!=null){
    val ext=abs(sr.y-sl.y)*.25f
    val dst=RectF(min(sl.x,sr.x)-ext,min(sl.y,sr.y),max(sl.x,sr.x)+ext,max(hl.y,hr.y))
    canvas.save(); canvas.clipPath(body); paint.color=Color.WHITE; canvas.drawBitmap(bmp,null,dst,paint); canvas.restore()
   }else{
    paint.color=tint; paint.style=Paint.Style.FILL; canvas.drawPath(body,paint)
    paint.color=Color.argb(80,255,255,255); paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; canvas.drawPath(body,paint); paint.style=Paint.Style.FILL
   }
  }
 }
}
