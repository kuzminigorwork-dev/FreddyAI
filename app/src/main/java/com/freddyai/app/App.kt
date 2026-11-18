package com.freddyai.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

fun JSONObject.str(k: String, d: String = ""): String = if (isNull(k)) d else optString(k, d)

/** Нейросеть с OpenAI-совместимым API. Порядок в списке = порядок запасных вариантов. */
data class Svc(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val keyUrl: String = "",
    val key: String = "",
    val enabled: Boolean = true,
    val custom: Boolean = false,
    val needsKey: Boolean = true,
    val status: Int = 0, // 0 не проверен, 1 работает, 2 ошибка
    val statusText: String = "",
    val limits: String = "",
)

data class FishCfg(
    val key: String = "",
    val model: String = "s2.1-pro-free",
    val tts: Boolean = true,
    val tags: Boolean = true,
    val showTags: Boolean = true,
    val cache: Boolean = true,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val status: Int = 0,
    val statusText: String = "",
    val credit: String = "",
)

data class Profile(
    val id: String,
    val name: String,
    val persona: String,
    val voiceId: String = "",
    val voiceName: String = "",
    val wake: String = "фредди",
    val userName: String = "",
    val memory: List<String> = emptyList(),
)

data class Phrase(val id: String, val title: String, val text: String, val ask: Boolean = false)
data class Msg(val id: Long, val user: Boolean, val text: String, val via: String = "", val err: Boolean = false)
data class SttCfg(val mode: String = "system", val wake: Boolean = false, val awake: Boolean = false, val talkWake: Boolean = false)
data class TgCfg(val token: String = "", val allowed: String = "", val voice: Boolean = true, val on: Boolean = false)
data class BoardCfg(
    val on: Boolean = false, val kind: String = "udp", val host: String = "192.168.4.1", val port: Int = 18888,
    val preset: String = "esp32-s2-mini", val compilerUrl: String = "", val token: String = "", val auto: Boolean = true,
    val eyeMode: String = "ai", val bright: Int = 255, val options: Map<String, String> = emptyMap(),
)
data class VisionCfg(
    val on: Boolean = false, val front: Boolean = true, val detector: String = "objects", val detailed: Boolean = true,
    val comment: Boolean = false, val every: Int = 30, val svc: String = "groq", val model: String = "qwen/qwen3.8-27b", val journal: Boolean = true,
    val source: String = "phone", val url: String = "",
)
data class ThemeCfg(val id: String = "fish", val bg: String = "#1C1B1A", val card: String = "#111010", val text: String = "#F5F3F0", val accent: String = "#A78BFA")
data class Note(val time: Long, val text: String)
data class GeoCfg(val lat: String = "", val lon: String = "", val place: String = "")

const val DEFAULT_PERSONA =
    "Ты — Фредди Фазбер: театральный, загадочный, с добрым чувством юмора. Говори по-русски, коротко, 1–3 предложения. " +
        "Если плохо расслышал, спокойно переспроси. Не выдавай фантазии за реальные события. " +
        "В начале каждого ответа одна эмоция: [нормальный], [весёлый], [жуткий], [добрый], [злой], [подозрительный], [поднятая бровь]. " +
        "Не проговаривай названия тегов. Обращайся по имени, если оно известно."
const val DEFAULT_VOICE = "4d7de378756b4215bf46ea7684919ce0"

object S {
    lateinit var ctx: Context
    private var ready = false
    val services = mutableStateListOf<Svc>()
    var fish by mutableStateOf(FishCfg())
    val profiles = mutableStateListOf<Profile>()
    var profileId by mutableStateOf("freddy")
    val phrases = mutableStateListOf<Phrase>()
    val messages = mutableStateListOf<Msg>()
    var stt by mutableStateOf(SttCfg())
    var tg by mutableStateOf(TgCfg())
    var board by mutableStateOf(BoardCfg())
    var geo by mutableStateOf(GeoCfg())
    var vision by mutableStateOf(VisionCfg())
    var theme by mutableStateOf(ThemeCfg())
    /** Поиск в интернете для вопросов о свежих событиях и FNAF. */
    var web by mutableStateOf(true)
    /** Проверять обновления на GitHub при запуске. */
    var autoUpdate by mutableStateOf(true)
    val journal = mutableStateListOf<Note>()
    val usage = mutableStateMapOf<String, Long>()
    private var nextId = 1L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val profile: Profile get() = profiles.firstOrNull { it.id == profileId } ?: profiles.first()
    private val file: File get() = File(ctx.filesDir, "state.bin")

    fun init(c: Context) {
        if (ready) return
        ctx = c.applicationContext
        defaults()
        try {
            if (file.exists()) restore(JSONObject(String(Secure.decBytes(file.readBytes()), Charsets.UTF_8)), true)
        } catch (e: Exception) {
            // Повреждённый файл не должен мешать запуску; сохраняем копию для разбора.
            runCatching { file.copyTo(File(ctx.filesDir, "state.invalid"), true) }
        }
        ready = true
    }

