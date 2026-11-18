package com.freddyai.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
    )
}

@Composable
private fun Link(text: String, url: String) {
    val ctx = LocalContext.current
    Row(Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = C.text, fontSize = 14.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp), tint = C.dim)
    }
}

// ====================================================================== сервисы
@Composable
fun ServicesScreen() {
    val scope = rememberCoroutineScope()
    var add by remember { mutableStateOf(false) }
    Page {
        ScreenHeader("Сервисы", "Ключи хранятся только на телефоне, в зашифрованном виде.")
        UsageCard()
        FishCard()
        Title("Нейросети", Modifier.padding(top = 6.dp))
        Sub("Запрос идёт в первый подключённый сервис. Если он упёрся в лимит или недоступен — в следующий по списку.")
        S.services.forEachIndexed { i, s -> SvcCard(s, i) }
        FCard(pad = 16.dp) {
            Toggle("Поиск в интернете", "Вопросы о свежих событиях, FNAF и «найди в интернете» сначала ищутся через Groq (нужен его ключ), потом отвечает Фредди.", S.web) { S.web = it; S.save() }
        }
        Pill("Добавить свой сервис", Modifier.fillMaxWidth(), primary = false, icon = Icons.Filled.Add) { add = true }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Проверить все ключи", Modifier.weight(1f), primary = false) {
                scope.launch {
                    S.services.filter { it.key.isNotBlank() || !it.needsKey && it.enabled }.forEach { Llm.check(it) }
                    if (S.fish.key.isNotBlank()) Fish.check()
                }
            }
        }
        TgCard()
    }
    if (add) AddSvcDialog { add = false }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Sub(label)
    }
}

@Composable
private fun UsageCard() {
    val req = S.sum(30) { it.startsWith("llm.") && it.endsWith(".req") }
    val tok = S.sum(30) { it.startsWith("llm.") && it.endsWith(".tok") }
    val bytes = S.sum(30) { it == "tts.bytes" }
    val hits = S.sum(30) { it == "tts.hit" }
    val days = (13 downTo 0).map { d -> S.onDay(d) { it.startsWith("llm.") && it.endsWith(".req") } to S.onDay(d) { it == "tts.req" } }
    FCard {
        Sub("Расход за 30 дней")
        Row(Modifier.padding(top = 10.dp)) {
            Stat("запросов к нейросетям", fmtNum(req), Modifier.weight(1f))
            Stat("токенов", fmtNum(tok), Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 12.dp)) {
            Stat("байт озвучено", fmtNum(bytes), Modifier.weight(1f))
            Stat("фраз из кэша", fmtNum(hits), Modifier.weight(1f))
        }
        val max = (days.maxOfOrNull { it.first + it.second } ?: 0L).coerceAtLeast(1L)
        Canvas(Modifier.fillMaxWidth().padding(top = 16.dp).height(64.dp)) {
            val w = size.width / days.size
            days.forEachIndexed { i, (a, b) ->
                val ha = size.height * a / max; val hb = size.height * b / max
                val x = i * w + w * 0.2f; val bw = w * 0.6f
                drawRoundRect(C.line, Offset(x, size.height - 3f), Size(bw, 3f), CornerRadius(2f))
                if (a > 0) drawRoundRect(C.blue, Offset(x, size.height - ha), Size(bw, ha), CornerRadius(4f))
                if (b > 0) drawRoundRect(C.yellow, Offset(x, size.height - ha - hb), Size(bw, hb), CornerRadius(4f))
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(C.blue); Sub("  нейросети    "); Dot(C.yellow); Sub("  озвучка · 14 дней")
        }
    }
}

@Composable
private fun FishCard() {
    val scope = rememberCoroutineScope()
    val f = S.fish
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(statusColor(f.status)); Spacer(Modifier.width(10.dp))
            Title("Fish Audio", Modifier.weight(1f))
            Chip("озвучка")
        }
        if (f.statusText.isNotBlank()) Sub(f.statusText, Modifier.padding(top = 6.dp), if (f.status == 2) C.yellow else C.dim)
        if (f.credit.isNotBlank()) Sub(f.credit)
        Spacer(Modifier.height(10.dp))
        Field(f.key, { S.fish = f.copy(key = it.trim(), status = 0, statusText = "", credit = "") }, "API-ключ", secret = true)
        Sub("Модель", Modifier.padding(top = 12.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Fish.MODELS.forEach { (id, _) -> Chip(id, f.model == id) { S.fish = S.fish.copy(model = id); S.save() } }
        }
        Sub(Fish.MODELS.first { it.first == f.model }.second + ". Если платная модель откажет, запрос повторится на бесплатной.", Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Проверить", enabled = f.key.isNotBlank()) { S.save(); scope.launch { Fish.check() } }
            Link("Получить ключ", "https://fish.audio/app/api-keys/")
        }
    }
}

