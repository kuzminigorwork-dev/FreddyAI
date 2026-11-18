package com.freddyai.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Пиксельная голова Фредди из оригинала: атлас 180×315, кадры 45×45, по паре кадров
 * (рот закрыт / открыт) на каждую эмоцию, отдельные кадры моргания и «думает».
 * Белки глаз перекрашиваются в цвет эмоции, как шейдер FreddyEyes в Unity-версии.
 */
object HeadArt {
    class Frame(val x: Int, val y: Int, val w: Int, val h: Int)

    var version by mutableIntStateOf(0)
        private set
    var atlas: ImageBitmap? = null
        private set
    var mask: ImageBitmap? = null
        private set
    /** true — своя картинка одним кадром, а не атлас эмоций. */
    var single = false
        private set
    var frames: Map<String, List<Frame>> = emptyMap()
        private set
    var custom by mutableStateOf(false)
        private set

    val customFile: File get() = File(S.ctx.filesDir, "freddy-custom.png")

    fun load() {
        if (frames.isEmpty()) frames = runCatching {
            val o = JSONObject(S.ctx.assets.open("head/animations.json").use { String(it.readBytes(), Charsets.UTF_8) })
            o.keys().asSequence().associate { k ->
                val a = o.getJSONArray(k)
                k.replace('ё', 'е') to (0 until a.length()).map { a.getJSONObject(it).let { f -> Frame(f.getInt("x"), f.getInt("y"), f.getInt("w"), f.getInt("h")) } }
            }
        }.getOrDefault(emptyMap())
        val own = customFile.takeIf { it.exists() }?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() }
            ?.takeIf { it.width in 8..2048 && it.height in 8..2048 }
        custom = own != null
        val bmp = (own ?: S.ctx.assets.open("head/freddy.png").use { BitmapFactory.decodeStream(it) }).copy(Bitmap.Config.ARGB_8888, false)
        single = bmp.width != 180 || bmp.height != 315
        atlas = bmp.asImageBitmap()
        mask = buildMask(bmp, single).asImageBitmap()
        version++
    }

    /** Маска белков: почти белые пиксели в области глаз каждого кадра. */
    private fun buildMask(b: Bitmap, single: Boolean): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            val a = p ushr 24; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            val white = a > 0 && min(r, min(g, bl)) > 180 && max(r, max(g, bl)) - min(r, min(g, bl)) < 25
            val area = if (single) x >= w * 0.25f && x <= w * 0.75f && y >= h * 0.30f && y <= h * 0.62f
            else (y % 45) in 15..26 && (x % 45) in 12..32
            if (white && area) out[y * w + x] = -1
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun frame(key: String, i: Int): Frame? {
        val seq = frames[key.replace('ё', 'е')] ?: frames["нормальный"] ?: return null
        return seq[i.coerceIn(0, seq.size - 1)]
    }

    /** Сохраняет свою картинку головы. Возвращает текст ошибки или пустую строку. */
    fun importPng(bytes: ByteArray): String {
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "Это не картинка."
        if (b.width > 2048 || b.height > 2048) return "Картинка должна быть не больше 2048 × 2048."
        customFile.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        load(); return ""
    }

    fun reset() { customFile.delete(); load() }

    fun stockPng(): ByteArray = S.ctx.assets.open("head/freddy.png").use { it.readBytes() }
}

/** Нос: тык — гудок и покачивание головы; серия из трёх и больше — Фредди реагирует на спам. */
object Nose {
    var tapped by mutableLongStateOf(0L)
        private set
    private var count = 0
    private var player: MediaPlayer? = null
    private val scope = MainScope()
    private var settle: kotlinx.coroutines.Job? = null

    fun tap() {
        val now = SystemClock.uptimeMillis()
        count = if (now - tapped < 1200) count + 1 else 1
        tapped = now
        runCatching {
            player?.release()
            player = MediaPlayer.create(S.ctx, R.raw.honk)?.apply {
                val v = S.fish.volume.coerceIn(0f, 1f); setVolume(v, v)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                start()
            }
        }
        settle?.cancel()
        settle = scope.launch {
            delay(1300)
            val taps = count; count = 0
            if (taps >= 3)
                Brain.event("Событие: пользователь много раз подряд тыкал тебе в нос. Коротко отреагируй на этот спам в характере.")
        }
    }
}

/**
 * Голова Фредди. Рот двигается по громкости речи, голова слегка покачивается при разговоре,
 * моргает раз в 10–15 секунд, при раздумьях показывает кадры «думает». Тык в нос — гудок.
 */
