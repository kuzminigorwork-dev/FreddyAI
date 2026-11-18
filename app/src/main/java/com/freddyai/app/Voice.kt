package com.freddyai.app

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.sqrt

// ====================================================================== озвучка
/**
 * Очередь озвучки. Текст режется на предложения, каждое синтезируется заранее,
 * пока играет предыдущее, — поэтому Фредди начинает говорить, не дожидаясь конца ответа.
 * Готовые фрагменты лежат в кэше и повторно лимит не тратят.
 */
object Speaker {
    var speaking by mutableStateOf(false)
        private set
    var lastError by mutableStateOf("")
    private val scope = MainScope()
    private class Item(val gen: Int, val text: String, val audio: Deferred<File?>)
    private val queue = Channel<Item>(Channel.UNLIMITED)
    private var gen = 0
    private var pending = 0
    private val buf = StringBuilder()
    private var player: MediaPlayer? = null
    private var waiter: CancellableContinuation<Unit>? = null
    private var sys: TextToSpeech? = null
    private var sysReady = false
    private var meter: Meter? = null
    private var boost: android.media.audiofx.LoudnessEnhancer? = null
    /** Звук сейчас действительно играет (а не синтезируется). */
    private var audible = false

    /** Громкость речи 0..1 для движения рта. Без доступа к анализатору звука — правдоподобный ритм слогов. */
    fun level(): Float {
        if (!audible) return 0f
        meter?.let { return it.level }
        val t = android.os.SystemClock.uptimeMillis()
        return (((kotlin.math.sin(t / 70.0) * 0.6 + kotlin.math.sin(t / 23.0) * 0.4) + 1) / 2).toFloat()
    }

    private fun releaseFx() {
        meter?.release(); meter = null
        runCatching { boost?.release() }; boost = null
        audible = false
    }

    val dir: File get() = File(S.ctx.cacheDir, "tts").apply { mkdirs() }

