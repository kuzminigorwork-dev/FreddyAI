package com.freddyai.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Nav {
    var talk by mutableStateOf(false)
}

private fun granted(ctx: android.content.Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

// ====================================================================== разговор с головой
/** Полноэкранный режим: голова в центре, Фредди сам слушает после каждого ответа. Камера работает только здесь. */
@Composable
fun TalkScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var hasMic by remember { mutableStateOf(granted(ctx, Manifest.permission.RECORD_AUDIO)) }
    var hasCam by remember { mutableStateOf(granted(ctx, Manifest.permission.CAMERA)) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasMic = r[Manifest.permission.RECORD_AUDIO] ?: hasMic
        hasCam = r[Manifest.permission.CAMERA] ?: hasCam
    }
    val phoneCam = S.vision.on && S.vision.source != "net"
    LaunchedEffect(S.vision.on, S.vision.source) {
        val need = buildList { if (!hasMic) add(Manifest.permission.RECORD_AUDIO); if (phoneCam && !hasCam) add(Manifest.permission.CAMERA) }
        if (need.isNotEmpty()) ask.launch(need.toTypedArray())
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        Ears.silentStreak = 0
        onDispose { view.keepScreenOn = S.stt.awake; Ears.cancel(); Wake.stop() }
    }
    BackHandler { onClose() }

    val idle = hasMic && !paused && !Brain.busy && !Speaker.speaking && !Ears.listening && !MusicBox.playing
    val byName = S.stt.talkWake && Vosk.installed
    val wakeWord = S.profile.wake
    if (byName) {
        DisposableEffect(idle, wakeWord) {
            if (idle) Wake.start(wakeWord) { rest ->
                Wake.stop()
                if (rest.length > 2) Brain.send(rest) else Ears.start { Brain.send(it) }
            }
            onDispose { Wake.stop() }
        }
    } else {
        // Непрерывный разговор: после ответа Фредди снова слушает. Три тишины подряд — пауза, чтобы не держать микрофон зря.
        LaunchedEffect(idle) {
            if (!idle) return@LaunchedEffect
            if (Ears.silentStreak >= 3) { paused = true; hint = "Долго тишина — разговор на паузе. Нажми микрофон, чтобы продолжить."; return@LaunchedEffect }
            if (Ears.error.isNotBlank() && Ears.silentStreak >= 1) { paused = true; hint = Ears.error; return@LaunchedEffect }
            delay(600)
            Ears.start { Brain.send(it) }
        }
    }

    val last = S.messages.lastOrNull { !it.user }
    val status = when {
        !hasMic -> "Нет доступа к микрофону"
        MusicBox.playing -> "Играет шкатулка"
        Ears.listening -> Ears.partial.ifBlank { "Слушаю…" }
        Brain.busy -> if (Brain.via.isNotBlank() && !Brain.via.first().isUpperCase()) Brain.via.replaceFirstChar { it.uppercase() } else "Думаю…"
        Speaker.speaking -> "Говорю"
        paused -> hint.ifBlank { "Пауза" }
        byName -> "Скажи «${S.profile.wake}»"
        else -> "…"
    }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = C.text) }
            Text(S.profile.name, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { MusicBox.toggle() }) { Icon(Icons.Filled.MusicNote, "Музыкальная шкатулка", tint = if (MusicBox.playing) C.purple else C.text) }
            if (S.vision.on && phoneCam) IconButton(onClick = { S.vision = S.vision.copy(front = !S.vision.front); S.save() }) { Icon(Icons.Filled.Cameraswitch, "Сменить камеру", tint = C.text) }
            IconButton(onClick = { S.vision = S.vision.copy(on = !S.vision.on); S.save() }) {
                Icon(if (S.vision.on) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Камера", tint = if (S.vision.on) C.teal else C.dim)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            FreddyHead(Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f))
            Text(status, Modifier.padding(top = 10.dp), color = if (Ears.listening) C.teal else C.dim, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (last != null && last.text.isNotBlank()) Text(tagged(last.text, S.fish.showTags), Modifier.padding(top = 12.dp), fontSize = 19.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                maxLines = 6, overflow = TextOverflow.Ellipsis, color = if (last.err) C.yellow else C.text)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
            if (S.vision.on && (hasCam || !phoneCam)) {
                Column(Modifier.width(150.dp)) {
                    CameraFeed(Modifier.size(150.dp, 200.dp).clip(RoundedCornerShape(20.dp)).background(C.card))
                    Sub(Vision.error.ifBlank { Vision.marks.map { it.label }.distinct().joinToString(", ").ifBlank { "ничего знакомого" } }, Modifier.padding(top = 6.dp))
                }
            } else Spacer(Modifier.width(150.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (Brain.busy || Speaker.speaking || MusicBox.playing) {
                    Box(Modifier.size(60.dp).clip(CircleShape).background(C.raised).clickable { Brain.stop() }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Stop, "Стоп", tint = C.text) }
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.size(76.dp).clip(CircleShape).background(if (paused) C.raised else C.text).clickable {
                    paused = !paused; hint = ""
                    if (paused) Ears.cancel() else { Ears.silentStreak = 0; Ears.error = "" }
                }, contentAlignment = Alignment.Center) {
                    Icon(if (paused) Icons.Filled.MicOff else Icons.Filled.Mic, if (paused) "Продолжить" else "Пауза", Modifier.size(30.dp), tint = if (paused) C.text else C.ink)
                }
            }
        }
    }
}

