package com.freddyai.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.freddyai.EspRomFlasher
import com.freddyai.FirmwarePackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val ui = Handler(Looper.getMainLooper())

private fun hasCdc(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }

private fun register(r: BroadcastReceiver, f: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) S.ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") S.ctx.registerReceiver(r, f)
}

/** Запрашивает у пользователя доступ к USB-устройству и сообщает результат на главном потоке. */
private fun askPermission(manager: UsbManager, device: UsbDevice, action: String, code: Int, done: (Boolean) -> Unit) {
    if (manager.hasPermission(device)) { done(true); return }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != action) return
            runCatching { S.ctx.unregisterReceiver(this) }
            done(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }
    register(receiver, IntentFilter(action))
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    manager.requestPermission(device, PendingIntent.getBroadcast(S.ctx, code, Intent(action).setPackage(S.ctx.packageName), flags))
}

// ====================================================================== USB CDC: команды глаз и телеметрия
/** Встроенный USB CDC плат ESP32-S2/S3/C3 и RP2040. Переходники CH340/CP210x не поддерживаются — как и в оригинале. */
object Usb {
    var status by mutableStateOf("Не подключена")
    var connected by mutableStateOf(false)
    var telemetry by mutableStateOf("")
    private val io = Executors.newSingleThreadExecutor()
    private val reader = Executors.newSingleThreadScheduledExecutor()
    private var connection: UsbDeviceConnection? = null
    private var output: UsbEndpoint? = null
    private var input: UsbEndpoint? = null
    private var control: UsbInterface? = null
    private var connectedId = -1
    private var asking = false
    private var watching = false
    private val incoming = StringBuilder()
    @Volatile private var flashing = false
    private const val ACTION = "com.freddyai.app.USB_PERMISSION"

    private fun say(text: String) = ui.post { status = text; connected = connection != null }
    private val manager: UsbManager get() = S.ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Следит за подключением и отключением кабеля; вызывается при запуске приложения. */
    fun watch() {
        if (watching) return
        watching = true
        register(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && S.board.auto && S.board.on && S.board.kind == "usb") connect(true)
                if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    @Suppress("DEPRECATION") val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d != null && d.deviceId == connectedId) io.execute { close(); say("Плата отключена.") }
                }
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
        reader.scheduleWithFixedDelay({ io.execute { readTelemetry() } }, 40, 40, TimeUnit.MILLISECONDS)
        if (S.board.auto && S.board.on && S.board.kind == "usb") connect(true)
    }

    fun connect(silent: Boolean = false) {
        if (flashing || asking) return
        val device = manager.deviceList.values.firstOrNull(::hasCdc)
        if (device == null) { if (!silent) say("Плата по USB не найдена. Проверь OTG, кабель передачи данных и питание."); return }
        if (connection != null && device.deviceId == connectedId) return
        asking = true
        askPermission(manager, device, ACTION, 0) { ok ->
            asking = false
            if (ok) io.execute { open(device) } else say("Доступ к USB не разрешён.")
        }
    }

    private fun open(device: UsbDevice) {
        if (flashing) return
        close()
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_COMM) control = f
            if (f.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                data = f
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.direction == UsbConstants.USB_DIR_OUT) output = ep else input = ep }
                }
            }
        }
        if (data == null || output == null) { say("Это не USB CDC. Нужен собственный USB-разъём платы."); return }
        val c = manager.openDevice(device)
        if (c == null || !c.claimInterface(data, true)) { c?.close(); close(); say("Не удалось открыть плату по USB."); return }
        connection = c
        control?.let { ctl ->
            c.claimInterface(ctl, true)
            val line = byteArrayOf(0, 0xC2.toByte(), 1, 0, 0, 0, 8) // 115200 8N1
            val a = c.controlTransfer(0x21, 0x20, 0, ctl.id, line, line.size, 1000)
            // DTR и RTS выставляются вместе: несимметричный переход ESP32 принимает за вход в загрузчик.
            val b = c.controlTransfer(0x21, 0x22, 3, ctl.id, null, 0, 1000)
            if (a < 0 || b < 0) { close(); say("Плата отклонила настройку USB Serial."); return }
        }
        connectedId = device.deviceId
        val off = "OFF\n".toByteArray()
        c.bulkTransfer(output, off, off.size, 150)
        say("Плата подключена по USB.")
    }

    fun write(command: String) = io.execute {
        val c = connection; val out = output
        if (flashing || c == null || out == null) return@execute
        val bytes = command.toByteArray()
        if (c.bulkTransfer(out, bytes, bytes.size, 150) != bytes.size) { close(); say("Плата не принимает данные. Подключи USB снова.") }
    }

    fun disconnect() = io.execute { close(); say("Не подключена") }

    fun pauseForFlash() { flashing = true; io.submit { close() }.get(5, TimeUnit.SECONDS); say("Идёт запись прошивки") }
    fun resumeAfterFlash() { flashing = false; say("Не подключена") }

    private fun readTelemetry() {
        val c = connection; val inp = input
        if (flashing || c == null || inp == null) return
        val buffer = ByteArray(256)
        val count = c.bulkTransfer(inp, buffer, buffer.size, 5)
        for (i in 0 until count) {
            val v = buffer[i].toInt() and 255
            if (v == 10) {
                val line = incoming.toString(); incoming.setLength(0)
                if (line.startsWith("{\"freddy\":1,")) ui.post { telemetry = line }
            } else if (v in 32..126) { if (incoming.length < 1024) incoming.append(v.toChar()) else incoming.setLength(0) }
        }
    }

    private fun close() {
        incoming.setLength(0); input = null
        connection?.let { c ->
            control?.let { c.controlTransfer(0x21, 0x22, 2, it.id, null, 0, 150); c.controlTransfer(0x21, 0x22, 0, it.id, null, 0, 150) }
            c.close()
        }
        connection = null; output = null; control = null; connectedId = -1
    }
}