    init {
        scope.launch {
            for (item in queue) {
                try {
                    if (item.gen == gen) {
                        val f = item.audio.await()
                        if (item.gen == gen) { if (f != null) play(f) else systemSpeak(Reply.stripTags(item.text)) }
                    } else item.audio.cancel()
                } catch (e: CancellationException) {
                    if (!isActive) throw e
                } catch (e: Exception) {
                    lastError = e.message.orEmpty()
                } finally {
                    pending--
                    if (pending <= 0) { pending = 0; speaking = false }
                }
            }
        }
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cacheFile(text: String): File {
        val c = S.fish
        return File(dir, sha("${c.model}|${S.profile.voiceId}|${c.speed}|$text") + ".mp3")
    }

    fun cacheSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun clearCache() { dir.listFiles()?.forEach { it.delete() } }

    private fun trimCache() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 150L * 1024 * 1024) break; total -= f.length(); f.delete() }
    }

    /** Возвращает файл со звуком или null, если Fish Audio недоступен (тогда говорит системный голос). */
    suspend fun synth(text: String): File? {
        val c = S.fish
        if (c.key.isBlank()) return null
        val f = cacheFile(text)
        if (c.cache && f.length() > 0) { S.count("tts.hit"); f.setLastModified(System.currentTimeMillis()); return f }
        return try {
            val bytes = Fish.tts(text, S.profile.voiceId)
            withContext(Dispatchers.IO) { f.writeBytes(bytes); trimCache() }
            lastError = ""
            f
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            lastError = errorText(e); S.count("tts.err"); null
        }
    }

    private fun prepare(text: String): String = (if (S.fish.tags) text else Reply.stripTags(text)).trim()

    private fun enqueue(raw: String) {
        val text = prepare(raw)
        if (Reply.stripTags(text).none { it.isLetterOrDigit() }) return
        pending++; speaking = true
        val g = gen
        queue.trySend(Item(g, text, scope.async { synth(text) }))
    }

    /** Озвучить готовый текст целиком. */
    fun say(text: String) {
        if (!S.fish.tts) return
        feed(text); flush()
    }

    /** Добавить кусок потокового ответа; законченные предложения сразу уходят в озвучку. */
    fun feed(delta: String) {
        if (!S.fish.tts) return
        buf.append(delta)
        while (true) {
            val cut = cutPoint(buf); if (cut <= 0) break
            enqueue(buf.substring(0, cut)); buf.delete(0, cut)
        }
    }

    fun flush() { if (buf.isNotBlank()) enqueue(buf.toString()); buf.setLength(0) }
    fun dropBuffer() { buf.setLength(0) }

    /** Те же фрагменты, на которые текст будет разрезан при озвучке, — чтобы кэш совпадал. */
    fun chunks(text: String): List<String> {
        val b = StringBuilder(text); val out = ArrayList<String>()
        while (true) { val c = cutPoint(b); if (c <= 0) break; out += b.substring(0, c); b.delete(0, c) }
        if (b.isNotBlank()) out += b.toString()
        return out.map { prepare(it) }.filter { t -> Reply.stripTags(t).any { it.isLetterOrDigit() } }
    }

    fun isCached(text: String): Boolean = chunks(text).let { c -> c.isNotEmpty() && c.all { cacheFile(it).length() > 0 } }
    suspend fun precache(text: String) { chunks(text).forEach { synth(it) } }

    private fun cutPoint(buf: StringBuilder): Int {
        val min = 40
        var depth = 0
        for (i in buf.indices) {
            val ch = buf[i]
            if (ch == '[') depth++ else if (ch == ']') depth = max(0, depth - 1)
            if (depth > 0 || i + 1 >= buf.length) continue
            if (i >= min && (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '\n') && buf[i + 1].isWhitespace()) return i + 1
            if (i >= 260 && ch == ' ') return i + 1
        }
        return 0
    }

    fun stop() {
        gen++
        buf.setLength(0)
        releaseFx()
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { sys?.stop() }
        waiter?.let { if (it.isActive) it.resume(Unit) }; waiter = null
    }

    private suspend fun play(f: File) = suspendCancellableCoroutine<Unit> { cont ->
        waiter = cont
        fun done() { releaseFx(); if (waiter === cont) waiter = null; if (cont.isActive) cont.resume(Unit) }
        try {
            val p = MediaPlayer()
            player = p
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { it.release(); if (player === it) player = null; done() }
            p.setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; f.delete(); done(); true }
            p.prepare()
            val v = S.fish.volume.coerceIn(0f, 2f)
            p.setVolume(v.coerceAtMost(1f), v.coerceAtMost(1f))
            // Громче 100% — через усилитель громкости системы.
            if (v > 1.01f) boost = runCatching {
                android.media.audiofx.LoudnessEnhancer(p.audioSessionId).apply { setTargetGain((2000 * kotlin.math.log10(v.toDouble())).toInt()); enabled = true }
            }.getOrNull()
            meter = Meter.attach(p.audioSessionId)
            audible = true
            p.start()
        } catch (e: Exception) {
            f.delete(); done()
        }
    }

    private suspend fun systemSpeak(text: String) {
        if (text.isBlank()) return
        if (sys == null) {
            suspendCancellableCoroutine<Unit> { cont ->
                sys = TextToSpeech(S.ctx) { status ->
                    sysReady = status == TextToSpeech.SUCCESS
                    if (sysReady) sys?.language = Locale("ru", "RU")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        val t = sys ?: return
        if (!sysReady) return
        suspendCancellableCoroutine<Unit> { cont ->
            waiter = cont
            val id = "u" + System.nanoTime()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { scope.launch { audible = true } }
                override fun onDone(utteranceId: String?) { scope.launch { audible = false; if (cont.isActive) cont.resume(Unit) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { scope.launch { audible = false; if (cont.isActive) cont.resume(Unit) } }
            })
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, S.fish.volume.coerceIn(0f, 1f)) }
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }
}

/** Прослушивание образца голоса из библиотеки по ссылке. */
object SamplePlayer {
    var playing by mutableStateOf("")
    private var mp: MediaPlayer? = null

    fun toggle(url: String) {
        val same = playing == url
        runCatching { mp?.release() }; mp = null; playing = ""
        if (same || url.isBlank()) return
        Speaker.stop()
        playing = url
        mp = MediaPlayer().apply {
            setOnPreparedListener { it.start() }
            setOnCompletionListener { if (playing == url) playing = "" }
            setOnErrorListener { _, _, _ -> if (playing == url) playing = ""; true }
            runCatching { setDataSource(url); prepareAsync() }.onFailure { playing = "" }
        }
    }
}

// ====================================================================== офлайн-модель Vosk
object Vosk {
    const val NAME = "vosk-model-small-ru-0.22"
    private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"
    var installed by mutableStateOf(false)
    var progress by mutableStateOf(-1) // -1 не идёт, 0..100 скачивание, 101 распаковка
    var error by mutableStateOf("")
    private var model: Model? = null
    private var job: Job? = null
    private val scope = MainScope()
    private val root: File get() = File(S.ctx.filesDir, "vosk")
    private val dirModel: File get() = File(root, NAME)

    fun refresh() { installed = File(dirModel, ".ok").exists() }

    fun install() {
        if (job?.isActive == true) return
        error = ""; progress = 0
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    root.mkdirs()
                    val zip = File(S.ctx.cacheDir, "vosk.zip")
                    http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                        if (!r.isSuccessful) throw ApiError(r.code, "Не удалось скачать модель (${r.code}).")
                        val total = r.body!!.contentLength()
                        r.body!!.byteStream().use { input ->
                            zip.outputStream().use { out ->
                                val b = ByteArray(64 * 1024); var done = 0L
                                while (true) {
                                    if (!isActive) throw CancellationException()
                                    val n = input.read(b); if (n < 0) break
                                    out.write(b, 0, n); done += n
                                    if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 100)
                                }
                            }
                        }
                    }
                    progress = 101
                    dirModel.deleteRecursively()
                    val base = root.canonicalPath + File.separator
                    ZipInputStream(zip.inputStream().buffered()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val dst = File(root, e.name)
                            // Защита от записи за пределы папки модели.
                            if (!dst.canonicalPath.startsWith(base)) throw ApiError(0, "Архив модели повреждён.")
                            if (e.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); dst.outputStream().use { z.copyTo(it) } }
                        }
                    }
                    zip.delete()
                    File(dirModel, ".ok").writeText("ok")
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { dirModel.deleteRecursively() }
            } catch (e: Exception) {
                error = errorText(e)
                withContext(Dispatchers.IO) { dirModel.deleteRecursively() }
            } finally { progress = -1; refresh() }
        }
    }

    fun cancel() { job?.cancel() }

    fun remove() {
        model?.close(); model = null
        scope.launch { withContext(Dispatchers.IO) { dirModel.deleteRecursively() }; refresh() }
    }

    suspend fun load(): Model {
        model?.let { return it }
        if (!installed) throw ApiError(0, "Сначала скачай модель Vosk на вкладке «Голос».")
        return withContext(Dispatchers.IO) { Model(dirModel.absolutePath) }.also { model = it }
    }

    fun text(json: String?, key: String = "text"): String = runCatching { JSONObject(json ?: "{}").str(key) }.getOrDefault("").trim()
}