// ====================================================================== зрение
@Composable
fun VisionPage() {
    val v = S.vision
    FCard {
        Title("Камера")
        Sub("Камера включается в режиме разговора (кнопка с головой в чате) и выключается при выходе из него.", Modifier.padding(top = 4.dp))
        Toggle("Зрение включено", checked = v.on) { S.vision = S.vision.copy(on = it); S.save() }
        Sub("Источник", Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Телефон", v.source != "net") { S.vision = v.copy(source = "phone"); S.save() }
            Chip("Сетевая камера", v.source == "net") { S.vision = v.copy(source = "net"); S.save() }
        }
        if (v.source == "net") {
            Field(v.url, { S.vision = v.copy(url = it.trim()) }, "Адрес снимка JPEG", Modifier.padding(top = 10.dp))
            Sub("Например ESP32-CAM: http://192.168.4.1/capture, IP Webcam: http://192.168.1.20:8080/shot.jpg", Modifier.padding(top = 6.dp))
        } else Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Фронтальная", v.front) { S.vision = v.copy(front = true); S.save() }
            Chip("Основная", !v.front) { S.vision = v.copy(front = false); S.save() }
        }
        Sub("Что искать в кадре", Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Предметы", v.detector == "objects") { S.vision = v.copy(detector = "objects"); S.save() }
            Chip("Лица", v.detector == "faces") { S.vision = v.copy(detector = "faces"); S.save() }
        }
        Sub(if (v.detector == "faces") "Считает лица и улыбки. Это не узнавание знакомых: кто именно в кадре, приложение не определяет."
            else "80 категорий предметов (человек, кошка, чашка, телефон…). Названия на английском, Фредди переводит сам.", Modifier.padding(top = 8.dp))
        Sub("Анализ идёт на телефоне, кадры никуда не отправляются.", Modifier.padding(top = 4.dp))
    }
    FCard {
        Title("Подробное зрение")
        Toggle("Рассматривать по просьбе", "На слова «посмотри», «что это», «кто перед тобой» один кадр уходит в облачную модель.", v.detailed) { S.vision = S.vision.copy(detailed = it); S.save() }
        Sub("Сервис", Modifier.padding(top = 4.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            S.services.filter { it.enabled && (it.key.isNotBlank() || !it.needsKey) }.forEach { s -> Chip(s.name, s.id == v.svc) { S.vision = v.copy(svc = s.id); S.save() } }
        }
        Field(v.model, { S.vision = v.copy(model = it.trim()) }, "Модель зрения", Modifier.padding(top = 10.dp))
        Sub("Модель должна понимать изображения. Для Groq подходит qwen/qwen3.8-27b или meta-llama/llama-4-scout-17b-16e-instruct, для Gemini — gemini-2.5-flash.", Modifier.padding(top = 6.dp))
        Toggle("Реагировать на изменения", "Фредди сам комментирует, когда в кадре появляется что-то новое. Тратит лимит нейросети.", v.comment) { S.vision = S.vision.copy(comment = it); S.save() }
        if (v.comment) Field(v.every.toString(), { t -> S.vision = v.copy(every = t.filter(Char::isDigit).take(4).toIntOrNull()?.coerceAtLeast(10) ?: v.every) }, "Не чаще, чем раз в … секунд", number = true)
    }
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Журнал наблюдений", Modifier.weight(1f))
            if (S.journal.isNotEmpty()) Pill("Очистить", primary = false) { S.journal.clear(); S.save() }
        }
        Toggle("Вести журнал", checked = v.journal) { S.vision = S.vision.copy(journal = it); S.save() }
        if (S.journal.isEmpty()) Sub("Пока пусто.")
        val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale("ru")) }
        S.journal.take(40).forEach { n ->
            Column(Modifier.padding(top = 10.dp)) { Sub(fmt.format(Date(n.time))); Text(n.text, fontSize = 15.sp) }
        }
    }
}