@Composable
private fun SvcCard(s: Svc, index: Int) {
    val scope = rememberCoroutineScope()
    var models by remember(s.id) { mutableStateOf<List<String>?>(null) }
    var loading by remember(s.id) { mutableStateOf(false) }
    val today = S.onDay(0) { it == "llm.${s.id}.req" }
    val usable = Llm.usable(s)
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (!usable) C.line else statusColor(s.status)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Sub(if (!s.enabled) "выключен" else if (!usable) "нет ключа" else "№${S.services.filter { Llm.usable(it) }.indexOf(s) + 1} в цепочке · сегодня запросов: $today")
            }
            IconButton(onClick = { if (index > 0) { S.services.add(index - 1, S.services.removeAt(index)); S.save() } }, enabled = index > 0) {
                Icon(Icons.Filled.ArrowUpward, "Выше", tint = if (index > 0) C.text else C.line)
            }
            IconButton(onClick = { if (index < S.services.size - 1) { S.services.add(index + 1, S.services.removeAt(index)); S.save() } }, enabled = index < S.services.size - 1) {
                Icon(Icons.Filled.ArrowDownward, "Ниже", tint = if (index < S.services.size - 1) C.text else C.line)
            }
        }
        if (s.statusText.isNotBlank()) Sub(s.statusText, Modifier.padding(top = 6.dp), if (s.status == 2) C.yellow else C.dim)
        if (s.limits.isNotBlank()) Sub(s.limits)
        Spacer(Modifier.height(6.dp))
        Toggle("Использовать", checked = s.enabled) { v -> S.setSvc(s.id) { it.copy(enabled = v) }; S.save() }
        if (s.custom || !s.needsKey) Field(s.baseUrl, { v -> S.setSvc(s.id) { it.copy(baseUrl = v.trim(), status = 0, statusText = "") } }, "Адрес API", Modifier.padding(bottom = 8.dp))
        if (s.needsKey) Field(s.key, { v -> S.setSvc(s.id) { it.copy(key = v.trim(), status = 0, statusText = "") } }, "API-ключ", Modifier.padding(bottom = 8.dp), secret = true)
        Field(s.model, { v -> S.setSvc(s.id) { it.copy(model = v.trim()) } }, "Модель")
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (loading) "…" else "Проверить", enabled = !loading && (s.key.isNotBlank() || !s.needsKey)) {
                S.save(); loading = true
                scope.launch { Llm.check(s); loading = false }
            }
            Pill("Модели", primary = false, enabled = !loading && (s.key.isNotBlank() || !s.needsKey)) {
                loading = true
                scope.launch {
                    try { models = Llm.models(s) } catch (e: Exception) { S.setSvc(s.id) { it.copy(status = 2, statusText = errorText(e)) } }
                    loading = false
                }
            }
            if (s.custom) IconButton(onClick = { S.services.removeAll { it.id == s.id }; S.save() }) { Icon(Icons.Filled.DeleteOutline, "Удалить", tint = C.red) }
        }
        if (s.keyUrl.isNotBlank()) Link("Получить ключ", s.keyUrl)
    }
    models?.let { list ->
        var q by remember { mutableStateOf("") }
        FDialog("Модели ${s.name}", { models = null }) {
            Field(q, { q = it }, "Поиск")
            list.filter { q.isBlank() || it.contains(q.trim(), true) }.take(80).forEach { id ->
                Row(Modifier.fillMaxWidth().clickable { S.setSvc(s.id) { it.copy(model = id) }; S.save(); models = null }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(id, Modifier.weight(1f), fontSize = 15.sp)
                    if (id == s.model) Icon(Icons.Filled.Check, null, tint = C.green)
                }
            }
        }
    }
}

