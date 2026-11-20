package dev.daily.todo

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun Today(m: DailyModel) {
    val date = m.date
    val stream = remember(m, date) { m.store.todos(date) }
    StoredContent(stream, "今日计划") { items -> TodayList(m, date, items) }
}

@Composable
private fun TodayList(m: DailyModel, date: String, items: List<TodoItem>) {
    var editing by rememberSaveable(date) { mutableStateOf(false) }
    var editId by rememberSaveable(date) { mutableStateOf<Long?>(null) }
    var title by rememberSaveable(date) { mutableStateOf("") }
    var deleting by rememberSaveable(date) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val done = items.count { it.done }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(date, style = MaterialTheme.typography.labelLarge)
                Heading("今日计划")
                Text("已完成 " + done + " / " + items.size)
                LinearProgressIndicator(progress = { if (items.isEmpty()) 0f else done.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { editId = null; title = ""; editing = true }) { Text("新增计划") }
            }
        }
        if (items.isEmpty()) item { Text("今天还没有计划。写下第一件想完成的事。") }
        items(items, key = { it.id }) { todo ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(todo.done, { doneNow -> m.action { m.store.setDone(todo.id, doneNow) } })
                TextButton(onClick = { editId = todo.id; title = todo.title; editing = true }) { Text(todo.title) }
            }
            HorizontalDivider()
        }
    }
    if (editing) {
        AlertDialog(onDismissRequest = { if (!saving) editing = false },
            title = { Text(if (editId == null) "新增计划" else "编辑计划") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("计划内容") }, maxLines = 5)
                    if (editId != null) TextButton(onClick = { deleting = true; editing = false }) { Text("删除计划") }
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !saving) { Text("取消") } },
            confirmButton = { TextButton(enabled = title.isNotBlank() && !saving, onClick = {
                saving = true
                val id = editId
                m.action {
                    try {
                        if (id == null) m.store.addTodo(date, title.trim()) else m.store.renameTodo(id, title.trim())
                        editing = false
                    } finally { saving = false }
                }
            }) { Text("保存") } })
    }
    if (deleting) Confirm("删除这条计划？", title, "删除", { deleting = false; editing = true }) {
        val id = editId
        deleting = false
        if (id != null) m.action { m.store.deleteTodo(id) }
    }
}

@Composable
fun HistoryPage(m: DailyModel) {
    var date by rememberSaveable { mutableStateOf(m.date) }
    val stream = remember(m, date) {
        combine(m.store.todos(date), m.store.reflections(date)) { todos, records -> todos to records }
    }
    StoredContent(stream, "当天的计划和回答") { (todos, records) ->
        HistoryList(date, { date = it }, todos, records)
    }
}

@Composable
private fun HistoryList(date: String, select: (String) -> Unit, todos: List<TodoItem>, records: List<ReflectionRecord>) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Heading("历史")
            TextButton(onClick = {
                val day = LocalDate.parse(date)
                DatePickerDialog(context, { _, y, mo, d -> select(LocalDate.of(y, mo + 1, d).toString()) },
                    day.year, day.monthValue - 1, day.dayOfMonth).show()
            }) { Text("选择日期　" + date) }
            Row {
                TextButton(onClick = { select(LocalDate.parse(date).minusDays(1).toString()) }) { Text("上一天") }
                TextButton(onClick = { select(LocalDate.parse(date).plusDays(1).toString()) }) { Text("下一天") }
            }
            Text("计划（" + todos.size + "）", style = MaterialTheme.typography.titleMedium)
        }
        if (todos.isEmpty()) item { Text("当天没有计划。") }
        items(todos) { Text((if (it.done) "已完成　" else "待完成　") + it.title) }
        item { HorizontalDivider(); Text("回答（" + records.size + "）", style = MaterialTheme.typography.titleMedium) }
        if (records.isEmpty()) item { Text("当天没有回答。") }
        items(records) { record ->
            val source = when (record.source) { "TODO_ENTRY" -> "今日"; "OVERLAY" -> "悬浮守护"; else -> "桌面启动" }
            Text(Instant.ofEpochMilli(record.submittedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm")) + " · " + source + (record.targetPackage?.let { " · " + it } ?: ""),
                style = MaterialTheme.typography.labelMedium)
            Text(record.question, style = MaterialTheme.typography.titleSmall)
            Text(record.answer)
            HorizontalDivider()
        }
    }
}

@Composable
fun Drawer(m: DailyModel, a: MainActivity, apps: List<LaunchApp>) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, search) { apps.filter { it.label.contains(search, true) || it.pkg.contains(search, true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Heading("应用")
            Row {
                TextButton(onClick = a::settingsPage) { Text("系统设置") }
                TextButton(onClick = a::dial) { Text("拨号") }
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索应用名称或包名") }, singleLine = true)
        }
        if (filtered.isEmpty()) item { Text("没有匹配的应用。") }
        items(filtered) { app ->
            Column(Modifier.fillMaxWidth().clickable(enabled = !m.checking) { m.openApp(app, a::open) }.padding(vertical = 10.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(app.pkg, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}
