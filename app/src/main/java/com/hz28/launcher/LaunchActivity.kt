package com.hz28.workplace

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URL
import com.hz28.psql.*
import com.hz28.workplace.databinding.BlankBinding
import com.hz28.workplace.databinding.LaunchActivityBinding

const val WORKPLACE = 0 // Загрузчик

class LaunchActivity : FragmentActivity() {

    lateinit var bind: LaunchActivityBinding
    lateinit var bind2: BlankBinding
    val TABLET_URL = "jdbc:postgresql://psql1.ad.hz28.ru/hz28?user=regularuser&password=P@ssw0rd#"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = LaunchActivityBinding.inflate(layoutInflater)
        setContentView(bind.root)
        PostgreSQL.url = TABLET_URL
        PostgreSQL.connect()
        if (PostgreSQL.statusStr != SQL_CONNECTED_STR) {
            bind.textView.text = getString(R.string.statusWrongStr)
        }
    }

    fun fillMenu(bld: Char, lvl: Char) {
        bind2 = BlankBinding.inflate(layoutInflater)
        setContentView(bind2.root)
        ScrollSelectFragment().apply {
            SQL_REQUEST = "SELECT idscheme, workplace_name FROM bread_factory_scheme.tplscheme" +
                " JOIN workplaces.workplaces ON idscheme = id" +
                " WHERE idgroupexc =300 AND idbuilding = $bld AND idlevelbld = $lvl" +
                " AND typestatus = 3 ORDER BY idscheme"
            promptText = "Рабочие места для загрузки:"
            tagField = "idscheme"
            btnTextField = "workplace_name"
            onCancel = {
                supportFragmentManager.findFragmentByTag("Select")?.let {
                    removeFragment(it)
                }
                setContentView(R.layout.launch_activity)
            }
            onSelect = {
                supportFragmentManager.findFragmentByTag("Select")?.let {
                    removeFragment(it)
                }
                loadApk(it)
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainLayout, this, "Select").commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // Полный экран, скрываем системные кнопки
        window.decorView.systemUiVisibility = ( View.SYSTEM_UI_FLAG_FULLSCREEN
                + View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                + View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    fun onFloorBtnClick(view: View) {
        val lvl = view.tag.toString()[1]
        val bld = view.tag.toString()[0]
        fillMenu(bld, lvl)
    }

    fun onSetupBtnClick(view: View) {
        loadApk(view.tag.toString().toInt())
    }

    fun loadApk(workplace: Int) {
        var session: PackageInstaller.Session?  = null
        try {   // Создаем сессию для установки apk
            val packageInstaller = packageManager.packageInstaller
            val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            // Запускаем скачивание с сервера apk, tag указывает номер рабочего места
            val job = addApkToInstallSession(workplace.toString(), session)

            // В качестве получателя сообщений указываем себя
            // новый экземпляр не создается из-за android:launchMode="singleTop"
            val intent = Intent(this, LaunchActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this,
                0, intent, 0)
            val statusReceiver = pendingIntent.intentSender

            runBlocking{ job.join() } // Ждем завершения загрузки
            // Commit the session (начинает процесс установки).
            session.commit(statusReceiver)
        } catch (e: Exception) {
            session?.abandon()
            bind2.statusView.text = e.toString()
        }
    }

    // Делаем по номеру Url, соединяемся и загружаем apk рабочего места в инсталлер
    private fun addApkToInstallSession(apkNumber: String, session: PackageInstaller.Session?): Job {
        val url = URL("http://fs001.ad.hz28.ru/Android/WorkPlaces/WorkPlace$apkNumber.apk")
        val urlConnection = url.openConnection()
        // сетевое подключение только в отдельном потоке
        return GlobalScope.launch {
            val msg = try {
                urlConnection.connect()
                val inputStream = urlConnection.getInputStream()
                val totalSize = urlConnection.contentLength
                var downloadedSize = 0
                val packageInSession = session?.openWrite(
                    "package",0, totalSize.toLong())
                val buffer = ByteArray(1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                    downloadedSize += bytesRead
                    packageInSession?.write(buffer, 0, bytesRead)
                }
                packageInSession?.close()
                inputStream?.close()
                "$downloadedSize"

            } catch (e: IOException) {
                e.printStackTrace()
                e.toString()
            }
            runOnUiThread { bind2.statusView.text = msg }
        }
    }
    // Получает сообщения от PackageInstaller
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val extras = intent.extras
        bind2.statusView.text = "Идет установка"
        when (val status = extras?.getInt(PackageInstaller.EXTRA_STATUS)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = extras[Intent.EXTRA_INTENT] as Intent?
                startActivity(confirmIntent)
            }

            PackageInstaller.STATUS_SUCCESS ->  runWorkPlaceAndFinish()

            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED, PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> bind2.statusView.text = "Установка не удалась"

            else -> bind2.statusView.text = "Статус установки - $status"
        }
    }
    // Запускаем загруженное рабочее место и отписываемся от автозагрузки
    private fun runWorkPlaceAndFinish() {
        bind2.statusView.text = "Установка завершена"
        val workPlaceActivity = packageManager.
            getLaunchIntentForPackage(getString(R.string.WorkPlacePackageStr))
        if (workPlaceActivity == null) {
            bind2.statusView.text = "Не удалось запустить рабочее место"
        } else {
            try {
                //workPlaceActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK + Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(workPlaceActivity)
                val componentName = ComponentName(this, BootCompletedReceiver::class.java)
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                finish()
            } catch (e: Exception) {
                bind2.statusView.text = e.toString()
            }
        }
    }

}
