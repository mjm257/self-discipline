package dev.daily.todo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.text.Collator

data class LaunchApp(val pkg: String, val label: String, val component: ComponentName)

/** LauncherApps enumeration and component-based launch adapted from GPL Olauncher (see docs/upstream). */
object Apps {
    fun list(context: Context): List<LaunchApp> {
        val launcher = context.getSystemService(LauncherApps::class.java)
        val collator = Collator.getInstance()
        return launcher.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != context.packageName }
            .map { LaunchApp(it.componentName.packageName, it.label.toString(), it.componentName) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun launch(context: Context, app: LaunchApp) {
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(app.component, Process.myUserHandle(), null, null)
    }

    /** Validate manual and selected packages identically. Failure to identify always allows normal phone use. */
    fun safe(context: Context, pkg: String): Boolean = runCatching {
        if (pkg == context.packageName || pkg == "android") return false
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (info.flags and system != 0) return false
        val homes = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        if (pm.queryIntentActivities(homes, 0).any { it.activityInfo.packageName == pkg }) return false
        if (context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == pkg) return false
        if (context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == pkg }) return false
        if (Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') == pkg) return false
        context.getSystemService(LauncherApps::class.java).getActivityList(pkg, Process.myUserHandle()).isNotEmpty()
    }.getOrDefault(false)

    fun watch(context: Context, onChange: () -> Unit): AutoCloseable {
        val service = context.getSystemService(LauncherApps::class.java)
        val listener = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
        }
        service.registerCallback(listener, Handler(Looper.getMainLooper()))
        return AutoCloseable { service.unregisterCallback(listener) }
    }
}