@Composable
fun FreddyHead(modifier: Modifier = Modifier, interactive: Boolean = true) {
    val v = HeadArt.version
    val atlas = HeadArt.atlas
    val mask = HeadArt.mask
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var nextBlink by remember { mutableLongStateOf(SystemClock.uptimeMillis() + 4000) }
    var blinkUntil by remember { mutableLongStateOf(0L) }
    var sway by remember { mutableFloatStateOf(0f) }
    var mouthLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(v) {
        var last = SystemClock.uptimeMillis()
        while (true) {
            withFrameMillis { }
            val t = SystemClock.uptimeMillis(); val dt = (t - last).coerceIn(1, 100) / 1000f; last = t
            now = t
            if (t >= nextBlink) { blinkUntil = t + (180..320).random(); nextBlink = blinkUntil + (10_000..15_000).random() }
            val level = Speaker.level()
            mouthLevel = level
            val target = if (level > 0.08f) sin(t / 1000f * 5.5f) * 2.2f else 0f
            sway += (target - sway) * (1 - exp(-dt * 8f))
        }
    }
    if (atlas == null || mask == null) return
    val thinking = Brain.busy && !Speaker.speaking
    val listening = Ears.listening
    val blinking = now < blinkUntil
    val emotion = Brain.emotion
    val music = MusicBox.playing
    val frame = when {
        HeadArt.single -> null
        thinking && !blinking -> HeadArt.frame("думает", ((now / 500) % 4).toInt())
        else -> HeadArt.frame(if (blinking) "$emotion (моргающий)" else emotion, if (mouthLevel > 0.12f) 1 else 0)
    }
    val eye = when {
        listening -> C.teal
        music -> Color(0xFFE8F1FF)
        else -> eyeColor(emotion)
    }
    // Яркость белков: в покое приглушены, при речи и музыке разгораются (как _EyeBrightness в оригинале).
    val glow = when {
        music -> 0.45f + 0.55f * MusicBox.level()
        Speaker.speaking -> 0.7f + 0.3f * mouthLevel.coerceAtMost(1f)
        listening || thinking -> 0.75f + 0.25f * ((sin(now / 260.0) + 1) / 2).toFloat()
        else -> 0.95f
    }
    val tint = Color(eye.red * glow, eye.green * glow, eye.blue * glow)
    val noseAge = (now - Nose.tapped) / 1000f
    val wobble = if (noseAge in 0f..0.7f) sin(noseAge * 28f) * 10f * exp(-noseAge * 6f) else 0f

    val tap = if (interactive) Modifier.pointerInput(v) {
        detectTapGestures { p ->
            val side = min(size.width, size.height).toFloat()
            val nx = (p.x - (size.width - side) / 2) / side; val ny = (p.y - (size.height - side) / 2) / side
            if (nx in 0.37f..0.63f && ny in 0.44f..0.68f) Nose.tap()
        }
    } else Modifier
    Canvas(modifier.then(tap)) {
        val srcOff = if (frame == null) IntOffset.Zero else IntOffset(frame.x, frame.y)
        val srcSize = if (frame == null) IntSize(atlas.width, atlas.height) else IntSize(frame.w, frame.h)
        // Целый масштаб сохраняет ровные пиксели; для маленьких размеров допускаем дробный.
        val fit = min(size.width / srcSize.width, size.height / srcSize.height)
        val scale = if (fit >= 3f) floor(fit) else fit
        val dw = srcSize.width * scale; val dh = srcSize.height * scale
        val dst = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt())
        val dstSize = IntSize(dw.toInt(), dh.toInt())
        rotate(sway + wobble, Offset(size.width / 2, dst.y + dh * 0.9f)) {
            if (!blinking && !HeadArt.single) {
                val r = 5f * scale
                for (ex in listOf(17.5f, 26.5f)) {
                    val c = Offset(dst.x + ex * scale, dst.y + 22f * scale)
                    drawCircle(Brush.radialGradient(listOf(eye.copy(alpha = 0.35f * glow), Color.Transparent), c, r), r, c)
                }
            }
            drawImage(atlas, srcOff, srcSize, dst, dstSize, filterQuality = FilterQuality.None)
            drawImage(mask, srcOff, srcSize, dst, dstSize, filterQuality = FilterQuality.None, colorFilter = ColorFilter.tint(tint, BlendMode.Modulate))
        }
    }
}

/** Музыкальная шкатулка из оригинала: мелодия минуту, глаза платы в режиме MUSIC. */
object MusicBox {
    var playing by mutableStateOf(false)
        private set
    private var player: MediaPlayer? = null
    private var meter: Meter? = null

    fun level(): Float = meter?.level ?: ((sin(SystemClock.uptimeMillis() / 180.0) + 1) / 2).toFloat()

    fun toggle() = if (playing) stop() else play()

    fun play() {
        stop()
        Speaker.stop()
        Ears.cancel(); Wake.stop()
        val p = MediaPlayer.create(S.ctx, R.raw.musicbox) ?: return
        player = p
        val v = S.fish.volume.coerceIn(0f, 1f); p.setVolume(v, v)
        p.setOnCompletionListener { stop() }
        p.start()
        meter = Meter.attach(p.audioSessionId)
        playing = true
        Board.send("MUSIC")
    }

    fun stop() {
        meter?.release(); meter = null
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        if (playing) Board.restoreEyes()
        playing = false
    }
}
