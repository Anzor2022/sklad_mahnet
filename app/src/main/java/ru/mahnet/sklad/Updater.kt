package ru.mahnet.sklad

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление приложения «по воздуху»: при запуске спрашивает у сайта (api.php?a=apk_info),
 * нет ли версии новее; если есть — предлагает обновиться, скачивает APK и открывает установку.
 * Новый APK админ загружает в админке, вкладка «APK файлы». Android требует, чтобы пользователь
 * сам нажал «Установить» (и один раз разрешил установку из этого приложения).
 */
object Updater {
    private const val API = "https://mahnet.ru/api.php"
    private const val APP = "wh"

    @Volatile private var checked = false
    private var pending: File? = null

    /** Номер N из версии «1.0.N» — именно его админ вписывает во вкладке «APK файлы». */
    @Suppress("DEPRECATION")
    private fun installedCode(a: AppCompatActivity): Long {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        val n = pi.versionName?.substringAfterLast('.')?.toLongOrNull()
        if (n != null) return n
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    /** Один раз за запуск приложения: проверить, есть ли новая версия. */
    fun check(a: AppCompatActivity) {
        if (checked) return
        checked = true
        Thread {
            try {
                val c = URL("$API?a=apk_info").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(txt).optJSONObject("apps")?.optJSONObject(APP) ?: return@Thread
                val sv = j.optLong("ver", 0)
                if (sv <= installedCode(a)) return@Thread
                // если этот номер уже оказался неверным (в APK версия не новее) — не предлагать снова
                if (sv == a.getSharedPreferences("updater", 0).getLong("bad", -1L)) return@Thread
                a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) ask(a, j) }
            } catch (e: Exception) {
                // нет сети или сервер недоступен — молча пропускаем
            }
        }.start()
    }

    /** Вызывается из onResume: если человек вернулся из настроек с разрешением — продолжить установку. */
    fun resume(a: AppCompatActivity) {
        val f = pending ?: return
        if (Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls()) {
            pending = null
            install(a, f)
        }
    }

    private fun ask(a: AppCompatActivity, j: JSONObject) {
        val mb = j.optLong("sz", 0) / 1048576.0
        val note = j.optString("note", "")
        val msg = "Доступна новая версия " + j.optString("vn", "") +
            (if (mb > 0) " (" + String.format("%.1f", mb) + " МБ)" else "") +
            (if (note.isNotEmpty()) "\n\n$note" else "")
        AlertDialog.Builder(a)
            .setTitle("Доступно обновление")
            .setMessage(msg)
            .setPositiveButton("Обновить") { _, _ -> download(a, j.optLong("ver", 0)) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun download(a: AppCompatActivity, sv: Long) {
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.setPadding(48, 32, 48, 8)
        val dlg = AlertDialog.Builder(a)
            .setTitle("Скачиваю обновление…")
            .setView(bar)
            .setCancelable(false)
            .show()
        Thread {
            var err = ""
            var out: File? = null
            try {
                val dir = File(a.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "update.apk")
                val c = URL("$API?a=apk_get&app=$APP").openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                if (c.responseCode != 200) throw RuntimeException("HTTP " + c.responseCode)
                val total = c.contentLengthLong
                var got = 0L
                c.inputStream.use { ins ->
                    f.outputStream().use { os ->
                        val buf = ByteArray(65536)
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val p = (got * 100 / total).toInt()
                                if (p != last) { last = p; a.runOnUiThread { bar.progress = p } }
                            }
                        }
                    }
                }
                if (total > 0 && got != total) throw RuntimeException("файл скачан не полностью")
                if (got < 1024) throw RuntimeException("пустой файл")
                out = f
            } catch (e: Exception) {
                err = e.message ?: "ошибка сети"
            }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) {}
                val f = out
                if (f != null) {
                    // проверяем, что в скачанном APK версия действительно новее установленной
                    val info = a.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                    val n = info?.versionName?.substringAfterLast('.')?.toLongOrNull()
                    if (n != null && n <= installedCode(a)) {
                        a.getSharedPreferences("updater", 0).edit().putLong("bad", sv).apply()
                        f.delete()
                        Toast.makeText(
                            a,
                            "В админке указана версия 1.0." + sv + ", а в загруженном APK версия 1.0." + n +
                                ". Исправьте версию во вкладке «APK файлы».",
                            Toast.LENGTH_LONG
                        ).show()
                    } else install(a, f)
                }
                else Toast.makeText(a, "Не удалось скачать обновление: $err", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun install(a: AppCompatActivity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            pending = f
            AlertDialog.Builder(a)
                .setTitle("Разрешите установку")
                .setMessage("Чтобы обновиться, включите в настройках переключатель «Разрешить установку из этого источника», затем вернитесь в приложение.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    a.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))
                    )
                }
                .setNegativeButton("Отмена") { _, _ -> pending = null }
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(a, "Не удалось открыть установку: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