    internal fun defaults() {
        services.clear()
        services += listOf(
            Svc("groq", "Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys"),
            Svc("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto", "https://openrouter.ai/keys"),
            Svc("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "https://aistudio.google.com/apikey"),
            Svc("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "https://console.mistral.ai/api-keys"),
            Svc("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b", "https://cloud.cerebras.ai"),
            Svc("ollama", "Ollama (локально)", "http://192.168.1.10:11434/v1", "llama3.2", "", enabled = false, needsKey = false),
        )
        profiles.clear()
        profiles += Profile("freddy", "Фредди", DEFAULT_PERSONA, DEFAULT_VOICE, "Фредди (по умолчанию)")
        phrases.clear()
        phrases += listOf(
            Phrase("p1", "Приветствие", "[chuckling] Привет! Я Фредди. Рад тебя видеть."),
            Phrase("p2", "Шутка", "Расскажи короткую шутку.", ask = true),
            Phrase("p3", "Страшилка", "[whispering] Не оборачивайся... [long-break] я уже здесь."),
        )
    }

    // ---------- изменение состояния (только с главного потока) ----------
    fun setSvc(id: String, f: (Svc) -> Svc) {
        val i = services.indexOfFirst { it.id == id }
        if (i >= 0) services[i] = f(services[i])
    }

    fun setProfile(f: (Profile) -> Profile) {
        val i = profiles.indexOfFirst { it.id == profileId }
        if (i >= 0) profiles[i] = f(profiles[i])
    }

    fun addMsg(user: Boolean, text: String, via: String = "", err: Boolean = false): Long {
        val id = nextId++
        messages += Msg(id, user, text, via, err)
        while (messages.size > 200) messages.removeAt(0)
        return id
    }

    fun setMsg(id: Long, text: String, via: String, err: Boolean = false) {
        val i = messages.indexOfLast { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(text = text, via = via, err = err)
    }

    fun note(text: String) {
        if (!vision.journal || text.isBlank()) return
        journal.add(0, Note(System.currentTimeMillis(), text.take(400)))
        while (journal.size > 100) journal.removeAt(journal.size - 1)
    }

    fun remember(fact: String) {
        val f = fact.trim().take(500)
        if (f.isEmpty() || f in profile.memory) return
        setProfile { it.copy(memory = (it.memory + f).takeLast(100)) }
    }

    // ---------- учёт расхода ----------
    fun day(offset: Int = 0): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -offset)
        return dayFmt.format(c.time)
    }

    /** Можно вызывать из любого потока. */
    fun count(counter: String, n: Long = 1) {
        if (n == 0L) return
        main.post { val k = day() + "|" + counter; usage[k] = (usage[k] ?: 0L) + n }
    }

    fun sum(days: Int, match: (String) -> Boolean): Long {
        val set = (0 until days).map { day(it) }.toSet()
        return usage.entries.sumOf { (k, v) -> val p = k.split('|'); if (p[0] in set && match(p[1])) v else 0L }
    }

    fun onDay(offset: Int, match: (String) -> Boolean): Long {
        val d = day(offset)
        return usage.entries.sumOf { (k, v) -> val p = k.split('|'); if (p[0] == d && match(p[1])) v else 0L }
    }

    // ---------- сохранение ----------
    fun save() {
        if (!ready) return
        val bytes = snapshot(full = true, keys = true).toString().toByteArray()
        io.execute {
            runCatching {
                val tmp = File(ctx.filesDir, "state.tmp")
                tmp.writeBytes(Secure.encBytes(bytes))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    /** full — вместе с историей чата и статистикой; keys — вместе с ключами сервисов. */
    fun snapshot(full: Boolean, keys: Boolean, slim: Boolean = false): JSONObject {
        val o = JSONObject().put("v", 1)
        o.put("services", JSONArray().also { a ->
            services.forEach { s ->
                a.put(JSONObject().put("id", s.id).put("name", s.name).put("baseUrl", s.baseUrl).put("model", s.model)
                    .put("keyUrl", s.keyUrl).put("key", if (keys) s.key else "").put("enabled", s.enabled)
                    .put("custom", s.custom).put("needsKey", s.needsKey))
            }
        })
        fish.let { o.put("fish", JSONObject().put("key", if (keys) it.key else "").put("model", it.model).put("tts", it.tts)
            .put("tags", it.tags).put("showTags", it.showTags).put("cache", it.cache).put("speed", it.speed.toDouble()).put("volume", it.volume.toDouble())) }
        o.put("web", web).put("autoUpdate", autoUpdate)
        o.put("profiles", JSONArray().also { a ->
            (if (slim) listOf(profile) else profiles.toList()).forEach { p ->
                a.put(JSONObject().put("id", p.id).put("name", p.name).put("persona", p.persona).put("voiceId", p.voiceId)
                    .put("voiceName", p.voiceName).put("wake", p.wake).put("userName", p.userName)
                    .put("memory", JSONArray(if (slim) emptyList() else p.memory)))
            }
        })
        o.put("profileId", profileId)
        if (!slim) o.put("phrases", JSONArray().also { a ->
            phrases.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text).put("ask", it.ask)) }
        })
        o.put("stt", JSONObject().put("mode", stt.mode).put("wake", stt.wake).put("awake", stt.awake).put("talkWake", stt.talkWake))
        o.put("tg", JSONObject().put("token", if (keys) tg.token else "").put("allowed", tg.allowed).put("voice", tg.voice).put("on", tg.on))
        o.put("board", JSONObject().put("on", board.on).put("kind", board.kind).put("host", board.host).put("port", board.port)
            .put("preset", board.preset).put("compilerUrl", board.compilerUrl).put("token", if (keys) board.token else "").put("auto", board.auto)
            .put("eyeMode", board.eyeMode).put("bright", board.bright).put("options", JSONObject(board.options)))
        vision.let { o.put("vision", JSONObject().put("on", it.on).put("front", it.front).put("detector", it.detector).put("detailed", it.detailed)
            .put("comment", it.comment).put("every", it.every).put("svc", it.svc).put("model", it.model).put("journal", it.journal)
            .put("source", it.source).put("url", it.url)) }
        theme.let { o.put("theme", JSONObject().put("id", it.id).put("bg", it.bg).put("card", it.card).put("text", it.text).put("accent", it.accent)) }
        o.put("geo", JSONObject().put("lat", geo.lat).put("lon", geo.lon).put("place", geo.place))
        if (full) {
            val keep = (0 until 60).map { day(it) }.toSet()
            o.put("usage", JSONObject().also { u -> usage.forEach { (k, v) -> if (k.substringBefore('|') in keep) u.put(k, v) } })
            o.put("journal", JSONArray().also { a -> journal.forEach { a.put(JSONObject().put("t", it.time).put("x", it.text)) } })
            o.put("messages", JSONArray().also { a ->
                messages.forEach { a.put(JSONObject().put("u", it.user).put("t", it.text).put("via", it.via).put("e", it.err)) }
            })
        }
        return o
    }

    /** own — наш собственный файл состояния (ключи сохраняются как есть). При импорте пустой ключ не затирает текущий. */
    fun restore(o: JSONObject, own: Boolean) {
        o.optJSONArray("services")?.let { a ->
            val old = services.associateBy { it.id }
            val list = (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
                val prev = old[j.str("id")]
                val key = j.str("key").ifEmpty { if (own) "" else prev?.key.orEmpty() }
                Svc(j.str("id"), j.str("name"), j.str("baseUrl"), j.str("model"), j.str("keyUrl"), key,
                    j.optBoolean("enabled", true), j.optBoolean("custom"), j.optBoolean("needsKey", true))
            }.filter { it.id.isNotBlank() }
            if (list.isNotEmpty()) {
                val ids = list.map { it.id }.toSet()
                val rest = if (own) emptyList() else services.filter { it.id !in ids }
                // Встроенные сервисы, которых нет в файле (например, после обновления), остаются в конце списка.
                val builtin = if (own) services.filter { it.id !in ids && !it.custom } else emptyList()
                services.clear(); services += list + rest + builtin
            }
        }
        o.optJSONObject("fish")?.let { j ->
            fish = FishCfg(j.str("key").ifEmpty { if (own) "" else fish.key }, j.str("model", "s2.1-pro-free"), j.optBoolean("tts", true),
                j.optBoolean("tags", true), j.optBoolean("showTags", true), j.optBoolean("cache", true), j.optDouble("speed", 1.0).toFloat(),
                j.optDouble("volume", 1.0).toFloat().coerceIn(0f, 2f))
        }
        if (o.has("web")) web = o.optBoolean("web", true)
        if (o.has("autoUpdate")) autoUpdate = o.optBoolean("autoUpdate", true)
        o.optJSONArray("profiles")?.let { a ->
            val list = (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
                val mem = j.optJSONArray("memory")?.let { m -> (0 until m.length()).map { m.getString(it) } } ?: emptyList()
                Profile(j.str("id"), j.str("name"), j.str("persona"), j.str("voiceId"), j.str("voiceName"), j.str("wake", "фредди"), j.str("userName"), mem)
            }.filter { it.id.isNotBlank() }
            if (list.isNotEmpty()) {
                if (own) { profiles.clear(); profiles += list } else {
                    list.forEach { p ->
                        val i = profiles.indexOfFirst { it.id == p.id }
                        if (i >= 0) profiles[i] = if (p.memory.isEmpty()) p.copy(memory = profiles[i].memory) else p else profiles += p
                    }
                }
            }
        }
        o.str("profileId").let { id -> if (profiles.any { it.id == id }) profileId = id }
        o.optJSONArray("phrases")?.let { a ->
            phrases.clear()
            for (i in 0 until a.length()) a.getJSONObject(i).let { phrases += Phrase(it.str("id"), it.str("title"), it.str("text"), it.optBoolean("ask")) }
        }
        o.optJSONObject("stt")?.let { stt = SttCfg(it.str("mode", "system"), it.optBoolean("wake"), it.optBoolean("awake"), it.optBoolean("talkWake")) }
        o.optJSONObject("tg")?.let { tg = TgCfg(it.str("token").ifEmpty { if (own) "" else tg.token }, it.str("allowed"), it.optBoolean("voice", true), own && it.optBoolean("on")) }
        o.optJSONObject("board")?.let { board = BoardCfg(it.optBoolean("on"), it.str("kind", "udp"), it.str("host", "192.168.4.1"), it.optInt("port", 18888),
            it.str("preset", "esp32-s2-mini"), it.str("compilerUrl"), it.str("token").ifEmpty { if (own) "" else board.token }, it.optBoolean("auto", true),
            it.str("eyeMode", "ai"), it.optInt("bright", 255).coerceIn(0, 255),
            it.optJSONObject("options")?.let { op -> op.keys().asSequence().associateWith { k -> op.str(k) } } ?: emptyMap()) }
        o.optJSONObject("vision")?.let { vision = VisionCfg(it.optBoolean("on"), it.optBoolean("front", true), it.str("detector", "objects"), it.optBoolean("detailed", true),
            it.optBoolean("comment"), it.optInt("every", 30), it.str("svc", "groq"), it.str("model", "qwen/qwen3.8-27b"), it.optBoolean("journal", true),
            it.str("source", "phone"), it.str("url")) }
        o.optJSONObject("theme")?.let { theme = ThemeCfg(it.str("id", "fish"), it.str("bg", "#1C1B1A"), it.str("card", "#111010"), it.str("text", "#F5F3F0"), it.str("accent", "#A78BFA")) }
        o.optJSONObject("geo")?.let { geo = GeoCfg(it.str("lat"), it.str("lon"), it.str("place")) }
        if (own) {
            o.optJSONObject("usage")?.let { u -> usage.clear(); u.keys().forEach { k -> usage[k] = u.optLong(k) } }
            o.optJSONArray("journal")?.let { a -> journal.clear(); for (i in 0 until a.length()) a.getJSONObject(i).let { journal += Note(it.optLong("t"), it.str("x")) } }
            o.optJSONArray("messages")?.let { a ->
                messages.clear()
                for (i in 0 until a.length()) a.getJSONObject(i).let { messages += Msg(nextId++, it.optBoolean("u"), it.str("t"), it.str("via"), it.optBoolean("e")) }
            }
        }
    }
}

/** Ключи и состояние шифруются ключом из Android Keystore; резервные копии — паролем. */
object Secure {
    private const val ALIAS = "freddy_state_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun encBytes(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun decBytes(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun passKey(pass: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)).encoded, "AES")
    }

