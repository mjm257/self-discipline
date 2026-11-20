package dev.daily.todo

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: DailyModel by viewModels()
    var revision by mutableIntStateOf(0)
        private set
    private var pendingEntry = false
    private var returningFromExternalApp = false
    private var watcher: AutoCloseable? = null
    private val systemResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.systemTrip = false
        revision++
    }
    private val exportResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.systemTrip = false
        val destination = result.data?.data
        if (result.resultCode == RESULT_OK && destination != null) model.action {
            val json = model.store.exportJson()
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(destination, "wt") ?: error("无法写入所选文件。")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            model.error = "已导出到所选文件。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEntry = !model.started
        setContent { DailyApp(model, this) }
        watcher = runCatching { Apps.watch(this) { runOnUiThread { revision++ } } }.getOrNull()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            GuardService.homeEntered(applicationContext)
            pendingEntry = true
        }
    }

    override fun onResume() {
        super.onResume()
        GuardService.homeEntered(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        if (returningFromExternalApp) pendingEntry = true
        returningFromExternalApp = false
    }

    override fun onPostResume() {
        super.onPostResume()
        revision++
        if (pendingEntry) {
            pendingEntry = false
            model.today()
        } else model.tick()
    }

    override fun onStop() {
        returningFromExternalApp = !isChangingConfigurations && !model.systemTrip
        super.onStop()
    }

    override fun onDestroy() {
        watcher?.close()
        super.onDestroy()
    }

    private fun system(intent: Intent) {
        try {
            model.systemTrip = true
            systemResult.launch(intent)
        } catch (_: Exception) {
            model.systemTrip = false
            model.error = "系统未提供此入口，请在系统设置中手动打开。"
        }
    }

    fun requestHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                system(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        homeSettings()
    }
    fun homeSettings() = system(Intent(Settings.ACTION_HOME_SETTINGS))
    fun accessibility() = system(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun overlay() = system(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
    fun settingsPage() = system(Intent(Settings.ACTION_SETTINGS))
    fun dial() {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
        catch (_: Exception) { model.error = "未找到拨号应用。" }
    }

    fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val expected = ComponentName(this, GuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun export() {
        try {
            model.systemTrip = true
            exportResult.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                putExtra(Intent.EXTRA_TITLE, "每日自律-" + LocalDate.now() + ".json")
            })
        } catch (_: Exception) {
            model.systemTrip = false
            model.error = "无法打开系统文件选择器。"
        }
    }

    fun open(app: LaunchApp) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            model.action { model.store.departedPackage(app.pkg) }
            return
        }
        try { Apps.launch(this, app) }
        catch (_: Exception) {
            model.action { model.store.departedPackage(app.pkg) }
            model.error = "启动失败，应用可能已卸载。"
            revision++
        }
    }
}
