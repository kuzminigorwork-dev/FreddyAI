package com.freddyai.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Карточка «Доступна новая версия»: в чате (пока не скрыта) и в разделе «Ещё» (всегда). */
@Composable
fun UpdateBanner(force: Boolean = false) {
    val ctx = LocalContext.current
    val rel = Updater.available ?: return
    if (!force && Updater.dismissed == rel.version && Updater.progress < 0) return
    FCard(Modifier.padding(horizontal = if (force) 0.dp else 14.dp, vertical = if (force) 0.dp else 6.dp), pad = 16.dp) {
        Text("Доступна версия ${rel.version}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (rel.notes.isNotBlank()) Sub(rel.notes.take(400), Modifier.padding(top = 4.dp))
        if (Updater.progress in 0..99) {
            LinearProgressIndicator(progress = { Updater.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = C.text, trackColor = C.line)
            Sub(Updater.message, Modifier.padding(top = 6.dp))
            Pill("Отмена", Modifier.padding(top = 8.dp), primary = false) { Updater.cancel() }
            return@FCard
        }
        if (!Updater.canInstall()) Sub("Один раз разреши FreddyAI устанавливать приложения — дальше обновления ставятся сами.", Modifier.padding(top = 6.dp), C.yellow)
        if (Updater.message.isNotBlank() && Updater.message.startsWith("⚠")) Sub(Updater.message, Modifier.padding(top = 6.dp), C.yellow)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!Updater.canInstall()) Pill("Разрешить") { runCatching { ctx.startActivity(Updater.allowInstallIntent()) } }
            else Pill("Обновить") { Updater.download() }
            if (!force) Pill("Позже", primary = false) { Updater.dismissed = rel.version }
        }
    }
}
