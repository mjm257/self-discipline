package dev.daily.todo

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class GuardService : AccessibilityService() {
    companion object {
        private val state = MutableStateFlow("守护服务未连接")
        val status = state.asStateFlow()
        private var connected: GuardService? = null
        private val sessions = GuardSessions()
        private val handoffs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun homeEntered(context: Context) {
            sessions.leave()
            connected?.suspendWindow("等待目标应用进入前台")
            expirePass(context)
        }
        private fun expirePass(context: Context, observedAt: Long = System.currentTimeMillis()) {
            val app = context.applicationContext
            handoffs.launch { runCatching { Store.get(app).clearLaunchPass(observedAt) } }
        }
    }

    private val store by lazy { Store.get(applicationContext) }
    private var scope: CoroutineScope? = null
    private var overlay: OverlayManager? = null
    private var evaluation: Job? = null
    private var activeDraft: GuardSessions.Draft? = null
    private var watchingScreen = false
    private var watchingOps = false
    private val main = Handler(Looper.getMainLooper())
    private val ops get() = getSystemService(APP_OPS_SERVICE) as AppOpsManager
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                leave("已息屏")
                expirePass(context)
            }
        }
    }
    private val permissions = AppOpsManager.OnOpChangedListener { _, _ ->
        main.post {
            if (scope != null && !Settings.canDrawOverlays(this)) leave("请在设置中开启悬浮窗权限")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        disconnect()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = worker
        connected = this
        state.value = if (Settings.canDrawOverlays(this)) "等待目标应用进入前台" else "请在设置中开启悬浮窗权限"
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, filter)
        watchingScreen = true
        watchingOps = runCatching {
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissions)
        }.isSuccess
        worker.launch {
            combine(store.settings, store.rules) { prefs, rules -> prefs to rules }
                .catch { leave("暂时无法读取守护设置") }
                .collect { (prefs, rules) ->
                    sessions.prune(rules.filter { it.enabled && prefs.hasPin }.map { it.packageName }.toSet())
                    if (activeDraft == null) return@collect
                    val visit = sessions.current ?: return@collect
                    val rule = rules.find { it.packageName == visit.pkg }
                    when {
                        !prefs.guardingAt(System.currentTimeMillis()) -> leave("应用守护已暂停或尚未设置 PIN")
                        rule?.enabled != true -> leave("等待目标应用进入前台")
                        activeDraft != null && rule.question != activeDraft?.question ->
                            leave("守护规则已更新，等待下一次前台事件")
                    }
                }
        }
    }

    private fun canDisplay(): Boolean = Settings.canDrawOverlays(this) &&
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive &&
        !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val worker = scope ?: return
        if (event == null) { suspendWindow("无法确认前台窗口，本次放行"); return }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val observedAt = System.currentTimeMillis() - (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive ||
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) {
            leave("设备已锁定或息屏")
            expirePass(this, observedAt)
            return
        }
        if (!Settings.canDrawOverlays(this) && overlay?.visible == true) suspendWindow("请在设置中开启悬浮窗权限")
        val pkg = event.packageName?.toString().orEmpty()
        val window = overlay
        if (pkg == packageName && window?.ownsEvent(event) == true) return
        if (pkg.isNotEmpty() &&
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') == pkg) {
            // AOSP's IME reports both a non-full View and a full SoftInputWindow while
            // opening the keyboard. Require a served editor in our focused overlay for both.
            val keyboard = !event.isFullScreen || event.className == "android.inputmethodservice.SoftInputWindow"
            if (keyboard && window?.isEditing() == true) return
            suspendWindow("输入法窗口，本次放行")
            return
        }

        val previous = sessions.current
        if (previous?.released == true && previous.pkg == pkg && !event.isFullScreen) return
        // Ambiguous panels and non-default displays are never grounds for creating a covering window.
        if (pkg.isBlank() || !event.isFullScreen ||
            (Build.VERSION.SDK_INT >= 33 && event.displayId > Display.DEFAULT_DISPLAY)) {
            suspendWindow("无法确认前台窗口，本次放行")
            return
        }
        if (previous?.pkg != pkg) suspendWindow("等待目标应用进入前台")
        val visit = sessions.enter(pkg, SystemClock.elapsedRealtime())
        if (visit.released || overlay?.visible == true || evaluation?.isActive == true) return
        // Consume this observed transition even if a later panel cancels the display
        // decision. The result belongs only to the visit which observed the transition.
        val pass = handoffs.async {
            if (store.consumeLaunchPass(pkg, observedAt)) visit.released = true
        }
        evaluation = worker.launch {
            try {
                // Confirmed arrival consumes (or invalidates) only grants already issued at
                // event time. This also runs for unselected apps and without overlay permission.
                pass.await()
                if (visit.released) {
                    state.value = "已从桌面完成本次提问"
                    return@launch
                }
                if (!canDisplay() || pkg == packageName) return@launch
                // Read the selected rule first, to avoid enumerating arbitrary foreground apps.
                val rule = store.rules.first().firstOrNull { it.packageName == pkg && it.enabled }
                if (rule == null || !withContext(Dispatchers.IO) { Apps.safe(this@GuardService, pkg) }) {
                    if (sessions.current === visit) leave("等待目标应用进入前台")
                    return@launch
                }
                if (sessions.current !== visit || !canDisplay()) return@launch
                val eligible = store.eligibleRule(pkg)
                if (eligible == null) {
                    visit.released = true
                    state.value = "本次进入已放行"
                    return@launch
                }
                val prefs = store.settings.first()
                if (sessions.current !== visit || visit.released || !sessions.hasForegroundEvidence ||
                    !canDisplay() || !prefs.guardingAt(System.currentTimeMillis())) return@launch
                val draft = sessions.draft(pkg, eligible.question)
                val manager = overlay ?: OverlayManager(this@GuardService).also { overlay = it }
                activeDraft = draft
                manager.show(eligible.label, draft,
                    onSubmit = { answer ->
                        action(visit, draft) {
                            store.submit(draft.id, draft.question, answer, "OVERLAY", pkg)
                            sessions.forget(pkg, draft)
                            visit.released = true
                            if (sessions.current === visit || (sessions.current?.pkg == pkg && activeDraft === draft)) {
                                sessions.current?.released = true
                                evaluation?.cancel()
                                dismiss()
                                state.value = "已保存本次回答"
                            }
                        }
                    },
                    onPin = { pin ->
                        action(visit, draft) {
                            if (store.emergencyPause(pin)) {
                                sessions.forget(pkg, draft)
                                leave("已暂停所有提问 30 分钟")
                            } else if (sessions.current === visit) manager.error("PIN 不正确，请重试")
                        }
                    })
                state.value = "正在显示应用守护问题"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (sessions.current === visit) leave("无法读取规则或显示悬浮窗，本次放行")
            }
        }
    }

    private fun action(visit: GuardSessions.Visit, draft: GuardSessions.Draft, task: suspend () -> Unit) {
        if (!sessions.hasForegroundEvidence || sessions.current !== visit || activeDraft !== draft || !canDisplay()) {
            leave("窗口已失效，本次放行")
            return
        }
        scope?.launch {
            try { task() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (sessions.current === visit && activeDraft === draft) overlay?.error("保存失败，请重试")
            }
        }
    }

    private fun dismiss() {
        overlay?.hide()
        activeDraft = null
    }

    private fun leave(reason: String) {
        sessions.leave()
        suspendWindow(reason)
    }

    private fun suspendWindow(reason: String) {
        sessions.obscure()
        evaluation?.cancel()
        evaluation = null
        dismiss()
        state.value = reason
    }

    override fun onInterrupt() {
        sessions.disconnect(SystemClock.elapsedRealtime())
        suspendWindow("守护服务已中断，等待下一次前台事件")
    }

    private fun disconnect() {
        if (connected === this) connected = null
        scope?.cancel()
        scope = null
        if (watchingScreen) unregisterReceiver(screen)
        if (watchingOps) ops.stopWatchingMode(permissions)
        watchingScreen = false
        watchingOps = false
        sessions.disconnect(SystemClock.elapsedRealtime())
        suspendWindow("守护服务未连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
