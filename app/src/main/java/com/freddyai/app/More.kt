package com.freddyai.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun MoreScreen() {
    var page by rememberSaveable { mutableStateOf("") }
    BackHandler(page.isNotEmpty()) { page = "" }
    val items = listOf(
        Triple("profile", "Персонаж и память", "Характер, имя, что Фредди о тебе знает"),
        Triple("vision", "Зрение", "Камера, предметы и лица, журнал наблюдений"),
        Triple("board", "Плата и прошивка", "Глаза по USB или Wi-Fi, код платы, запись прошивки"),
        Triple("theme", "Оформление", "Четыре темы и своя"),
        Triple("backup", "Перенос и резервная копия", "Файл или QR-код с паролем"),
        Triple("place", "Место и погода", "Координаты для команды «погода»"),
    )
    Page {
        if (page.isEmpty()) {
            ScreenHeader("Ещё", "Персонажи, зрение, плата, оформление, перенос настроек.")
            items.forEach { (id, title, sub) ->
                FCard(Modifier.clickable { page = id }, pad = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Sub(sub) }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                    }
                }
            }
            AboutCard()
        } else {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = C.text) }
                Text(items.first { it.first == page }.second, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            when (page) {
                "profile" -> { ProfilesCard(); MemoryCard() }
                "vision" -> VisionPage()
                "board" -> BoardPage()
                "theme" -> ThemePage()
                "backup" -> BackupCard()
                else -> PlaceCard()
            }
        }
    }
}

@Composable
private fun ProfilesCard() {
    var edit by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    val p = S.profile
    FCard {
        Title("Персонаж")
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            S.profiles.forEach { x -> Chip(x.name, x.id == S.profileId) { Brain.stop(); S.profileId = x.id; S.save() } }
            Chip("+ новый") { create = true }
        }
        Sub(p.persona, Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Изменить", primary = false) { edit = true }
            if (p.id != "freddy") Pill("Удалить", primary = false, danger = true) { S.profiles.removeAll { it.id == p.id }; S.profileId = "freddy"; S.save() }
        }
        Sub("У каждого персонажа свой характер, голос, слово-активация и память.", Modifier.padding(top = 10.dp))
    }
    if (edit) {
        var name by remember { mutableStateOf(p.name) }
        var persona by remember { mutableStateOf(p.persona) }
        var user by remember { mutableStateOf(p.userName) }
        FDialog("Персонаж", { edit = false }) {
            Field(name, { name = it }, "Имя")
            Field(user, { user = it }, "Как обращаться к тебе")
            Field(persona, { persona = it }, "Характер (промпт)", singleLine = false, minLines = 5)
            Sub("Эмоция в начале ответа ([весёлый], [злой], [жуткий]…) меняет цвет глаз.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Сбросить", primary = false) { persona = DEFAULT_PERSONA }
                Pill("Сохранить", Modifier.weight(1f), enabled = name.isNotBlank()) {
                    S.setProfile { it.copy(name = name.trim(), persona = persona.trim(), userName = user.trim()) }; S.save(); edit = false
                }
            }
        }
    }
    if (create) {
        var name by remember { mutableStateOf("") }
        FDialog("Новый персонаж", { create = false }) {
            Field(name, { name = it }, "Имя")
            Sub("Характер и голос можно настроить после создания.")
            Pill("Создать", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                val id = "u" + System.currentTimeMillis()
                S.profiles += Profile(id, name.trim(), "Ты — ${name.trim()}. Говори по-русски, коротко, 1–3 предложения. В начале каждого ответа одна эмоция: [нормальный], [весёлый], [жуткий], [добрый], [злой], [подозрительный].", wake = name.trim().lowercase())
                Brain.stop(); S.profileId = id; S.save(); create = false
            }
        }
    }
}

@Composable
private fun MemoryCard() {
    var fact by rememberSaveable { mutableStateOf("") }
    val p = S.profile
    FCard {
        Title("Память")
        Sub("То, что ${p.name} знает о тебе. Можно добавить голосом: «запомни …».", Modifier.padding(top = 4.dp))
        p.memory.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f, Modifier.weight(1f), fontSize = 15.sp)
                IconButton(onClick = { S.setProfile { it.copy(memory = it.memory - f) }; S.save() }) { Icon(Icons.Filled.Close, "Забыть", tint = C.dim) }
            }
        }
        Field(fact, { fact = it }, "Новый факт", Modifier.padding(top = 10.dp), trailing = {
            IconButton(onClick = { S.remember(fact); fact = ""; S.save() }, enabled = fact.isNotBlank()) { Icon(Icons.Filled.Add, "Добавить", tint = C.text) }
        })
    }
}

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 720, 720, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

