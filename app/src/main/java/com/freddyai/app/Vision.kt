package com.freddyai.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Рамка найденного объекта; координаты центра и размеры в долях кадра. */
class Mark(val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Локальное зрение: предметы (TensorFlow Lite, COCO SSD MobileNet) или лица (ML Kit).
 * Кадры не покидают телефон; в облако уходит один кадр и только по прямой просьбе рассмотреть.
 */
object Vision {
    var active by mutableStateOf(false)
    var description by mutableStateOf("")
    var marks by mutableStateOf<List<Mark>>(emptyList())
    var error by mutableStateOf("")
    private val main = Handler(Looper.getMainLooper())
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var faces: FaceDetector? = null
    @Volatile private var busy = false
    @Volatile private var lastRun = 0L
    @Volatile private var frame: Bitmap? = null
    private var stable = ""
    private var candidate = ""
    private var candidateHits = 0
    private var lastComment = 0L

    private val DETAIL = Regex("(?iu)(кто|что\\s+(?:это|за|перед)|узна[йёе]|назови|определи|изображ|картин|фото|видишь|видно|видеть|вижу|камер|посмотр|смотри|перед тоб|в кадре|коспле|костюм|персонаж|человек|лицо|выгляжу|увидел|look|see|cosplay)")
    fun detailRequested(q: String) = DETAIL.containsMatchIn(q)

    fun stop() {
        active = false; marks = emptyList(); description = ""
        stable = ""; candidate = ""; candidateHits = 0
        frame = null
    }

    /** Вызывается из потока анализа CameraX; обрабатывает не чаще двух кадров в секунду. */
    fun analyze(image: ImageProxy) {
        try {
            if (busy || SystemClock.elapsedRealtime() - lastRun < 500) return
            val raw = image.toBitmap()
            val deg = image.imageInfo.rotationDegrees
            analyzeBitmap(if (deg == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg.toFloat()) }, true))
        } catch (e: Throwable) {
            main.post { error = "Ошибка локального зрения." }
        } finally { image.close() }
    }

    /** Кадр с любого источника (камера телефона или сетевая камера). Вызывать не с главного потока. */
    fun analyzeBitmap(bmp: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastRun < 500) return
        lastRun = now; busy = true
        try {
            frame = bmp
            if (S.vision.detector == "faces") detectFaces(bmp) else { publish(detectObjects(bmp), ""); busy = false }
        } catch (e: Throwable) {
            busy = false
            main.post { error = "Ошибка локального зрения." }
        }
    }

    private fun detectObjects(bitmap: Bitmap): List<Mark> {
        val net = interpreter ?: run {
            val model = S.ctx.assets.open("vision/detect.tflite").use { it.readBytes() }
            labels = S.ctx.assets.open("vision/labelmap.txt").use { String(it.readBytes()).split(Regex("\\r?\\n")) }
            val buffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply { put(model); rewind() }
            Interpreter(buffer, Interpreter.Options().setNumThreads(2)).also { interpreter = it }
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, 300, 300, true)
        val pixels = IntArray(300 * 300); scaled.getPixels(pixels, 0, 300, 0, 0, 300, 300)
        val input = ByteBuffer.allocateDirect(300 * 300 * 3).order(ByteOrder.nativeOrder())
        for (p in pixels) { input.put((p shr 16).toByte()); input.put((p shr 8).toByte()); input.put(p.toByte()) }
        input.rewind()
        if (scaled !== bitmap) scaled.recycle()
        val boxes = Array(1) { Array(10) { FloatArray(4) } }; val classes = Array(1) { FloatArray(10) }; val scores = Array(1) { FloatArray(10) }; val count = FloatArray(1)
        net.runForMultipleInputsOutputs(arrayOf<Any>(input), hashMapOf<Int, Any>(0 to boxes, 1 to classes, 2 to scores, 3 to count))
        val out = ArrayList<Mark>()
        for (i in 0 until minOf(10, count[0].toInt())) {
            if (scores[0][i] < 0.55f) continue
            val b = boxes[0][i]; val label = classes[0][i].toInt() + 1
            if (label >= labels.size || labels[label] == "???") continue
            out += Mark(labels[label], (b[1] + b[3]) / 2, (b[0] + b[2]) / 2, b[3] - b[1], b[2] - b[0])
        }
        return out
    }

    private fun detectFaces(bitmap: Bitmap) {
        val d = faces ?: FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).build()).also { faces = it }
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        d.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { list ->
            val smiling = list.count { (it.smilingProbability ?: 0f) > 0.6f }
            publish(list.map { f -> val r = f.boundingBox; Mark("лицо", r.exactCenterX() / w, r.exactCenterY() / h, r.width() / w, r.height() / h) },
                if (smiling > 0) " Улыбаются: $smiling." else "")
        }.addOnFailureListener { main.post { error = "Ошибка локального зрения." } }.addOnCompleteListener { busy = false }
    }

    private fun publish(found: List<Mark>, extra: String) = main.post {
        if (!S.vision.on) return@post
        active = true; error = ""; marks = found
        val faceMode = S.vision.detector == "faces"
        val key = if (faceMode) "лиц: ${found.size}" else found.map { it.label }.sorted().joinToString(", ")
        description = if (faceMode) "Локальная камера: обнаружено лиц: ${found.size}.$extra Личность людей не определена."
        else if (found.isEmpty()) "Локальный детектор: известные предметы не обнаружены."
        else "Локальный детектор предполагает: $key. Названия классов на английском; это вероятные категории, не личности людей."
        // Событием считается состав кадра, продержавшийся три анализа подряд, — так дрожание детектора не засоряет журнал.
        if (key == candidate) candidateHits++ else { candidate = key; candidateHits = 1 }
        if (candidateHits == 3 && key != stable) {
            val before = stable; stable = key
            if (key.isNotEmpty() && !(faceMode && found.isEmpty())) S.note(if (faceMode) "В кадре $key" else "В кадре: $key")
            val now = SystemClock.elapsedRealtime()
            if (S.vision.comment && before != key && !Ears.listening && now - lastComment > S.vision.every.coerceAtLeast(10) * 1000L) {
                if (Brain.cameraEvent(before.ifBlank { "наблюдения не было" }, key.ifBlank { "ничего знакомого" })) lastComment = now
            }
        }
    }

    private fun jpeg(): ByteArray? {
        val b = frame ?: return null
        val scale = 1024f / maxOf(b.width, b.height)
        val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    /** Подробный разбор свежего кадра облачной моделью. Вызывается только по просьбе «посмотри», «что это» и т. п. */
    suspend fun describe(question: String): String {
        val bytes = withContext(Dispatchers.Default) { jpeg() } ?: throw ApiError(0, "Нет кадра с камеры.")
        val text = Llm.vision(bytes,
            "Ты анализируешь отдельный свежий кадр для робота. Кратко по-русски, до 80 слов: видимые предметы, одежда, поза, действия, читаемый текст. " +
                "Рассмотри игрушки, рисунки и костюмы: цвет, шляпу, бант, уши, глаза. Вымышленного персонажа называй только при конкретных отличительных признаках и как предположение. " +
                "Не определяй личность реальных людей. Вопрос пользователя: $question")
        S.note("Подробно: $text")
        return text
    }
}