@Composable
private fun AddSvcDialog(onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    FDialog("Свой сервис", onClose) {
        Sub("Подойдёт любой сервис с OpenAI-совместимым API: адрес заканчивается на /v1.")
        Field(name, { name = it }, "Название")
        Field(url, { url = it }, "Адрес API")
        Field(key, { key = it }, "API-ключ (если нужен)", secret = true)
        Field(model, { model = it }, "Модель")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Отмена", Modifier.weight(1f), primary = false, onClick = onClose)
            Pill("Добавить", Modifier.weight(1f), enabled = name.isNotBlank() && url.trim().startsWith("http") && model.isNotBlank()) {
                S.services += Svc("c" + System.currentTimeMillis(), name.trim(), url.trim(), model.trim(), key = key.trim(), custom = true, needsKey = key.isNotBlank())
                S.save(); onClose()
            }
        }
    }
}

@Composable
private fun TgCard() {
    val t = S.tg
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (t.on && Tg.status == "Работает") C.green else if (t.on) C.yellow else C.line); Spacer(Modifier.width(10.dp))
            Title("Telegram-бот", Modifier.weight(1f)); Chip("связка")
        }
        Sub("Пиши Фредди из Telegram и получай ответы текстом и голосом. Работает, пока приложение открыто. Состояние: ${Tg.status}.", Modifier.padding(vertical = 8.dp))
        Field(t.token, { S.tg = t.copy(token = it.trim()) }, "Токен бота от @BotFather", secret = true)
        Spacer(Modifier.height(8.dp))
        Field(t.allowed, { S.tg = t.copy(allowed = it.trim()) }, "Твой Telegram ID", number = true)
        Sub("Бот отвечает только этому ID. Не знаешь его — включи бота и напиши ему: он пришлёт ID.", Modifier.padding(top = 6.dp))
        Toggle("Отвечать голосом", checked = t.voice) { S.tg = S.tg.copy(voice = it); S.save() }
        Toggle("Бот включён", checked = t.on) { on ->
            S.tg = S.tg.copy(on = on); S.save()
            if (on) Tg.start() else Tg.stop()
        }
    }
}

// ====================================================================== голос
@Composable
fun VoiceScreen() {
    val scope = rememberCoroutineScope()
    val f = S.fish
    var test by rememberSaveable { mutableStateOf("[chuckling] Надеюсь, ты рад моему визиту? [break] У меня специальная доставка только для тебя.") }
    var cache by remember { mutableStateOf(0L) }
    LaunchedEffect(Speaker.speaking) { cache = Speaker.cacheSize() }
    Page {
        ScreenHeader("Голос", "Озвучка Fish Audio и распознавание речи.")
        FCard {
            Sub("Текущий голос")
            Text(S.profile.voiceName.ifBlank { if (S.profile.voiceId.isBlank()) "Голос модели по умолчанию" else "Свой голос" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Field(S.profile.voiceId, { v -> S.setProfile { it.copy(voiceId = v.trim(), voiceName = "") } }, "ID голоса (можно вставить вручную)", Modifier.padding(top = 10.dp))
            Field(test, { test = it }, "Проверочная фраза", Modifier.padding(top = 10.dp), singleLine = false, minLines = 2)
            Text(tagged(test), Modifier.padding(top = 8.dp), fontSize = 14.sp, color = C.dim)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (Speaker.speaking) "Стоп" else "Озвучить", icon = if (Speaker.speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow) {
                    S.save()
                    if (Speaker.speaking) Speaker.stop() else if (S.fish.tts) Speaker.say(test) else { S.fish = S.fish.copy(tts = true); Speaker.say(test) }
                }
            }
            if (Speaker.lastError.isNotBlank()) Sub("⚠ ${Speaker.lastError} Говорит системный голос.", Modifier.padding(top = 8.dp), C.yellow)
            if (f.key.isBlank()) Sub("Без ключа Fish Audio говорит системный голос телефона.", Modifier.padding(top = 8.dp))
        }
        FCard {
            Toggle("Озвучивать ответы", checked = f.tts) { S.fish = S.fish.copy(tts = it); if (!it) Speaker.stop(); S.save() }
            Toggle("Теги эмоций", "Нейросеть сама расставляет [laughing], [whispering], [break] — голос звучит живее.", f.tags) { S.fish = S.fish.copy(tags = it); S.save() }
            Toggle("Показывать теги в чате", checked = f.showTags) { S.fish = S.fish.copy(showTags = it); S.save() }
            Toggle("Кэш озвучки", "Повторные фразы играют с телефона и не тратят лимит. Занято: ${cache / 1024 / 1024} МБ.", f.cache) { S.fish = S.fish.copy(cache = it); S.save() }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Sub("Громкость ${Math.round(f.volume * 100)}%", Modifier.width(110.dp))
                Slider(f.volume, { S.fish = S.fish.copy(volume = (Math.round(it * 20) / 20f)) }, Modifier.weight(1f), valueRange = 0f..2f, onValueChangeFinished = { S.save() },
                    colors = SliderDefaults.colors(thumbColor = C.text, activeTrackColor = C.text, inactiveTrackColor = C.line))
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Sub("Скорость ${"%.2f".format(f.speed)}×", Modifier.width(110.dp))
                Slider(f.speed, { S.fish = S.fish.copy(speed = (Math.round(it * 20) / 20f)) }, Modifier.weight(1f), valueRange = 0.5f..2f, onValueChangeFinished = { S.save() },
                    colors = SliderDefaults.colors(thumbColor = C.text, activeTrackColor = C.text, inactiveTrackColor = C.line))
            }
            Pill("Очистить кэш", Modifier.padding(top = 6.dp), primary = false) { Speaker.clearCache(); cache = 0 }
        }
        VoiceLibrary()
        SttCard()
    }
}

