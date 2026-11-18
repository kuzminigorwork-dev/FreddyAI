package com.freddyai.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Палитра по мотивам интерфейса Fish Audio; меняется вместе с темой оформления. */
object C {
    var bg by mutableStateOf(Color(0xFF1C1B1A))
    var card by mutableStateOf(Color(0xFF111010))
    var raised by mutableStateOf(Color(0xFF3A3532))
    var popup by mutableStateOf(Color(0xFF2A2725))
    var line by mutableStateOf(Color(0xFF34302D))
    var text by mutableStateOf(Color(0xFFF5F3F0))
    var dim by mutableStateOf(Color(0xFFA39E99))
    var purple by mutableStateOf(Color(0xFFA78BFA))
    /** Цвет надписи на кнопке, залитой цветом текста. */
    var ink by mutableStateOf(Color(0xFF000000))
    var light by mutableStateOf(false)
    val orange = Color(0xFFF97316)
    val blue = Color(0xFF3B82F6)
    val teal = Color(0xFF14B8A6)
    val yellow = Color(0xFFEAB308)
    val red = Color(0xFFF43F5E)
    val green = Color(0xFF22C55E)
}

class ThemePreset(val id: String, val name: String, val bg: Long, val card: Long, val text: Long, val accent: Long)

object Themes {
    val LIST = listOf(
        ThemePreset("fish", "Тёплая тёмная", 0xFF1C1B1A, 0xFF111010, 0xFFF5F3F0, 0xFFA78BFA),
        ThemePreset("black", "Чёрная", 0xFF000000, 0xFF0E0E0E, 0xFFFFFFFF, 0xFFA78BFA),
        ThemePreset("night", "Синяя ночь", 0xFF0F172A, 0xFF0B1120, 0xFFE2E8F0, 0xFF38BDF8),
        ThemePreset("light", "Светлая", 0xFFF4F1EC, 0xFFFFFFFF, 0xFF1C1B1A, 0xFF7C3AED),
    )

    fun parse(hex: String): Color? = runCatching {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6) null else Color(0xFF000000 or h.toLong(16))
    }.getOrNull()

    private fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

    /** Применяет тему: готовую по id или свою из четырёх цветов. Остальные оттенки выводятся смешением. */
    fun apply(t: ThemeCfg) {
        val p = LIST.firstOrNull { it.id == t.id }
        val bg = if (p != null) Color(p.bg) else parse(t.bg) ?: Color(LIST[0].bg)
        val card = if (p != null) Color(p.card) else parse(t.card) ?: Color(LIST[0].card)
        val text = if (p != null) Color(p.text) else parse(t.text) ?: Color(LIST[0].text)
        val accent = if (p != null) Color(p.accent) else parse(t.accent) ?: Color(LIST[0].accent)
        val lum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
        C.light = lum > 0.5f
        C.bg = bg; C.card = card; C.text = text; C.purple = accent
        C.raised = mix(bg, text, 0.16f); C.popup = mix(bg, text, 0.08f); C.line = mix(bg, text, 0.14f); C.dim = mix(bg, text, 0.62f)
        C.ink = if (0.299f * text.red + 0.587f * text.green + 0.114f * text.blue > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF)
    }
}

@Composable
fun FCard(modifier: Modifier = Modifier, color: Color = C.card, pad: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = color, contentColor = C.text, border = BorderStroke(1.dp, C.line)) {
        Column(Modifier.padding(pad), content = content)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, primary: Boolean = true, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) C.text else C.raised
    val fg = if (danger) C.red else if (primary) C.ink else C.text
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 46.dp), shape = CircleShape,
        color = bg.copy(alpha = if (enabled) 1f else 0.4f), contentColor = fg,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, color: Color = C.text, onClick: (() -> Unit)? = null) {
    val m = Modifier.clip(CircleShape).background(if (selected) C.text else Color.Transparent)
        .border(1.dp, if (selected) C.text else C.line, CircleShape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 12.dp, vertical = 6.dp)
    Text(text, m, color = if (selected) C.ink else color, fontSize = 13.sp, maxLines = 1)
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

fun statusColor(status: Int) = when (status) { 1 -> C.green; 2 -> C.red; else -> C.dim }

@Composable
fun Title(text: String, modifier: Modifier = Modifier) = Text(text, modifier, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Sub(text: String, modifier: Modifier = Modifier, color: Color = C.dim) = Text(text, modifier, color = color, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun ScreenHeader(title: String, sub: String = "") {
    Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        if (sub.isNotBlank()) Sub(sub, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, secret: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, number: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 13.sp) }, modifier = modifier.fillMaxWidth(),
        singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else 8, shape = RoundedCornerShape(if (singleLine) 24.dp else 20.dp),
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        else if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        trailingIcon = if (secret) ({
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Показать", tint = C.dim) }
        }) else trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.dim, unfocusedBorderColor = C.line, cursorColor = C.text,
            focusedTextColor = C.text, unfocusedTextColor = C.text, focusedLabelColor = C.dim, unfocusedLabelColor = C.dim,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
fun Toggle(title: String, sub: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp)
            if (sub.isNotBlank()) Sub(sub)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = C.ink, checkedTrackColor = C.text, uncheckedThumbColor = C.dim,
            uncheckedTrackColor = C.card, uncheckedBorderColor = C.line,
        ))
    }
}

@Composable
fun FDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = C.popup, contentColor = C.text, border = BorderStroke(1.dp, C.line)) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Title(title)
                content()
            }
        }
    }
}

/** Теги эмоций и звуков подсвечиваются фиолетовым, как в редакторе Fish Audio. */
fun tagged(text: String, show: Boolean = true): AnnotatedString {
    if (!show) return AnnotatedString(Reply.stripTags(text))
    return buildAnnotatedString {
        var last = 0
        for (m in Reply.TAG.findAll(text)) {
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(color = C.purple, background = C.purple.copy(alpha = 0.14f))) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

fun eyeColor(emotion: String): Color = when (Reply.EMOTIONS[emotion]) {
    "ORANGE" -> C.orange; "BLUE" -> C.blue; "YELLOW" -> C.yellow; "RED" -> Color(0xFFEF4444); "PINK" -> Color(0xFFF472B6)
    else -> Color(0xFFE8F1FF)
}

fun fmtNum(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f млн", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000} тыс."
    else -> "%,d".format(java.util.Locale.US, n).replace(',', ' ')
}