// ====================================================================== платы и исходники прошивок
class BoardPreset(val id: String, val name: String, val source: String, val transport: String, val notes: String)

/** Каталог плат Arduino из оригинала (419 плат ESP32, AVR, SAMD) с опциями сборки. */
object Catalog {
    class Item(val id: String, val name: String, val options: Map<String, List<Pair<String, String>>>)

    val items: List<Item> by lazy {
        runCatching {
            val a = org.json.JSONArray(S.ctx.assets.open("boards.json").use { String(it.readBytes(), Charsets.UTF_8) })
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val op = o.optJSONObject("options")
                val opts = op?.keys()?.asSequence()?.associateWith { k -> op.getJSONObject(k).let { v -> v.keys().asSequence().map { it to v.str(it) }.toList() } } ?: emptyMap()
                Item(o.str("id"), o.str("name").substringBefore(" · ").trim(), opts)
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Item? = items.firstOrNull { it.id == id }

    fun family(id: String): String = when {
        id.startsWith("arduino:avr:") -> "Arduino AVR"; id.startsWith("arduino:samd:") -> "Arduino SAMD"; id.startsWith("esp32") -> "ESP32"; id == "rp2040" -> "RP2040"; else -> "Шаблоны"
    }

    private fun norm(t: String) = t.lowercase().replace("ардуино", "arduino").replace("есп", "esp").replace("эсп", "esp").filter { it.isLetterOrDigit() }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1); row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = row
        }
        return prev[b.length]
    }

    /** Поиск по названию с допуском небольших опечаток, как в оригинале. */
    fun search(query: String): List<Pair<String, String>> {
        val n = norm(query)
        val all = Firmware.PRESETS.map { it.id to it.name } + items.map { it.id to it.name }
        if (n.isEmpty()) return Firmware.PRESETS.map { it.id to it.name }
        return all.mapNotNull { (id, name) ->
            val nn = norm(name); val ni = norm(id)
            val score = when {
                nn == n || ni == n -> 0
                nn.startsWith(n) -> 1
                nn.contains(n) || ni.contains(n) || norm(family(id)).contains(n) -> 2
                else -> name.split(' ', '-', ':', '/').filter { it.isNotBlank() }.minOfOrNull { distance(norm(it), n) }
                    ?.takeIf { it <= minOf(2, maxOf(1, n.length / 4)) }?.let { 10 + it }
            }
            score?.let { Triple(id, name, it) }
        }.sortedWith(compareBy({ it.third }, { it.second })).take(40).map { it.first to it.second }
    }
}

