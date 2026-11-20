package dev.daily.todo

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun GuardPage(m: DailyModel, a: MainActivity, s: AppSettings, apps: List<LaunchApp>) {
    StoredContent(m.store.rules, "守护规则") { rules -> GuardRules(m, a, s, apps, rules) }
}

@Composable
private fun GuardRules(m: DailyModel, a: MainActivity, s: AppSettings, apps: List<LaunchApp>, rules: List<GuardRule>) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<GuardRule?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val pkg = editing
    if (pkg != null) {
        RuleEditor(m, a, rules.firstOrNull { it.packageName == pkg }, apps, { editing = null })
        return
    }
    Sheet {
        Heading("应用守护")
        Text("从本桌面打开目标前先回答。其他入口需要无障碍和悬浮窗权限。")
        val until = maxOf(s.guardPausedUntil, s.allPausedUntil)
        if (until > now) Text("守护已暂停至 " + Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
        else Text("守护可用 · 已启用 " + rules.count { it.enabled } + " 项")
        PermissionStatus(a)
        Button(onClick = { editing = "" }) { Text("添加守护应用") }
        OutlinedButton(onClick = { m.action { m.store.pauseGuard() } }) { Text("暂停应用守护 30 分钟") }
        if (rules.isEmpty()) Text("还没有守护应用。")
        rules.forEach { rule ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.label, style = MaterialTheme.typography.titleMedium)
                    Text(rule.packageName, style = MaterialTheme.typography.bodySmall)
                }
                Switch(rule.enabled, onCheckedChange = { enable ->
                    if (enable && !Apps.safe(a, rule.packageName)) m.error = "无法验证为可守护的普通应用。"
                    else m.action { m.store.saveRule(rule.copy(enabled = enable)) }
                })
            }
            Text(rule.question)
            Text("冷却 " + rule.cooldownSeconds + " 秒", style = MaterialTheme.typography.labelMedium)
            Row {
                TextButton(onClick = { editing = rule.packageName }) { Text("编辑") }
                TextButton(onClick = { deleting = rule }) { Text("移除") }
            }
        }
    }
    deleting?.let { rule ->
        Confirm("移除守护规则？", rule.label, "移除", { deleting = null }) {
            deleting = null
            m.action { m.store.deleteRule(rule.packageName) }
        }
    }
}

@Composable
private fun RuleEditor(m: DailyModel, a: MainActivity, rule: GuardRule?, apps: List<LaunchApp>, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf(rule?.packageName ?: "") }
    var question by rememberSaveable { mutableStateOf(rule?.question ?: "使用这个应用的目的是什么？") }
    var seconds by rememberSaveable { mutableStateOf((rule?.cooldownSeconds ?: 900).toString()) }
    var enabled by rememberSaveable { mutableStateOf(rule?.enabled ?: true) }
    var saving by remember { mutableStateOf(false) }
    var shown by remember(query) { mutableIntStateOf(8) }
    val matches = remember(query, apps) {
        apps.distinctBy { it.pkg }.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }
    val contextRevision = a.revision
    val eligible = remember(pkg, contextRevision, apps) { pkg.isNotBlank() && Apps.safe(a, pkg.trim()) }
    val label = apps.firstOrNull { it.pkg == pkg.trim() }?.label ?: rule?.label ?: pkg.trim()
    Sheet {
        Heading(if (rule == null) "添加守护应用" else "编辑守护规则")
        if (rule == null) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("按名称或包名搜索") })
            matches.take(shown).forEach { app ->
                TextButton(onClick = { pkg = app.pkg }) { Text(app.label + " · " + app.pkg) }
            }
            if (shown < matches.size) TextButton(onClick = { shown += 16 }) { Text("显示更多应用") }
            OutlinedTextField(pkg, { pkg = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("目标包名（可手填）") })
        } else Text(label + " · " + pkg)
        if (pkg.isNotBlank()) Text(if (eligible) "已识别目标：" + label else "目标身份未确认或属于系统安全界面，无法启用。")
        OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("打开前的问题") })
        OutlinedTextField(seconds, { seconds = it }, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("冷却秒数（0 表示无冷却）") })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled, { enabled = it }); Text("启用守护") }
        Button(enabled = !saving && pkg.isNotBlank() && question.isNotBlank() && (seconds.toIntOrNull() ?: -1) >= 0 &&
            (!enabled || eligible), onClick = {
            saving = true
            m.action {
                try {
                    val target = pkg.trim()
                    require(!enabled || Apps.safe(a, target)) { "目标应用已变更，请重新选择。" }
                    m.store.saveRule(GuardRule(target, label, enabled, question.trim(), seconds.toInt(), rule?.lastSubmittedAt))
                    close()
                } finally { saving = false }
            }
        }) { Text("保存规则") }
        TextButton(onClick = close) { Text("取消") }
    }
}

