package com.freddyai.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Обновление без магазина: приложение смотрит последний релиз на GitHub,
 * скачивает APK и ставит его через системный установщик поверх текущей версии.
 * Новый APK должен быть подписан тем же ключом — иначе Android откажет в установке.
 */
object Updater {
    const val REPO = "kuzminigorwork-dev/FreddyAI"
    /** Меняется только в тестах. */
    var API = "https://api.github.com"
    private const val CHECK_EVERY = 6 * 60 * 60 * 1000L

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

    var available by mutableStateOf<Release?>(null)
    var checking by mutableStateOf(false)
    var progress by mutableIntStateOf(-1)
    var message by mutableStateOf("")
    var dismissed by mutableStateOf("")
    private val scope = MainScope()
    private var job: Job? = null

    val current: String get() = BuildConfig.VERSION_NAME

    /** Сравнение версий вида 2.10.1 > 2.9.3; хвосты вроде «-beta» не учитываются. */
    fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun parse(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.str("name").endsWith(".apk", true) } ?: return null
        return Release(o.str("tag_name").removePrefix("v"), o.str("body").trim(), apk.str("browser_download_url"), apk.optLong("size"), o.str("html_url"))
    }

    /** Автоматическая проверка при запуске — не чаще раза в 6 часов. */
    fun autoCheck() {
        if (!S.autoUpdate) return
        val prefs = S.ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < CHECK_EVERY) {
            // Найденное ранее обновление показываем и без нового запроса.
            prefs.getString("found", null)?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { newer(it.version, current) }?.let { available = it }
            return
        }
        check(silent = true)
    }

    fun check(silent: Boolean = false) {
        if (checking) return
        checking = true; if (!silent) message = "Проверяю…"
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("$API/repos/$REPO/releases/latest").header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "FreddyAI/$current").build()).execute().use { r ->
                        if (r.code == 404) throw ApiError(404, "На GitHub пока нет опубликованных версий.")
                        if (r.code == 403 || r.code == 429) throw ApiError(r.code, "GitHub временно ограничил проверки. Попробуй позже.")
                        if (!r.isSuccessful) throw ApiError(r.code, "Не удалось проверить обновления (${r.code}).")
                        r.body!!.string()
                    }
                }
                val rel = parse(body)
                S.ctx.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis())
                    .putString("found", if (rel != null && newer(rel.version, current)) body else null).apply()
                if (rel != null && newer(rel.version, current)) {
                    available = rel; message = "Доступна версия ${rel.version}."
                } else {
                    available = null; message = if (silent) "" else "Установлена последняя версия $current."
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (!silent) message = "⚠ " + errorText(e)
            } finally { checking = false }
        }
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || S.ctx.packageManager.canRequestPackageInstalls()

    /** Открывает системный экран «Установка неизвестных приложений» для FreddyAI. */
    fun allowInstallIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + S.ctx.packageName))

    fun download() {
        val rel = available ?: return
        if (job?.isActive == true) return
        progress = 0; message = "Скачиваю версию ${rel.version}…"
        job = scope.launch {
            val file = File(S.ctx.cacheDir, "update.apk")
            try {
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "FreddyAI/$current").build()).execute().use { r ->
                        if (!r.isSuccessful) throw ApiError(r.code, "Не удалось скачать обновление (${r.code}).")
                        val total = r.body!!.contentLength().takeIf { it > 0 } ?: rel.size
                        r.body!!.byteStream().use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024); var done = 0L
                                while (true) {
                                    if (!isActive) throw CancellationException()
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 99)
                                }
                                if (rel.size > 0 && done != rel.size) throw ApiError(0, "Файл скачался не полностью. Попробуй ещё раз.")
                            }
                        }
                    }
                    verify(file)
                }
                progress = 100; message = "Подтверди установку в системном окне."
                install(file)
            } catch (e: CancellationException) {
                file.delete(); progress = -1; message = "Загрузка отменена."
            } catch (e: Exception) {
                file.delete(); progress = -1; message = "⚠ " + errorText(e)
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** Проверяем до установки: это FreddyAI, версия новее и подпись совпадает с установленной. */
    private fun verify(file: File) {
        val pm = S.ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw ApiError(0, "Скачанный файл не похож на APK.")
        if (info.packageName != S.ctx.packageName) throw ApiError(0, "Это APK другого приложения — установка отменена.")
        val mine = pm.getPackageInfo(S.ctx.packageName, flags)
        if (signatures(info) != signatures(mine)) throw ApiError(0, "Обновление подписано другим ключом. Удали текущую версию и установи новую вручную.")
    }

    @Suppress("DEPRECATION")
    private fun signatures(p: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else p.signatures
        return sigs?.map { it.toCharsString() }?.toSet() ?: emptySet()
    }

    private fun install(file: File) {
        val installer = S.ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(S.ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input -> session.openWrite("freddyai.apk", 0, file.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(S.ctx, id, Intent(S.ctx, InstallReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }
}

/** Ответ системного установщика: просит подтверждение у пользователя или сообщает об ошибке. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится в новой версии
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                runCatching { Updater.progress = -1; Updater.message = "⚠ Установка не выполнена" + if (msg.isNotBlank()) ": $msg" else "." }
            }
        }
    }
}