@Composable
private fun VoiceLibrary() {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var mine by rememberSaveable { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var state by remember { mutableStateOf("") }
    fun load() {
        if (S.fish.key.isBlank()) { state = "Для библиотеки нужен ключ Fish Audio (вкладка «Сервисы»)."; return }
        state = "Загружаю…"
        scope.launch {
            state = try { list = Fish.voices(q, mine); if (list.isEmpty()) "Ничего не найдено." else "" } catch (e: Exception) { errorText(e) }
        }
    }
    FCard {
        Title("Библиотека голосов")
        Field(q, { q = it }, "Название голоса", Modifier.padding(top = 10.dp), trailing = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Search, "Найти", tint = C.text) } })
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Все русские", !mine) { mine = false; load() }
            Chip("Мои голоса", mine) { mine = true; load() }
        }
        if (state.isNotBlank()) Sub(state, Modifier.padding(top = 10.dp))
        list.forEach { v ->
            val chosen = S.profile.voiceId == v.id
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { SamplePlayer.toggle(v.sample) }, enabled = v.sample.isNotBlank()) {
                    Icon(if (SamplePlayer.playing == v.sample && v.sample.isNotBlank()) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Образец", tint = if (v.sample.isBlank()) C.line else C.text)
                }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(v.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Sub(listOf(if (v.author.isNotBlank()) "@${v.author}" else "", "использований: ${fmtNum(v.uses)}").filter { it.isNotBlank() }.joinToString(" · "))
                    if (v.tags.isNotEmpty()) Sub(v.tags.joinToString(" · "), color = C.purple)
                }
                Pill(if (chosen) "Выбран" else "Выбрать", primary = !chosen, enabled = !chosen) {
                    SamplePlayer.toggle("")
                    S.setProfile { it.copy(voiceId = v.id, voiceName = v.title) }; S.save()
                }
            }
        }
    }
}

@Composable
private fun SttCard() {
    val s = S.stt
    FCard {
        Title("Распознавание речи")
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Системное", s.mode == "system") { S.stt = s.copy(mode = "system"); S.save() }
            Chip("Whisper (Groq)", s.mode == "whisper") { S.stt = s.copy(mode = "whisper"); S.save() }
            Chip("Vosk офлайн", s.mode == "vosk") { S.stt = s.copy(mode = "vosk"); S.save() }
        }
        Sub(when (s.mode) {
            "whisper" -> "Самое точное. Запись уходит в Groq, нужен его ключ. Останавливается после паузы в речи."
            "vosk" -> "Работает без интернета, запись не покидает телефон. Нужна модель ниже."
            else -> "Распознаватель Google, встроенный в телефон. Ключ не нужен."
        }, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Text("Модель Vosk · русская, 45 МБ", fontSize = 16.sp)
        when {
            Vosk.progress >= 0 -> {
                Sub(if (Vosk.progress > 100) "Распаковываю…" else "Скачиваю: ${Vosk.progress}%", Modifier.padding(vertical = 6.dp))
                LinearProgressIndicator(progress = { (Vosk.progress.coerceAtMost(100)) / 100f }, modifier = Modifier.fillMaxWidth(), color = C.text, trackColor = C.line)
                Pill("Отмена", Modifier.padding(top = 10.dp), primary = false) { Vosk.cancel() }
            }
            Vosk.installed -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(C.green); Sub("установлена", Modifier.weight(1f))
                Pill("Удалить", primary = false, danger = true) { if (S.stt.mode == "vosk") S.stt = S.stt.copy(mode = "system"); S.stt = S.stt.copy(wake = false); Vosk.remove(); S.save() }
            }
            else -> {
                Sub("Модель больше не вшита в приложение — скачивается один раз.", Modifier.padding(vertical = 6.dp))
                Pill("Скачать модель") { Vosk.install() }
            }
        }
        if (Vosk.error.isNotBlank()) Sub("⚠ ${Vosk.error}", Modifier.padding(top = 6.dp), C.yellow)
        Spacer(Modifier.height(8.dp))
        Toggle("Слово-активация", if (Vosk.installed) "Скажи «${S.profile.wake}» — и Фредди начнёт слушать. Работает, пока открыт чат." else "Сначала скачай модель Vosk.", s.wake && Vosk.installed) {
            if (Vosk.installed) { S.stt = S.stt.copy(wake = it); S.save() }
        }
        Field(S.profile.wake, { v -> S.setProfile { it.copy(wake = v) } }, "Слово для активации")
        Toggle("Не гасить экран", "Удобно, когда телефон стоит как голова Фредди.", s.awake) { S.stt = S.stt.copy(awake = it); S.save() }
    }
}

