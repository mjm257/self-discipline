package dev.daily.todo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay

enum class Page(val title: String) {
    TODAY("今日"), HISTORY("历史"), APPS("应用"), GUARD("守护"), SETTINGS("设置")
}

data class EntryQuestion(
    val text: String, val app: LaunchApp? = null,
    val session: String = UUID.randomUUID().toString()
)

class DailyModel(application: Application) : AndroidViewModel(application) {
    val store = Store.get(application)
    internal val obsidian = ObsidianSync(application, store)

    init {
        viewModelScope.launch {
            store.allPlans.retryWhen { _, _ ->
                delay(5_000)
                true
            }.collect { obsidian.sync() }
        }
    }
    var page by mutableStateOf(Page.TODAY)
        private set
    var date by mutableStateOf(LocalDate.now().toString())
        private set
    var prompt by mutableStateOf<EntryQuestion?>(null)
        private set
    var answer by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var systemTrip = false
    var started = false
    private var generation = 0

    fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.localizedMessage ?: "操作失败，请重试。" }
    }

    fun tick() {
        val now = LocalDate.now().toString()
        if (date != now) {
            date = now
            if (page == Page.TODAY && prompt == null) today()
        }
    }

    fun navigate(next: Page) {
        if (prompt != null || next == page) return
        if (next == Page.TODAY) today() else {
            generation++
            checking = false
            page = next
        }
    }

    fun today() {
        started = true
        date = LocalDate.now().toString()
        page = Page.TODAY
        if (prompt != null && prompt?.app == null) return
        val request = ++generation
        prompt = null
        answer = ""
        checking = true
        action {
            try {
                if (store.shouldAskToday(date)) {
                    val question = store.settings.first().dailyQuestion
                    if (request == generation) prompt = EntryQuestion(question)
                }
            } finally { if (request == generation) checking = false }
        }
    }

    fun openApp(app: LaunchApp, launch: (LaunchApp) -> Unit) {
        if (checking || busy || prompt != null) return
        val request = ++generation
        checking = true
        action {
            try {
                val rule = if (Apps.safe(getApplication(), app.pkg)) store.eligibleRule(app.pkg) else null
                if (request == generation) {
                    if (rule == null) launch(app) else {
                        answer = ""
                        prompt = EntryQuestion(rule.question, app)
                    }
                }
            } finally { if (request == generation) checking = false }
        }
    }

    fun submit(launch: (LaunchApp) -> Unit) {
        val current = prompt ?: return
        if (busy) return
        val draft = answer
        busy = true
        action {
            try {
                store.submit(current.session, current.text, draft,
                    if (current.app == null) "TODO_ENTRY" else "LAUNCHER_ENTRY", current.app?.pkg)
                if (prompt == current) {
                    answer = ""
                    prompt = null
                    current.app?.let(launch)
                } else if (current.app != null) {
                    store.departedPackage(current.app.pkg)
                }
            } finally { busy = false }
        }
    }

    fun escape(pin: String, onInvalid: () -> Unit) {
        if (busy) return
        busy = true
        action {
            try {
                if (store.emergencyPause(pin)) {
                    prompt = null
                    answer = ""
                } else onInvalid()
            } finally { busy = false }
        }
    }

    fun reset() {
        generation++
        checking = false
        prompt = null
        answer = ""
        page = Page.TODAY
    }
}