// ====================================================================== плата и прошивка
@Composable
fun BoardPage() {
    val ctx = LocalContext.current
    val b = S.board
    var editor by rememberSaveable { mutableStateOf(false) }
    if (editor) { CodeEditor(Firmware.preset.source) { editor = false }; return }

    FCard {
        Title("Подключение")
        Toggle("Управлять глазами головы", "Цвет глаз меняется по эмоции ответа.", b.on) {
            S.board = S.board.copy(on = it); S.save()
            if (it && S.board.kind == "usb") Usb.connect(true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("USB (OTG)", b.kind == "usb") { S.board = b.copy(kind = "usb"); S.save() }
            Chip("Wi-Fi UDP", b.kind == "udp") { S.board = b.copy(kind = "udp"); S.save() }
        }
        Spacer(Modifier.height(10.dp))
        if (b.kind == "usb") {
            Row(verticalAlignment = Alignment.CenterVertically) { Dot(if (Usb.connected) C.green else C.line); Sub("  " + Usb.status) }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (Usb.connected) "Отключить" else "Подключить", primary = !Usb.connected) { if (Usb.connected) Usb.disconnect() else Usb.connect() }
            }
            Toggle("Подключать автоматически", checked = b.auto) { S.board = S.board.copy(auto = it); S.save() }
            Sub("Нужен собственный USB-разъём платы (ESP32-S2/S3/C3, RP2040). Переходники CH340/CP210x не поддержаны.")
            if (Usb.telemetry.isNotBlank()) Sub("Телеметрия: ${Usb.telemetry.take(160)}", Modifier.padding(top = 6.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(b.host, { S.board = b.copy(host = it.trim()) }, "IP платы", Modifier.weight(2f))
                Field(b.port.toString(), { t -> S.board = b.copy(port = t.filter(Char::isDigit).take(5).toIntOrNull()?.coerceIn(1, 65535) ?: b.port) }, "Порт", Modifier.weight(1f), number = true)
            }
            if (Board.lastError.isNotBlank()) Sub("⚠ ${Board.lastError}", Modifier.padding(top = 8.dp), C.yellow)
        }
    }

    FCard {
        Title("Глаза")
        Sub("Режим", Modifier.padding(top = 8.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Board.EYE_MODES.forEach { (id, name) -> Chip(name, b.eyeMode == id) { S.board = S.board.copy(eyeMode = id); S.save(); Board.restoreEyes() } }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Sub("Яркость ${b.bright * 100 / 255}%", Modifier.width(110.dp))
            Slider(b.bright.toFloat(), { S.board = S.board.copy(bright = it.toInt().coerceIn(0, 255)) }, Modifier.weight(1f), valueRange = 0f..255f,
                onValueChangeFinished = { S.save(); Board.send("B" + S.board.bright) },
                colors = SliderDefaults.colors(thumbColor = C.text, activeTrackColor = C.text, inactiveTrackColor = C.line))
        }
        Sub("Музыкальная шкатулка включает на плате режим MUSIC: глаза мерцают в такт.", Modifier.padding(top = 4.dp))
    }

    val preset = Firmware.preset
    val canFlash = Firmware.chip(preset.id) > 0
    val seen = Firmware.edited // перечитать состояние после правки кода
    var query by rememberSaveable { mutableStateOf("") }
    FCard {
        Title("Плата и код")
        Text(preset.name, Modifier.padding(top = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Sub(Catalog.family(preset.id) + " · " + preset.notes)
        Field(query, { query = it }, "Найти плату: S2 Mini, Uno, Pico…", Modifier.padding(top = 10.dp))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Catalog.search(query).take(if (query.isBlank()) 6 else 24).forEach { (id, name) ->
                Chip(name, id == preset.id) { S.board = S.board.copy(preset = id, options = emptyMap()); S.save() }
            }
        }
        Catalog.get(preset.id)?.options?.takeIf { it.isNotEmpty() }?.let { menus ->
            Sub("Опции сборки", Modifier.padding(top = 12.dp, bottom = 4.dp))
            menus.entries.take(12).forEach { (menu, values) ->
                Sub(menuName(menu), Modifier.padding(top = 6.dp, bottom = 4.dp), C.text)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cur = b.options[menu] ?: values.firstOrNull()?.first
                    values.forEach { (value, label) ->
                        Chip(label, value == cur) {
                            val first = values.firstOrNull()?.first
                            S.board = S.board.copy(options = if (value == first) S.board.options - menu else S.board.options + (menu to value)); S.save()
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Открыть код", primary = false) { editor = true }
            Sub(preset.source + if (seen >= 0 && Firmware.isEdited(preset.source)) " · изменён" else "")
        }
    }

    var device by remember { mutableIntStateOf(-1) }
    var devices by remember { mutableStateOf(Firmware.devices()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.onSuccess { Firmware.import(it) }.onFailure { Firmware.message = "⚠ Не удалось прочитать файл." }
    }
    FCard {
        Title("Прошивка")
        if (!canFlash) Sub("Запись с телефона работает для ESP32-S2, S3 и C3. Для этой платы открой код, экспортируй его и прошей с компьютера через Arduino IDE.", Modifier.padding(top = 6.dp))
        else {
            if (Firmware.stockFits()) {
                Sub("Шаг 1. Код не менялся — можно взять готовую прошивку из оригинала, компьютер не нужен.", Modifier.padding(top = 6.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Готовая прошивка", enabled = !Firmware.busy) { Firmware.useStock() }
                    Pill("Выбрать ZIP", primary = false, enabled = !Firmware.busy) { pick.launch(arrayOf("application/zip", "application/octet-stream")) }
                }
            } else {
                Sub("Шаг 1. Собери код на своём компьютере («Помощник прошивки», файл tools/firmware_compiler.py) или выбери готовый ZIP прошивки.", Modifier.padding(top = 6.dp, bottom = 8.dp))
                Field(b.compilerUrl, { S.board = b.copy(compilerUrl = it.trim()) }, "Адрес помощника, например http://192.168.1.5:8766/compile")
                Spacer(Modifier.height(8.dp))
                Field(b.token, { S.board = b.copy(token = it.trim()) }, "Токен помощника", secret = true)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Собрать", enabled = !Firmware.busy) { S.save(); Firmware.compile() }
                    Pill("Выбрать ZIP", primary = false, enabled = !Firmware.busy) { pick.launch(arrayOf("application/zip", "application/octet-stream")) }
                }
            }
            Firmware.info?.let { i ->
                val fresh = i.str("board") == preset.id && (i.str("sourceSha256").isEmpty() || (seen >= 0 && i.str("sourceSha256") == Firmware.hash(Firmware.source(preset.source))))
                Sub("Готов пакет: ${i.str("name")}" + if (!fresh) " · не подходит к текущей плате или коду" else "", Modifier.padding(top = 10.dp), if (fresh) C.green else C.yellow)
            }
            Sub("Шаг 2. Подключи плату через OTG. Удерживай BOOT, нажми RESET, отпусти BOOT — плата войдёт в загрузчик.", Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Найти плату", primary = false, enabled = !Firmware.busy) { devices = Firmware.devices(); device = devices.firstOrNull()?.first ?: -1 }
                Sub(if (devices.isEmpty()) "USB-устройств нет" else "Найдено: ${devices.size}")
            }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                devices.forEach { (id, name) -> Chip(name, id == device) { device = id } }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Записать в плату", enabled = !Firmware.busy && Firmware.info != null && device >= 0) { Firmware.flash(device) }
                if (Firmware.busy) Pill("Отмена", primary = false, danger = true) { Firmware.cancel() }
            }
        }
        if (Firmware.percent >= 0) LinearProgressIndicator(progress = { Firmware.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = C.text, trackColor = C.line)
        if (Firmware.message.isNotBlank()) Sub(Firmware.message, Modifier.padding(top = 8.dp), if (Firmware.message.startsWith("⚠")) C.yellow else C.text)
    }
}

private fun menuName(key: String): String = mapOf(
    "CDCOnBoot" to "USB CDC при запуске", "UploadSpeed" to "Скорость загрузки", "CPUFreq" to "Частота процессора", "FlashSize" to "Размер Flash",
    "FlashMode" to "Режим Flash", "PartitionScheme" to "Разделы памяти", "USBMode" to "Режим USB", "UploadMode" to "Способ загрузки",
    "DebugLevel" to "Отладка", "PSRAM" to "PSRAM", "cpu" to "Процессор", "EraseFlash" to "Стереть Flash", "FlashFreq" to "Частота Flash",
).getOrDefault(key, key)

@Composable
private fun CodeEditor(name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var code by remember(name) { mutableStateOf(Firmware.source(name)) }
    var note by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) note = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(code.toByteArray()) }; "Файл сохранён." }.getOrDefault("⚠ Не удалось сохранить файл.")
    }
    BackHandler { onClose() }
    FCard(pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = C.text) }
            Column(Modifier.weight(1f)) { Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Sub("${code.count { it == '\n' } + 1} строк") }
        }
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Сохранить") { Firmware.saveSource(name, code); note = "Сохранено. Перед записью в плату код нужно собрать заново." }
            Pill("Сбросить", primary = false) { Firmware.resetSource(name); code = Firmware.original(name); note = "Возвращён исходный код." }
            Pill("Экспорт", primary = false) { export.launch(name) }
        }
        if (note.isNotBlank()) Sub(note, Modifier.padding(bottom = 8.dp), if (note.startsWith("⚠")) C.yellow else C.green)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.bg).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            BasicTextField(
                value = code, onValueChange = { code = it },
                textStyle = TextStyle(color = C.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                cursorBrush = SolidColor(C.text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
    }
}

// ====================================================================== оформление
@Composable
fun ThemePage() {
    val ctx = LocalContext.current
    val t = S.theme
    fun set(n: ThemeCfg) { S.theme = n; Themes.apply(n); S.save() }
    var headNote by remember { mutableStateOf("") }
    val importHead = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) headNote = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { HeadArt.importPng(it.readBytes()) } }
            .getOrElse { "Не удалось прочитать файл." }.ifEmpty { "Своя голова загружена." }
    }
    val exportHead = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) headNote = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(HeadArt.stockPng()) }; "Спрайт сохранён." }.getOrDefault("Не удалось сохранить файл.")
    }
    FCard {
        Title("Голова")
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FreddyHead(Modifier.size(110.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (HeadArt.custom) "Своя картинка" else "Пиксельный Фредди", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Sub("Ткни в нос. Своя голова — PNG до 2048 px: либо атлас 180×315 в формате оригинала, либо одна картинка.")
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Загрузить PNG", primary = false) { importHead.launch(arrayOf("image/png", "image/*")) }
            Pill("Шаблон спрайта", primary = false) { exportHead.launch("freddy-sprite.png") }
            if (HeadArt.custom) Pill("Вернуть Фредди", primary = false) { HeadArt.reset(); headNote = "Возвращена голова по умолчанию." }
        }
        if (headNote.isNotBlank()) Sub(headNote, Modifier.padding(top = 8.dp))
    }
    FCard {
        Title("Тема")
        Themes.LIST.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).clickable { set(t.copy(id = p.id)) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.bg)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Dot(Color(p.card), 16.dp); Dot(Color(p.text), 16.dp); Dot(Color(p.accent), 16.dp)
                }
                Text(p.name, Modifier.weight(1f).padding(start = 12.dp), fontSize = 16.sp)
                if (t.id == p.id) Chip("выбрана", color = C.green)
            }
        }
    }
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Своя тема", Modifier.weight(1f))
            if (t.id == "custom") Chip("выбрана", color = C.green)
        }
        Sub("Четыре цвета в формате #RRGGBB; остальные оттенки подбираются сами.", Modifier.padding(top = 4.dp, bottom = 10.dp))
        var bg by remember(t) { mutableStateOf(t.bg) }
        var card by remember(t) { mutableStateOf(t.card) }
        var text by remember(t) { mutableStateOf(t.text) }
        var accent by remember(t) { mutableStateOf(t.accent) }
        @Composable fun color(label: String, value: String, change: (String) -> Unit) {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Themes.parse(value) ?: C.line))
                Spacer(Modifier.width(10.dp))
                Field(value, { change(it.take(7)) }, label)
            }
        }
        color("Фон", bg) { bg = it }
        color("Карточки", card) { card = it }
        color("Текст", text) { text = it }
        color("Акцент и теги", accent) { accent = it }
        val ok = listOf(bg, card, text, accent).all { Themes.parse(it) != null }
        Pill("Применить", Modifier.fillMaxWidth(), enabled = ok) { set(ThemeCfg("custom", bg.trim(), card.trim(), text.trim(), accent.trim())) }
        if (!ok) Sub("Проверь цвета: нужен вид #1C1B1A.", Modifier.padding(top = 6.dp), C.yellow)
    }
}