// ====================================================================== быстрые фразы
@Composable
fun PhrasesScreen(openChat: () -> Unit) {
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<Phrase?>(null) }
    var caching by remember { mutableStateOf("") }
    Page {
        ScreenHeader("Быстрые фразы", "Готовые реплики в одно касание. Озвученные один раз хранятся в кэше и не тратят лимит.")
        FCard(pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Музыкальная шкатулка", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Sub("Мелодия из оригинала. Голосом: «сыграй мелодию», остановить — «стоп».")
                }
                IconButton(onClick = { MusicBox.toggle() }) { Icon(if (MusicBox.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Шкатулка", tint = C.text) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Новая фраза", Modifier.weight(1f), icon = Icons.Filled.Add) { edit = Phrase("", "", "") }
            Pill(if (caching.isNotBlank()) caching else "Озвучить заранее", Modifier.weight(1f), primary = false, enabled = caching.isBlank() && S.fish.key.isNotBlank()) {
                scope.launch {
                    val list = S.phrases.filter { !it.ask }
                    list.forEachIndexed { i, p -> caching = "${i + 1} из ${list.size}"; Speaker.precache(p.text) }
                    caching = ""
                }
            }
        }
        S.phrases.forEach { p ->
            val cached = !p.ask && Speaker.isCached(p.text)
            FCard(pad = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(tagged(p.text), fontSize = 14.sp, color = C.dim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(if (p.ask) "вопрос нейросети" else "произнести", color = if (p.ask) C.blue else C.orange)
                            if (cached) Chip("в кэше", color = C.green)
                        }
                    }
                    IconButton(onClick = { edit = p }) { Icon(Icons.Filled.Edit, "Изменить", tint = C.dim) }
                    IconButton(onClick = { runPhrase(p); if (p.ask) openChat() }) { Icon(Icons.Filled.PlayArrow, "Запустить", tint = C.text) }
                }
            }
        }
    }
    edit?.let { p ->
        var title by remember(p) { mutableStateOf(p.title) }
        var text by remember(p) { mutableStateOf(p.text) }
        var ask by remember(p) { mutableStateOf(p.ask) }
        FDialog(if (p.id.isEmpty()) "Новая фраза" else "Фраза", { edit = null }) {
            Field(title, { title = it }, "Название кнопки")
            Field(text, { text = it }, "Текст", singleLine = false, minLines = 3)
            Sub("Теги: [laughing] [chuckling] [whispering] [sighing] [excited] [angry] [emphasis] [break] [long-break]")
            Toggle("Отправлять нейросети", "Выключено — Фредди произносит текст как есть.", ask) { ask = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.id.isNotEmpty()) Pill("Удалить", primary = false, danger = true) { S.phrases.removeAll { it.id == p.id }; S.save(); edit = null }
                Pill("Сохранить", Modifier.weight(1f), enabled = title.isNotBlank() && text.isNotBlank()) {
                    val n = Phrase(p.id.ifEmpty { "p" + System.currentTimeMillis() }, title.trim(), text.trim(), ask)
                    val i = S.phrases.indexOfFirst { it.id == n.id }
                    if (i >= 0) S.phrases[i] = n else S.phrases += n
                    S.save(); edit = null
                }
            }
        }
    }
}