/** Источник кадров по настройке: камера телефона или сетевая камера (адрес JPEG-снимка). */
@Composable
fun CameraFeed(modifier: Modifier = Modifier) {
    if (S.vision.source == "net") NetCameraView(modifier) else CameraView(modifier)
}

/** Сетевая камера: ESP32-CAM (/capture), IP Webcam (/shot.jpg) и любые другие, отдающие JPEG по адресу. */
@Composable
fun NetCameraView(modifier: Modifier = Modifier) {
    val url = S.vision.url.trim()
    var shot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    DisposableEffect(url) {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            var fails = 0
            while (true) {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                            if (!r.isSuccessful) null else android.graphics.BitmapFactory.decodeStream(r.body!!.byteStream())
                        }
                    }.getOrNull()
                }
                if (bmp != null) {
                    fails = 0; shot = bmp.asImageBitmap(); Vision.active = true; Vision.error = ""
                    withContext(Dispatchers.Default) { Vision.analyzeBitmap(bmp) }
                } else if (++fails >= 3) Vision.error = "Нет кадра по сети. Проверь адрес камеры."
                kotlinx.coroutines.delay(if (bmp != null) 400 else 2000)
            }
        }
        onDispose { scope.cancel(); Vision.stop() }
    }
    Box(modifier) {
        shot?.let { androidx.compose.foundation.Image(it, "Сетевая камера", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        val marks = Vision.marks
        Canvas(Modifier.fillMaxSize()) {
            for (m in marks) drawRect(C.teal, Offset((m.x - m.w / 2) * size.width, (m.y - m.h / 2) * size.height), Size(m.w * size.width, m.h * size.height), style = Stroke(3f))
        }
    }
}

/** Окно камеры с рамками найденных объектов. Камера работает, только пока это окно на экране. */
@Composable
fun CameraView(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val front = S.vision.front
    DisposableEffect(front) {
        val future = ProcessCameraProvider.getInstance(ctx)
        val exec = Executors.newSingleThreadExecutor()
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build().also { it.setAnalyzer(exec) { img -> Vision.analyze(img) } }
                provider.unbindAll()
                provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Vision.active = true; Vision.error = ""
            } catch (e: Exception) { Vision.error = "Камера не найдена." }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            exec.shutdown()
            Vision.stop()
        }
    }
    Box(modifier) {
        AndroidView({ view }, Modifier.fillMaxSize())
        val marks = Vision.marks
        Canvas(Modifier.fillMaxSize()) {
            for (m in marks) {
                val cx = (if (front) 1f - m.x else m.x) * size.width
                drawRect(C.teal, Offset(cx - m.w * size.width / 2, (m.y - m.h / 2) * size.height), Size(m.w * size.width, m.h * size.height), style = Stroke(3f))
            }
        }
    }
}