// ====================================================================== распознавание речи
/** Разовое прослушивание одной фразы тремя способами: системный распознаватель, Whisper, Vosk. */
object Ears {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
    var error by mutableStateOf("")
    /** Сколько раз подряд прослушивание закончилось тишиной — режим разговора по нему делает паузу. */
    var silentStreak by mutableStateOf(0)
    private val scope = MainScope()
    private var onText: (String) -> Unit = {}
    private var sr: SpeechRecognizer? = null
    private var job: Job? = null
    private var vosk: SpeechService? = null
    @Volatile private var stopFlag = false

    fun start(cb: (String) -> Unit) {
        if (listening) return
        onText = cb; error = ""; partial = ""; listening = true; stopFlag = false
        Speaker.stop()
        when (S.stt.mode) {
            "whisper" -> startWhisper()
            "vosk" -> startVosk()
            else -> startSystem()
        }
    }

    /** Остановить запись; уже сказанное будет распознано. */
    fun stop() {
        if (!listening) return
        when (S.stt.mode) {
            "whisper" -> stopFlag = true
            "vosk" -> { val p = partial; release(); finish(p) }
            else -> runCatching { sr?.stopListening() }
        }
    }

    fun cancel() { release(); job?.cancel(); listening = false; partial = "" }

    private fun release() {
        runCatching { sr?.destroy() }; sr = null
        runCatching { vosk?.stop(); vosk?.shutdown() }; vosk = null
    }

    private fun finish(text: String, err: String = "") {
        release()
        listening = false; partial = ""
        error = err
        val t = text.trim()
        silentStreak = if (t.isNotEmpty()) 0 else silentStreak + 1
        if (t.isNotEmpty()) onText(t)
    }