object Firmware {
    val PRESETS = listOf(
        BoardPreset("esp32-s2-mini", "ESP32-S2 Mini", "FreddyAdaptive.ino", "usb", "Исходная прошивка: GPIO 8, 12 светодиодов, каналы пульта 4/5. Native USB CDC, 115200."),
        BoardPreset("esp32-s3", "ESP32-S3 · USB CDC", "FreddyAdaptive.ino", "usb", "Native USB, USB CDC On Boot включён. GPIO 8 — пример: сверяй со своей проводкой."),
        BoardPreset("esp32-c3", "ESP32-C3 · native USB", "FreddyAdaptive.ino", "usb", "Native USB Serial/JTAG. LED по умолчанию GPIO6, входы пульта 4/5. CH340/CP210x загрузчиком не поддержаны."),
        BoardPreset("rp2040", "RP2040 / Raspberry Pi Pico", "FreddyPico.ino", "usb", "Arduino-Pico, USB Serial CDC. GPIO 2 — пример. Прошивается с компьютера."),
        BoardPreset("esp32-wifi", "ESP32 · Wi-Fi UDP", "FreddyEsp32Wifi.ino", "udp", "Обычный ESP32: подключение через Wi-Fi UDP. GPIO 18 — пример. Прошивается с компьютера."),
        BoardPreset("usb-cdc", "Другая плата · USB CDC", "FreddyCdc.ino", "usb", "Шаблон Arduino + Adafruit NeoPixel. Нужен настоящий USB CDC. Прошивается с компьютера."),
    )

    var busy by mutableStateOf(false)
    var percent by mutableIntStateOf(-1)
    var message by mutableStateOf("")
    var info by mutableStateOf<JSONObject?>(null)
    var edited by mutableIntStateOf(0) // меняется при сохранении кода, чтобы экран перечитал состояние
    private val scope = MainScope()
    private var job: Job? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    private const val ACTION = "com.freddyai.app.FLASH_PERMISSION"

    val STOCK = setOf("esp32-s2-mini", "esp32-s3", "esp32-c3")

    /** Шаблон прошивки для выбранной платы; для плат из каталога — ближайший по семейству, как в оригинале. */
    val preset: BoardPreset get() = presetFor(S.board.preset)

    fun presetFor(id: String): BoardPreset {
        PRESETS.firstOrNull { it.id == id }?.let { return it }
        val c = Catalog.get(id) ?: return PRESETS.first()
        val base = PRESETS.first {
            it.id == when {
                id.contains("s2") -> "esp32-s2-mini"; id.contains("s3") -> "esp32-s3"; id.contains("c3") -> "esp32-c3"
                id.startsWith("esp32:") -> "esp32-wifi"; else -> "usb-cdc"
            }
        }
        return BoardPreset(id, c.name, base.source, base.transport, base.notes)
    }
    private val folder: File get() = File(S.ctx.filesDir, "firmware").apply { mkdirs() }
    private fun editFile(name: String) = File(folder, "edit-$name")

    /** Чип по модели платы: 2 — ESP32-S2, 9 — S3, 5 — C3; -1 — запись с телефона не поддержана. */
    fun chip(board: String): Int = when {
        board == "esp32-s2-mini" -> 2; board == "esp32-s3" -> 9; board == "esp32-c3" -> 5
        board.startsWith("esp32:esp32:") && board.contains("s2") -> 2
        board.startsWith("esp32:esp32:") && board.contains("s3") -> 9
        board.startsWith("esp32:esp32:") && board.contains("c3") -> 5
        else -> -1
    }
    fun fqbn(board: String): String = when (board) { "esp32-s2-mini" -> "esp32:esp32:lolin_s2_mini"; "esp32-s3" -> "esp32:esp32:esp32s3"; "esp32-c3" -> "esp32:esp32:esp32c3"; else -> board }

    fun original(name: String): String = S.ctx.assets.open("firmware/$name").use { String(it.readBytes(), Charsets.UTF_8) }
    fun source(name: String): String = editFile(name).let { if (it.exists()) it.readText() else original(name) }
    fun isEdited(name: String): Boolean = editFile(name).exists()
    fun saveSource(name: String, text: String) { if (text == original(name)) editFile(name).delete() else editFile(name).writeText(text); edited++ }
    fun resetSource(name: String) { editFile(name).delete(); edited++ }

    fun hash(source: String): String =
        MessageDigest.getInstance("SHA-256").digest(source.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }

