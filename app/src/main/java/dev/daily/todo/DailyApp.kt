package dev.daily.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun DailyApp(m: DailyModel, a: MainActivity) {
    DailyTheme {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            StoredContent(m.store.settings, "本地设置") { s -> DailyContent(m, a, s) }
        }
    }
}

@Composable
private fun DailyContent(m: DailyModel, a: MainActivity, s: AppSettings) {
    val revision = a.revision
    var apps by remember { mutableStateOf(emptyList<LaunchApp>()) }
    LaunchedEffect(revision, m.page) {
        if (m.page == Page.APPS || m.page == Page.GUARD) {
            try { apps = withContext(Dispatchers.IO) { Apps.list(a) } }
            catch (_: Exception) { m.error = "无法读取应用列表，请重试。" }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L * 10)
            m.tick()
        }
    }
    BackHandler(enabled = s.hasPin && m.page != Page.TODAY && m.prompt == null) {
        m.navigate(Page.TODAY)
    }
    Column(Modifier.fillMaxSize()) {
        if (s.hasPin && m.page == Page.TODAY && m.checking) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        } else if (!s.hasPin) {
            PinSetup(m, false) { m.today() }
        } else {
            ScrollableTabMenu(m)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (m.page) {
                    Page.TODAY -> Today(m)
                    Page.HISTORY -> HistoryPage(m)
                    Page.APPS -> Drawer(m, a, apps)
                    Page.GUARD -> GuardPage(m, a, s, apps)
                    Page.SETTINGS -> SettingsPage(m, a, s)
                }
            }
            if (m.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            m.prompt?.let { q -> key(q.session) { QuestionDialog(m, a, q) } }
        }
    }
    m.error?.let { message ->
        AlertDialog(onDismissRequest = { m.error = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { m.error = null }) { Text("知道了") } })
    }
}

@Composable
private fun ScrollableTabMenu(m: DailyModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Page.entries.forEach { page ->
            FilterChip(selected = page == m.page, onClick = { m.navigate(page) }, label = { Text(page.title) })
        }
    }
    HorizontalDivider()
}

@Composable
fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Heading(title: String) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }

@Composable
fun Confirm(title: String, body: String, confirm: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = accept) { Text(confirm) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
fun PinField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, { if (it.length <= 12 && it.all { c -> c in '0'..'9' }) change(it) },
        Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

@Composable
fun PinSetup(m: DailyModel, editing: Boolean, finished: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Sheet {
        Heading(if (editing) "更改紧急 PIN" else "每日自律")
        Text(if (editing) "输入当前 PIN，再验证新 PIN。" else
            "用今天的计划开始一天。设置紧急 PIN 后，进入今日页时会请你写下目的。你可以按需设置默认桌面和应用守护。")
        Text("在提问时长按标题 5 秒并输入 PIN，即可暂停全部提问 30 分钟。")
        if (editing) PinField(old, { old = it }, "当前 PIN")
        PinField(pin, { pin = it }, "新 PIN（4–12 位数字）")
        PinField(confirm, { confirm = it }, "再次输入新 PIN")
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        Button(enabled = !saving && pin.length >= 4 && confirm.isNotEmpty(), onClick = {
            if (pin != confirm) message = "两次输入不同，请重新确认。"
            else {
                saving = true
                m.action {
                    try {
                        if (editing && !m.store.verifyPin(old)) message = "当前 PIN 不正确。"
                        else { m.store.setPin(pin); finished() }
                    } finally { saving = false }
                }
            }
        }) { Text(if (editing) "保存 PIN" else "验证并开始") }
        if (editing) TextButton(onClick = finished) { Text("取消") }
        else {
            val activity = androidx.activity.compose.LocalActivity.current
            TextButton(onClick = { activity?.finish() }) { Text("稍后设置，退出") }
        }
        Text("忘记 PIN 时，可从系统设置更换默认桌面、关闭无障碍服务或撤销悬浮窗授权。清除本应用数据会删除全部计划、回答和设置，可随后重新设置 PIN。")
    }
}

@Composable
private fun QuestionDialog(m: DailyModel, a: MainActivity, q: EntryQuestion) {
    var unlocking by rememberSaveable { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Sheet {
                Text(if (q.app == null) "进入今日计划" else "打开 " + q.app.label,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(q.text, Modifier.fillMaxWidth().pointerInput(q.session) {
                    detectTapGestures(onPress = {
                        val released = withTimeoutOrNull(5000L) { tryAwaitRelease() }
                        if (released == null) unlocking = true
                    })
                }, style = MaterialTheme.typography.headlineSmall)
                if (unlocking) {
                    PinField(pin, { pin = it; invalid = false }, "紧急 PIN")
                    if (invalid) Text("PIN 不正确。", color = MaterialTheme.colorScheme.error)
                    Button(enabled = !m.busy && pin.length >= 4, onClick = {
                        m.escape(pin) { invalid = true; pin = "" }
                    }) { Text("退出并暂停 30 分钟") }
                    TextButton(onClick = { unlocking = false; pin = "" }) { Text("继续回答") }
                } else {
                    OutlinedTextField(m.answer, { m.answer = it }, Modifier.fillMaxWidth(),
                        label = { Text("我的回答") }, minLines = 3, maxLines = 7)
                    val count = answerLength(m.answer)
                    Text(androidx.compose.ui.res.stringResource(R.string.answered_count, count, MIN_ANSWER_CHARS))
                    Button(enabled = canSubmitAnswer(m.answer) && !m.busy, onClick = { m.submit(a::open) }) {
                        Text(if (q.app == null) "保存并进入今日" else "保存并打开应用")
                    }
                    Text("紧急退出：长按问题标题 5 秒，再输入 PIN。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