@Composable
private fun BackupCard() {
    val ctx = LocalContext.current
    var pass by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<Bitmap?>(null) }
    val okPass = pass.length >= 4

    fun import(text: String) {
        note = try {
            S.restore(Backup.unpack(text, pass), own = false); S.save()
            "Настройки загружены."
        } catch (e: Exception) { "⚠ " + (e.message ?: "Не удалось прочитать данные.") }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) note = try {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Backup.pack(S.snapshot(full = false, keys = true), pass).toByteArray()) }
            "Копия сохранена. Без пароля её не открыть."
        } catch (e: Exception) { "⚠ Не удалось сохранить файл." }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            import(ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) })
        } catch (e: Exception) { note = "⚠ Не удалось прочитать файл." }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { import(it) } }

    FCard {
        Title("Перенос и резервная копия")
        Sub("Ключи, персонажи, фразы и настройки — одним файлом или QR-кодом. Данные шифруются твоим паролем.", Modifier.padding(top = 4.dp))
        Field(pass, { pass = it }, "Пароль копии (не короче 4 символов)", Modifier.padding(top = 10.dp), secret = true)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("В файл", Modifier.weight(1f), enabled = okPass) { saveFile.launch("freddy-backup.fai") }
            Pill("Из файла", Modifier.weight(1f), primary = false, enabled = okPass) { openFile.launch(arrayOf("*/*")) }
        }
        Sub("Чтобы хранить копию в облаке, при сохранении выбери Google Диск.", Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Показать QR", Modifier.weight(1f), primary = false, icon = Icons.Filled.QrCode2, enabled = okPass) {
                val text = Backup.pack(S.snapshot(full = false, keys = true, slim = true), pass)
                if (text.length > 2300) note = "⚠ Слишком много данных для QR-кода. Используй файл."
                else try { code = qr(text); note = "" } catch (e: Exception) { note = "⚠ Не удалось построить QR-код." }
            }
            Pill("Сканировать", Modifier.weight(1f), primary = false, icon = Icons.Filled.QrCodeScanner, enabled = okPass) {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведи на QR-код FreddyAI").setBeepEnabled(false).setOrientationLocked(false))
            }
        }
        if (note.isNotBlank()) Sub(note, Modifier.padding(top = 10.dp), if (note.startsWith("⚠")) C.yellow else C.green)
    }
    code?.let { bmp ->
        FDialog("QR для переноса", { code = null }) {
            Image(bmp.asImageBitmap(), "QR-код", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White))
            Sub("На втором телефоне: Ещё → введи тот же пароль → «Сканировать». В QR входят ключи сервисов и текущий персонаж; фразы и память переносятся файлом.")
            Pill("Готово", Modifier.fillMaxWidth()) { code = null }
        }
    }
}

@Composable
private fun PlaceCard() {
    val g = S.geo
    FCard {
        Title("Место и погода")
        Sub("Нужно для команды «погода». Координаты своего города можно посмотреть в любых картах.", Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(g.lat, { S.geo = g.copy(lat = it.trim()) }, "Широта", Modifier.weight(1f))
            Field(g.lon, { S.geo = g.copy(lon = it.trim()) }, "Долгота", Modifier.weight(1f))
        }
    }
}

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FreddyAI", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Sub("  ${Updater.current} · нативная версия")
        }
        Sub("Переписано с нуля на Kotlin по исходникам FreddyAI 1.9.10 (лицензия MIT). Обновления приходят с GitHub: ${Updater.REPO}.", Modifier.padding(top = 6.dp))
        Toggle("Проверять обновления", "При запуске, не чаще раза в 6 часов.", S.autoUpdate) { S.autoUpdate = it; S.save() }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(if (Updater.checking) "Проверяю…" else "Проверить сейчас", primary = false, enabled = !Updater.checking) { Updater.check() }
            Pill("GitHub", primary = false) { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${Updater.REPO}/releases"))) } }
        }
        if (Updater.message.isNotBlank() && Updater.available == null) Sub(Updater.message, Modifier.padding(top = 8.dp), if (Updater.message.startsWith("⚠")) C.yellow else C.dim)
    }
    UpdateBanner(force = true)
}