    // --- системный распознаватель Android
    private fun startSystem() {
        if (!SpeechRecognizer.isRecognitionAvailable(S.ctx)) { finish("", "На телефоне нет системного распознавания речи. Выбери Whisper или Vosk."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(S.ctx); sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) = finish(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            override fun onPartialResults(b: Bundle?) { partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
            override fun onError(code: Int) = finish("", when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Системному распознаванию нужен интернет."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
                else -> "Ошибка распознавания ($code)."
            })
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    // --- запись и Whisper
    @SuppressLint("MissingPermission")
    private fun startWhisper() {
        job = scope.launch {
            try {
                val (wav, seconds) = withContext(Dispatchers.IO) { record() }
                if (wav == null) { finish(""); return@launch }
                partial = "Распознаю…"
                finish(Llm.transcribe(wav, seconds))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { finish("", errorText(e)) }
        }
    }

    /** Пишет до 30 секунд; останавливается после секунды тишины вслед за речью. */
    @SuppressLint("MissingPermission")
    private fun record(): Pair<ByteArray?, Int> {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 6400))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw ApiError(0, "Микрофон занят или недоступен.") }
        val pcm = ByteArrayOutputStream(); val b = ShortArray(1600) // 100 мс
        var frames = 0; var spoke = false; var silent = 0; var floor = 0.0
        rec.startRecording()
        try {
            while (!stopFlag && frames < 300) {
                val n = rec.read(b, 0, b.size); if (n <= 0) break
                var sum = 0.0
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) { sum += b[i].toDouble() * b[i]; bytes.putShort(b[i]) }
                pcm.write(bytes.array())
                val rms = sqrt(sum / n); frames++
                if (frames <= 3) { floor = max(floor, rms); continue }
                if (rms > max(500.0, floor * 2.5)) { spoke = true; silent = 0 } else silent++
                if (spoke && silent >= 11) break
                if (!spoke && frames >= 70) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (!spoke && !stopFlag) return null to 0
        if (frames < 4) return null to 0
        val data = pcm.toByteArray()
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size)
        return (head.array() + data) to (frames / 10)
    }

    // --- Vosk офлайн
    private fun startVosk() {
        job = scope.launch {
            try {
                val rec = withContext(Dispatchers.IO) { Recognizer(Vosk.load(), 16000f) }
                if (!listening) return@launch
                val svc = SpeechService(rec, 16000f); vosk = svc
                svc.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(h: String?) { partial = Vosk.text(h, "partial") }
                    override fun onResult(h: String?) { val t = Vosk.text(h); if (t.isNotEmpty()) finish(t) }
                    override fun onFinalResult(h: String?) { if (listening) finish(Vosk.text(h)) }
                    override fun onError(e: Exception?) = finish("", e?.message ?: "Ошибка Vosk.")
                    override fun onTimeout() = finish(partial)
                }, 15000)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { finish("", errorText(e)) }
        }
    }
}

/** Слово-активация: Vosk слушает постоянно, пока приложение на экране, и ждёт имя персонажа. */
object Wake {
    var active by mutableStateOf(false)
        private set
    private val scope = MainScope()
    private var svc: SpeechService? = null
    private var job: Job? = null

    fun start(word: String, onWake: (rest: String) -> Unit) {
        stop()
        val w = word.trim().lowercase().replace('ё', 'е').ifBlank { "фредди" }
        job = scope.launch {
            try {
                val rec = withContext(Dispatchers.IO) { Recognizer(Vosk.load(), 16000f) }
                if (!isActive) return@launch
                val s = SpeechService(rec, 16000f); svc = s; active = true
                s.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(h: String?) {}
                    override fun onResult(h: String?) {
                        val t = Vosk.text(h).lowercase().replace('ё', 'е')
                        val i = t.indexOf(w)
                        if (i >= 0) onWake(t.substring(i + w.length).trim())
                    }
                    override fun onFinalResult(h: String?) {}
                    override fun onError(e: Exception?) { stop() }
                    override fun onTimeout() {}
                })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { active = false }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { svc?.stop(); svc?.shutdown() }; svc = null
        active = false
    }
}

/** Уровень громкости воспроизводимого звука через Visualizer. Нужен доступ к микрофону; без него — null. */
class Meter private constructor(private val v: android.media.audiofx.Visualizer) {
    @Volatile var level = 0f
        private set

    fun release() { runCatching { v.enabled = false; v.release() } }

    companion object {
        fun attach(session: Int): Meter? {
            if (androidx.core.content.ContextCompat.checkSelfPermission(S.ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
            return try {
                val vis = android.media.audiofx.Visualizer(session)
                vis.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                val m = Meter(vis)
                vis.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(x: android.media.audiofx.Visualizer?, wave: ByteArray?, rate: Int) {
                        if (wave == null || wave.isEmpty()) return
                        var sum = 0.0
                        for (b in wave) { val d = ((b.toInt() and 255) - 128) / 128.0; sum += d * d }
                        val rms = sqrt(sum / wave.size).toFloat()
                        m.level = m.level * 0.4f + (rms * 5f).coerceIn(0f, 1f) * 0.6f
                    }
                    override fun onFftDataCapture(x: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {}
                }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, true, false)
                vis.enabled = true
                m
            } catch (e: Throwable) { null }
        }
    }
}