    fun loadInfo() {
        info = runCatching { File(folder, "selected.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } }.getOrNull()
    }

    /** Готовая прошивка из оригинала подходит, если код и опции платы не менялись. */
    fun stockFits(): Boolean = S.board.preset in STOCK && S.board.options.isEmpty() && !isEdited(preset.source)

    fun useStock(): Boolean {
        if (!stockFits()) return false
        return try {
            val f = File(folder, "stock-${S.board.preset}.zip")
            S.ctx.assets.open("firmware/stock-${S.board.preset}.zip").use { i -> f.outputStream().use { i.copyTo(it) } }
            select(f); message = "Готовая прошивка из оригинала выбрана."; true
        } catch (e: Exception) { message = "⚠ Не удалось подготовить готовую прошивку."; false }
    }

    private fun select(pack: File) {
        val json = FirmwarePackage.load(pack).json(pack.absolutePath)
        File(folder, "selected.json").writeText(json)
        info = JSONObject(json)
    }

    private fun finish(text: String) = ui.post { busy = false; percent = -1; message = text }

    /** Готовый ZIP прошивки (manifest.json + части), выбранный пользователем. */
    fun import(bytes: ByteArray) {
        if (busy) return
        busy = true; message = "Проверяю пакет…"
        worker.execute {
            val tmp = File(folder, "import.tmp")
            try {
                if (bytes.size > FirmwarePackage.MAX) throw IOException("Пакет больше 8 МБ.")
                tmp.writeBytes(bytes)
                FirmwarePackage.load(tmp) // проверка структуры и контрольных сумм
                val target = File(folder, EspRomFlasher.digest("SHA-256", bytes) + ".zip")
                if (target.exists()) tmp.delete() else if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить пакет.")
                ui.post { runCatching { select(target) }; finish("Прошивка готова: ${info?.str("board")}") }
            } catch (e: Exception) { tmp.delete(); finish("⚠ " + (e.message ?: "Пакет не подошёл.")) }
        }
    }

    /** Сборка кода на «Помощнике прошивки» (tools/firmware_compiler.py на компьютере). */
    fun compile(thenFlash: Boolean = false, deviceId: Int = -1) {
        if (busy) return
        val b = S.board; val board = b.preset
        busy = true; cancelled = false; percent = -1; message = "Подготовка прошивки…"
        job = scope.launch {
            try {
                if (chip(board) < 0) throw IOException("Сборка и запись с телефона поддержаны для ESP32-S2/S3/C3. Остальные платы прошиваются с компьютера.")
                if (stockFits()) {
                    // Код не менялся — сборка не нужна, берём готовую прошивку из оригинала.
                    useStock(); busy = false
                    if (thenFlash && deviceId >= 0) flash(deviceId)
                    return@launch
                }
                val url = b.compilerUrl.trim()
                val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
                val local = host == "127.0.0.1" || host == "localhost" || host.startsWith("192.168.") || host.startsWith("10.")
                if (!(url.startsWith("https://") || (url.startsWith("http://") && local)))
                    throw IOException("Укажи адрес «Помощника прошивки» на своём компьютере: https://… или http:// в домашней сети.")
                if (b.token.length < 24) throw IOException("Укажи токен компилятора (не менее 24 символов).")
                val src = source(preset.source).replace("\r\n", "\n"); val h = hash(src)
                message = "Собираю код для выбранной платы…"
                val out = withContext(Dispatchers.IO) {
                    val body = JSONObject().put("board", board).put("fqbn", fqbn(board)).put("options", JSONObject(b.options)).put("source", src).put("sourceSha256", h)
                    val client = http.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()
                    val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${b.token}")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build())
                    call.execute().use { r ->
                        if (!r.isSuccessful) throw IOException("Сборка не выполнена: " + r.body?.string().orEmpty().take(1800))
                        if (r.header("X-Source-SHA256") != h || r.header("X-Board") != board) throw IOException("Ответ не относится к текущему коду или плате.")
                        val bytes = FirmwarePackage.read(r.body!!.byteStream(), FirmwarePackage.MAX)
                        File(folder, "compiled-$h.zip").also { it.writeBytes(bytes) }
                    }
                }
                select(out)
                busy = false; message = "Прошивка собрана."
                if (thenFlash && deviceId >= 0) flash(deviceId)
            } catch (e: CancellationException) { busy = false; message = "Сборка отменена."; throw e } catch (e: Exception) {
                busy = false; message = "⚠ " + (e.message ?: "Сборка не выполнена.")
            }
        }
    }

    fun devices(): List<Pair<Int, String>> {
        val m = S.ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return m.deviceList.values.filter(::hasCdc).map { it.deviceId to (it.productName ?: "USB CDC") }
    }

    fun cancel() { cancelled = true; job?.cancel() }

    /** Запись проверенного пакета в плату через загрузчик ROM. Успех сообщается только после сверки MD5. */
    fun flash(deviceId: Int) {
        if (busy) return
        val i = info
        try {
            if (i == null) throw IOException("Сначала собери код или выбери ZIP прошивки.")
            val board = S.board.preset
            if (i.str("board") != board || i.optInt("chipId") != chip(board)) throw IOException("Выбранная плата и пакет прошивки не совпадают.")
            val prov = i.str("sourceSha256")
            if (prov.isNotEmpty() && prov != hash(source(preset.source))) throw IOException("Код изменён после сборки. Сначала собери его заново.")
            i.optJSONObject("requestedOptions")?.let { o ->
                val got = o.keys().asSequence().associateWith { o.str(it) }
                if (got != S.board.options) throw IOException("Опции платы изменены после сборки. Собери заново.")
            }
            val m = S.ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = m.deviceList.values.firstOrNull { it.deviceId == deviceId && hasCdc(it) }
                ?: throw IOException("Плата пропала. Удерживай BOOT, нажми RESET и выбери USB-устройство заново.")
            Brain.stop()
            busy = true; cancelled = false; percent = 0; message = "Подключение к загрузчику…"
            askPermission(m, device, ACTION, 72) { ok ->
                if (ok && !cancelled) worker.execute { upload(m, device, File(i.str("path")), board) } else finish("Доступ к USB не разрешён или запись отменена.")
            }
        } catch (e: Exception) { message = "⚠ " + e.message }
    }

    private fun upload(manager: UsbManager, device: UsbDevice, path: File, board: String) {
        var connection: UsbDeviceConnection? = null
        var awake: PowerManager.WakeLock? = null
        var paused = false
        try {
            val pack = FirmwarePackage.load(path)
            if (pack.board != board) throw IOException("Пакет собран для другой модели платы.")
            if (cancelled) throw InterruptedIOException("Отменено.")
            paused = true; Usb.pauseForFlash()
            val c = manager.openDevice(device) ?: throw IOException("Не удалось открыть USB.")
            connection = c
            var control: UsbInterface? = null; var data: UsbInterface? = null; var input: UsbEndpoint? = null; var output: UsbEndpoint? = null
            for (n in 0 until device.interfaceCount) {
                val f = device.getInterface(n)
                if (f.interfaceClass == UsbConstants.USB_CLASS_COMM) control = f
                if (f.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && data == null) {
                    var a: UsbEndpoint? = null; var o: UsbEndpoint? = null
                    for (k in 0 until f.endpointCount) {
                        val ep = f.getEndpoint(k)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) a = ep else o = ep
                    }
                    if (a != null && o != null) { data = f; input = a; output = o }
                }
            }
            val inp = input; val out = output
            if (data == null || inp == null || out == null || !c.claimInterface(data, true)) throw IOException("Не удалось открыть интерфейс CDC.")
            control?.let { ctl ->
                if (!c.claimInterface(ctl, true)) throw IOException("CDC control занят.")
                val line = byteArrayOf(0, 0xC2.toByte(), 1, 0, 0, 0, 8)
                if (c.controlTransfer(0x21, 0x20, 0, ctl.id, line, 7, 1000) < 0 || c.controlTransfer(0x21, 0x22, 1, ctl.id, null, 0, 1000) < 0)
                    throw IOException("Не удалось настроить CDC.")
            }
            awake = (S.ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreddyAI:Firmware").apply { acquire(15 * 60 * 1000L) }
            val link = object : EspRomFlasher.Link {
                val buffer = ByteArray(4096); var position = 0; var count = 0
                override fun write(bytes: ByteArray) {
                    var at = 0
                    while (at < bytes.size) {
                        if (cancelled) throw InterruptedIOException("Отменено.")
                        val n = c.bulkTransfer(out, bytes, at, bytes.size - at, 3000)
                        if (n <= 0) throw IOException("USB отключён при записи.")
                        at += n
                    }
                }
                override fun readByte(deadlineNanos: Long): Int {
                    if (position >= count) {
                        val timeout = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceIn(1, 100).toInt()
                        count = c.bulkTransfer(inp, buffer, buffer.size, timeout); position = 0
                        if (count <= 0) return -1
                    }
                    return buffer[position++].toInt() and 255
                }
            }
            EspRomFlasher(link, object : EspRomFlasher.Progress {
                override fun cancelled(): Boolean = cancelled
                override fun update(p: Int, text: String) { ui.post { percent = p; message = text } }
            }).flash(pack.segments, pack.chip, pack.flashSize)
            finish("Готово: записанная Flash проверена по MD5. Нажми RESET на плате.")
        } catch (e: Exception) {
            finish("⚠ " + (e.message ?: "Запись не выполнена."))
        } finally {
            connection?.close()
            awake?.let { if (it.isHeld) it.release() }
            if (paused) Usb.resumeAfterFlash()
        }
    }
}