    fun seal(plain: ByteArray, pass: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, passKey(pass, salt), GCMParameterSpec(128, iv))
        return salt + iv + c.doFinal(plain)
    }

    fun open(data: ByteArray, pass: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, passKey(pass, data.copyOfRange(0, 16)), GCMParameterSpec(128, data, 16, 12))
        return c.doFinal(data, 28, data.size - 28)
    }
}

/** Формат резервной копии и QR: "FAI1:" + base64(AES-GCM(deflate(json))). */
object Backup {
    private const val HEAD = "FAI1:"

    fun pack(o: JSONObject, pass: String): String {
        val z = ByteArrayOutputStream()
        DeflaterOutputStream(z).use { it.write(o.toString().toByteArray()) }
        return HEAD + java.util.Base64.getUrlEncoder().encodeToString(Secure.seal(z.toByteArray(), pass))
    }

    fun unpack(text: String, pass: String): JSONObject {
        val t = text.trim()
        require(t.startsWith(HEAD)) { "Это не резервная копия FreddyAI." }
        val raw = try {
            Secure.open(java.util.Base64.getUrlDecoder().decode(t.substring(HEAD.length)), pass)
        } catch (e: Exception) {
            throw IllegalArgumentException("Неверный пароль или повреждённые данные.")
        }
        return JSONObject(String(InflaterInputStream(raw.inputStream()).readBytes(), Charsets.UTF_8))
    }
}

fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())
