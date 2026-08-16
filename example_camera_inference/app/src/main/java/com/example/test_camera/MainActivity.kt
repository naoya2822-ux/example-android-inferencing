つpackage com.example.test_camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test_camera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.app.ActivityCompat

data class InferenceResult(
    val classification: Map<String, Float>?,   // Classification labels and values
    val objectDetections: List<BoundingBox>?,  // Object detection results
    val visualAnomalyGridCells: List<BoundingBox>?, // Visual anomaly grid
    val anomalyResult: Map<String, Float>?, // Anomaly values
    val timing: Timing  // Timing information
)

data class BoundingBox(
    val label: String,
    val confidence: Float,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class Timing(
    val sampling: Int,
    val dsp: Int,
    val classification: Int,
    val anomaly: Int,
    val dsp_us: Long,
    val classification_us: Long,
    val anomaly_us: Long
)

private const val CAMERA_PERMISSION_REQUEST_CODE = 1001

class BoundingBoxOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }

    private val anomalyPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 60 // Adjust transparency
    }

    var boundingBoxes: List<BoundingBox> = emptyList()
        set(value) {
            field = value
            invalidate() // Redraw when new bounding boxes are set
        }

    @SuppressLint("DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT) // Ensure transparency

        boundingBoxes.forEach { box ->
            val rect = Rect(box.x, box.y, box.x + box.width, box.y + box.height)

            if (box.label == "anomaly") {
                // Fill the box with transparent red
                canvas.drawRect(rect, anomalyPaint)

                // Display anomaly score in the center
                val scoreText = String.format("%.2f", box.confidence)
                val textX = rect.centerX().toFloat()
                val textY = rect.centerY().toFloat()

                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(scoreText, textX, textY, textPaint)
            } else {
                // Standard object detection box
                canvas.drawRect(rect, paint)
                canvas.drawText("${box.label} (${(box.confidence * 100).toInt()}%)", box.x.toFloat(), (box.y - 10).toFloat(), textPaint)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var discardCount = 0
private var recycleCount = 0
private var totalCount = 0

private var lastDecisionTime = System.currentTimeMillis()
private var totalSortingTimeMs = 0L

private var lastLabel = ""
private var decisionLocked = false

    private lateinit var binding: ActivityMainBinding
    private lateinit var resultTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultTextView = findViewById(R.id.resultTextView) // Result TextView
        previewView = findViewById(R.id.previewView) // Camera preview view
        boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay) // overlay for bbxes / visual ad

        // Set overlay size to match PreviewView
        previewView.post {
            boundingBoxOverlay.layoutParams = boundingBoxOverlay.layoutParams.apply {
                width = previewView.width
                height = previewView.height
            }
        }

        val startScreen = findViewById<android.view.View>(R.id.startScreen)
val startCameraButton = findViewById<android.widget.Button>(R.id.startCameraButton)
val finishWorkButton = findViewById<android.widget.Button>(R.id.finishWorkButton)

startCameraButton.setOnClickListener {
    if (!hasCameraPermission()) {
        requestCameraPermission()
    } else {
        startScreen.visibility = android.view.View.GONE
        previewView.visibility = android.view.View.VISIBLE
        finishWorkButton.visibility = android.view.View.VISIBLE
        startCamera()
    }
}

finishWorkButton.setOnClickListener {
    val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
    val cameraProvider = cameraProviderFuture.get()
    cameraProvider.unbindAll()

    previewView.visibility = android.view.View.GONE
    boundingBoxOverlay.visibility = android.view.View.GONE
    resultTextView.visibility = android.view.View.GONE
    finishWorkButton.visibility = android.view.View.GONE
    startScreen.visibility = android.view.View.VISIBLE
}
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val imageAnalysis = ImageAnalysis.Builder().build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                findViewById<android.view.View>(R.id.startScreen).visibility = android.view.View.GONE
previewView.visibility = android.view.View.VISIBLE
findViewById<android.widget.Button>(R.id.finishWorkButton).visibility = android.view.View.VISIBLE
startCamera()
            } else {
                resultTextView.text = "Camera permission required!"
            }
        }
    }

    // Process the captured image
    private fun processImage(imageProxy: ImageProxy) {
        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()

val resizedBitmap = Bitmap.createScaledBitmap(
    bitmap,
    480,
    640,
    true
)

val byteArray = getByteArrayFromBitmap(resizedBitmap)

        // Close the imageProxy after processing
        imageProxy.close()

        // Pass to C++ for Edge Impulse inference
        lifecycleScope.launch(Dispatchers.IO) {
            val result = passToCpp(byteArray)
            runOnUiThread {
                displayResults(result)
            }
        }
    }

    // Convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap {
        val planes = this.planes
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // Convert Bitmap to ByteArray (RGB888 format)
    private fun getByteArrayFromBitmap(bitmap: Bitmap): ByteArray {

        // Rotate the bitmap by 90 degrees
        val matrix = Matrix()
        matrix.postRotate(90f)

        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val width = rotatedBitmap.width
        val height = rotatedBitmap.height

        val pixels = IntArray(width * height) // Holds ARGB pixels
        val rgbByteArray = ByteArray(width * height * 3) // Holds RGB888 data

        rotatedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert ARGB to RGB888
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            rgbByteArray[i * 3] = r.toByte()
            rgbByteArray[i * 3 + 1] = g.toByte()
            rgbByteArray[i * 3 + 2] = b.toByte()
        }

        return rgbByteArray
    }

    // Call the C++ function to process the image and return results
    private external fun passToCpp(imageData: ByteArray): InferenceResult?

    // Display results in UI
    @SuppressLint("SetTextI18n")
    private fun displayResults(result: InferenceResult?) {
        

    boundingBoxOverlay.visibility = View.GONE

    if (result == null) {
        resultTextView.visibility = View.VISIBLE
        resultTextView.text = "判定エラー"
        return
    }

    val bestResult = result.classification?.entries?.maxByOrNull { it.value }
        ?: return

    val label = bestResult.key
    val confidence = bestResult.value

    // 信頼度80%未満は無視
    if (confidence < 0.80f) {
        return
    }

    // リモコンが画面から消えたら、次の商品を判定できるようにする
    if (label == "リモコン以外") {
        decisionLocked = false
        lastLabel = label
        return
    }

    // 同じリモコンを置きっぱなしなら再カウントしない
    if (decisionLocked) {
        return
    }

    // 販売・捨てる以外はカウントしない
    if (label != "販売" && label != "捨てる") {
        return
    }

    val now = System.currentTimeMillis()
    val sortingTime = now - lastDecisionTime

    when (label) {
        "捨てる" -> discardCount++
        "販売" -> recycleCount++
    }

    totalCount++
    totalSortingTimeMs += sortingTime

    val seconds = sortingTime / 1000.0
    val averageSeconds =
        (totalSortingTimeMs / totalCount.toDouble()) / 1000.0

    lastLabel = label
    lastDecisionTime = now
    decisionLocked = true

    resultTextView.visibility = View.VISIBLE

    resultTextView.text =
        "\n判定：$label" +
        "\n信頼度：${(confidence * 100).toInt()}%" +
        "\n捨てる：${discardCount}個" +
        "\n販売：${recycleCount}個" +
        "\n合計：${totalCount}個" +
        "\n今回：${"%.1f".format(seconds)}秒" +
        "\n平均：${"%.1f".format(averageSeconds)}秒"

    // 判定結果を1.5秒表示
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        resultTextView.visibility = View.GONE
    }, 1500)
    }   

    // Load the native library
    init {
        System.loadLibrary("test_camera")
    }
}
