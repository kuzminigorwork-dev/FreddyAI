package com.freddyai.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.material.icons.filled.Face
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        S.init(applicationContext)
        Vosk.refresh()
        Themes.apply(S.theme)
        HeadArt.load()
        Firmware.loadInfo()
        Usb.watch()
        if (S.tg.on) Tg.start()
        Updater.autoCheck()
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = C.text, onPrimary = C.ink, background = C.bg, onBackground = C.text,
                surface = C.card, onSurface = C.text, surfaceVariant = C.raised, onSurfaceVariant = C.dim, outline = C.line, error = C.red,
            )) {
                Surface(Modifier.fillMaxSize(), color = C.bg, contentColor = C.text) { Root() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.refresh()
    }

    override fun onStop() {
        super.onStop()
        Wake.stop(); Ears.cancel()
        S.save()
    }
}

private class Tab(val title: String, val icon: ImageVector)

@Composable
fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val view = LocalView.current
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(S.stt.awake) { view.keepScreenOn = S.stt.awake }
    val light = C.light
    // Значки строки состояния должны оставаться читаемыми и на светлой теме.
    SideEffect {
        activity?.window?.let { w -> WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light } }
    }
    if (Nav.talk) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { TalkScreen { Nav.talk = false } }
        return
    }
    val tabs = remember {
        listOf(Tab("Чат", Icons.AutoMirrored.Filled.Chat), Tab("Голос", Icons.Filled.GraphicEq), Tab("Сервисы", Icons.Filled.Hub),
            Tab("Фразы", Icons.Filled.Bolt), Tab("Ещё", Icons.Filled.MoreHoriz))
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ChatScreen()
                1 -> VoiceScreen()
                2 -> ServicesScreen()
                3 -> PhrasesScreen { tab = 0 }
                else -> MoreScreen()
            }
        }
        Row(Modifier.fillMaxWidth().background(C.card).padding(horizontal = 6.dp, vertical = 6.dp)) {
            tabs.forEachIndexed { i, t ->
                val on = i == tab
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { tab = i }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, t.title, Modifier.size(22.dp), tint = if (on) C.text else C.dim)
                    Text(t.title, fontSize = 11.sp, color = if (on) C.text else C.dim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

// ====================================================================== чат
@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var input by rememberSaveable { mutableStateOf("") }
    var hasMic by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasMic = ok
        if (ok) Ears.start { Brain.send(it) }
    }
    fun mic() {
        when {
            Ears.listening -> Ears.stop()
            hasMic -> Ears.start { Brain.send(it) }
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun send() { val t = input.trim(); if (t.isNotEmpty() && !Brain.busy) { input = ""; Brain.send(t) } }

    // Слово-активация работает, пока Фредди молчит и не слушает команду.
    val wakeOn = S.stt.wake && Vosk.installed && hasMic && !Speaker.speaking && !Ears.listening && !Brain.busy
    val wakeWord = S.profile.wake
    DisposableEffect(wakeOn, wakeWord) {
        if (wakeOn) Wake.start(wakeWord) { rest ->
            Wake.stop()
            if (rest.length > 2) Brain.send(rest) else Ears.start { Brain.send(it) }
        }
        onDispose { Wake.stop() }
    }

    val chain = S.services.filter { Llm.usable(it) }
    val status = when {
        Ears.listening -> "Слушаю…"
        Brain.busy -> "Думаю" + if (Brain.via.isNotBlank()) " · ${Brain.via}" else "…"
        Speaker.speaking -> "Говорю"
        chain.isEmpty() -> "Нейросеть не подключена"
        else -> chain.joinToString(" → ") { it.name } + if (Wake.active) " · жду «${S.profile.wake}»" else ""
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FreddyHead(Modifier.size(52.dp).clickable { Brain.stop(); Ears.cancel(); Nav.talk = true }, interactive = false)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(S.profile.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { Brain.stop(); Ears.cancel(); Nav.talk = true }) { Icon(Icons.Filled.Face, "Разговор с головой", tint = C.text) }
            IconButton(onClick = { if (S.fish.tts) Speaker.stop(); S.fish = S.fish.copy(tts = !S.fish.tts); S.save() }) {
                Icon(if (S.fish.tts) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Озвучка", tint = if (S.fish.tts) C.text else C.dim)
            }
            IconButton(onClick = { Brain.stop(); S.messages.clear(); S.save() }) { Icon(Icons.Filled.DeleteOutline, "Очистить чат", tint = C.dim) }
        }

        UpdateBanner()

        if (S.messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                FreddyHead(Modifier.size(200.dp))
                Spacer(Modifier.height(18.dp))
                Text(if (chain.isEmpty()) "Сначала подключи нейросеть" else "Скажи или напиши что-нибудь", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Sub(
                    if (chain.isEmpty()) "Открой вкладку «Сервисы» и вставь ключ Groq — он бесплатный."
                    else "Команды без интернета: «который час», «запомни …», «что ты помнишь», «погода», «стоп».",
                    Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(S.messages.asReversed(), key = { it.id }) { m -> Bubble(m) { clip.setText(AnnotatedString(Reply.stripTags(m.text))) } }
            }
        }

        if (Ears.listening || Ears.error.isNotBlank() || Speaker.lastError.isNotBlank()) {
            val err = Ears.error.ifBlank { if (Ears.listening) "" else Speaker.lastError }
            Text(
                if (err.isNotBlank()) "⚠ $err" else "🎙 " + Ears.partial.ifBlank { "говори…" },
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp), color = if (err.isNotBlank()) C.yellow else C.teal, fontSize = 13.sp, maxLines = 2,
            )
        }

        if (S.phrases.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                S.phrases.forEach { p -> Chip(p.title, onClick = { runPhrase(p) }) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(C.card).padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (input.isEmpty()) Text("Сообщение для ${S.profile.name}", color = C.dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), maxLines = 5,
                    textStyle = TextStyle(color = C.text, fontSize = 16.sp), cursorBrush = SolidColor(C.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                )
            }
            Spacer(Modifier.width(8.dp))
            Round(Icons.Filled.Mic, "Микрофон", if (Ears.listening) C.teal else C.raised, if (Ears.listening) C.ink else C.text) { mic() }
            Spacer(Modifier.width(8.dp))
            if (Brain.busy || Speaker.speaking) Round(Icons.Filled.Stop, "Стоп", C.text, C.ink) { Brain.stop() }
            else Round(Icons.AutoMirrored.Filled.Send, "Отправить", C.text, C.ink) { send() }
        }
    }
}

fun runPhrase(p: Phrase) {
    if (p.ask) Brain.send(p.text) else {
        Speaker.stop()
        S.addMsg(false, p.text, "фраза")
        Speaker.say(p.text)
    }
}

@Composable
private fun Round(icon: ImageVector, desc: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.size(50.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(22.dp), tint = fg)
    }
}

@Composable
private fun Bubble(m: Msg, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp)).background(if (m.user) C.raised else C.card)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onCopy)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (m.text.isEmpty()) Text("…", color = C.dim, fontSize = 16.sp)
            else Text(if (m.user) AnnotatedString(m.text) else tagged(m.text, S.fish.showTags), fontSize = 16.sp, lineHeight = 22.sp, color = if (m.err) C.yellow else C.text)
            if (!m.user && m.text.isNotEmpty() && !m.err) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m.via.isNotBlank()) Text(m.via, color = C.dim, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Replay, "Повторить озвучку", Modifier.size(18.dp).clip(CircleShape).clickable { Speaker.stop(); Speaker.say(Reply.parse(m.text).speech) }, tint = C.dim)
                }
            }
        }
    }
}