@Composable
private fun PermissionStatus(a: MainActivity) {
    val revision = a.revision
    val access = remember(revision) { a.accessibilityEnabled() }
    val overlay = remember(revision) { Settings.canDrawOverlays(a) }
    val guardStatus by GuardService.status.collectAsStateWithLifecycle()
    Text("无障碍服务：" + (if (access) "已开启" else "未开启") + "　悬浮窗：" + (if (overlay) "已授权" else "未授权"))
    if (!access || !overlay) Text("外部入口守护未就绪，从本桌面启动仍按规则提问。", style = MaterialTheme.typography.bodySmall)
    else Text(guardStatus, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun SettingsPage(m: DailyModel, a: MainActivity, s: AppSettings) {
    var guide by rememberSaveable { mutableStateOf(false) }
    if (guide) {
        androidx.activity.compose.BackHandler { guide = false }
        Sheet {
            Heading("权限引导")
            Text("默认桌面决定按 Home 后进入哪里。未选择本应用也能从图标进入今日计划。")
            OutlinedButton(onClick = a::requestHome) { Text("选择默认桌面") }
            HorizontalDivider()
            PermissionStatus(a)
            Text("从通知或最近任务进入目标应用时，无障碍服务只识别应用包名。你可以随时在系统设置中关闭服务。")
            OutlinedButton(onClick = a::accessibility) { Text("前往无障碍设置") }
            Text("悬浮窗授权允许在已选择的目标应用上显示可输入的问题。桌面内启动前提问无需此权限。")
            OutlinedButton(onClick = a::overlay) { Text("前往悬浮窗设置") }
            Text("系统拒绝授权时，可继续使用桌面启动前提问。")
            TextButton(onClick = { guide = false }) { Text("返回设置") }
        }
        return
    }
    var pin by rememberSaveable { mutableStateOf(false) }
    if (pin) { PinSetup(m, true) { pin = false }; return }
    var question by rememberSaveable(s.dailyQuestion) { mutableStateOf(s.dailyQuestion) }
    var policy by rememberSaveable(s.entryPolicy) { mutableStateOf(s.entryPolicy) }
    var dialog by rememberSaveable { mutableStateOf("") }
    val revision = a.revision
    val home = remember(revision) {
        a.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == a.packageName
    }
    Sheet {
        Heading("设置")
        Text("进入今日页", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("每日问题") })

        Text("回答满 15 个非空白字符即可提交，字数不限。")
        EntryPolicy.entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(policy == entry, onClick = { policy = entry })
                TextButton(onClick = { policy = entry }) { Text(if (entry == EntryPolicy.FIRST_DAILY) "每日首次进入" else "每次进入") }
            }
        }
        Button(enabled = question.isNotBlank(), onClick = {
            m.action { m.store.updateSettings(question.trim(), policy); m.error = "提问设置已保存。" }
        }) { Text("保存提问设置") }
        OutlinedButton(onClick = { pin = true }) { Text("更改紧急 PIN") }
        HorizontalDivider()
        Text("桌面及权限", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { guide = true }) { Text("权限引导") }
        Text(if (home) "当前默认桌面：每日自律" else "当前使用其他默认桌面")
        TextButton(onClick = a::requestHome) { Text("设为默认桌面") }
        TextButton(onClick = a::homeSettings) { Text("更换或恢复系统桌面") }
        TextButton(onClick = { m.navigate(Page.GUARD) }) { Text("应用守护与暂停") }
        PermissionStatus(a)
        TextButton(onClick = { dialog = "access" }) { Text("管理无障碍服务") }
        TextButton(onClick = { dialog = "overlay" }) { Text("管理悬浮窗权限") }
        HorizontalDivider()
        Text("本地数据与隐私", style = MaterialTheme.typography.titleLarge)
        Text("计划、回答和守护规则保存在本机。守护服务只使用窗口变化的应用标识，不读取屏幕文本。关闭授权仍可使用今日计划。")
        TextButton(onClick = { dialog = "export" }) { Text("导出 JSON") }
        TextButton(onClick = { dialog = "clear" }) { Text("清空全部本地数据") }
    }
    when (dialog) {
        "access" -> Confirm("前台应用识别", "开启无障碍服务后，可识别进入自选应用的窗口变化。服务不读取节点、按键或窗口内容。随时可在系统设置关闭。", "去系统设置", { dialog = "" }) { dialog = ""; a.accessibility() }
        "overlay" -> Confirm("在应用上方显示提问", "授权后，进入已启用的目标时可显示可输入的目的提问。系统导航、锁屏和敏感系统窗口优先放行。", "去系统设置", { dialog = "" }) { dialog = ""; a.overlay() }
        "export" -> Confirm("导出敏感数据？", "文件包含计划、回答原文及应用规则。请选择你信任的本地保存位置。", "选择位置", { dialog = "" }) { dialog = ""; a.export() }
        "clear" -> Confirm("清空本地数据？", "将删除所有日期的计划、回答、应用规则和 PIN。", "继续", { dialog = "" }) { dialog = "clearFinal" }
        "clearFinal" -> Confirm("确认永久删除？", "删除后无法恢复，应用将返回首次引导。", "永久删除", { dialog = "" }) {
            dialog = ""
            m.action { m.store.clearAll(); m.reset() }
        }
    }
}
