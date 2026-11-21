package dev.daily.todo

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val Context.dailyPrefs by preferencesDataStore("daily_settings")
private object Keys {
    val question = stringPreferencesKey("daily_question")
    val policy = stringPreferencesKey("entry_policy")
    val salt = stringPreferencesKey("pin_salt")
    val hash = stringPreferencesKey("pin_hash")
    val rounds = intPreferencesKey("pbkdf2_sha256_iterations")
    val guardUntil = longPreferencesKey("guard_paused_until")
    val allUntil = longPreferencesKey("all_paused_until")
    val clearing = booleanPreferencesKey("clear_in_progress")
}

class Store private constructor(context: Context) {
    private val db = Room.databaseBuilder(context, DailyDatabase::class.java, "daily.db").build()
    private val dao = db.dao()
    private val prefs = context.dailyPrefs
    private val lock = Mutex()
    private var recovered = false

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }

    // A clear spans two files. Finish any interrupted reset before exposing either one.
    private suspend fun finishClear() {
        db.withTransaction {
            dao.clearPass()
            dao.clearAnswers()
            dao.clearTodos()
            dao.clearRules()
        }
        prefs.edit { it.clear() }
    }

    private suspend fun <T> access(action: suspend () -> T): T = lock.withLock {
        if (!recovered) {
            if (prefs.data.first()[Keys.clearing] == true) finishClear()
            recovered = true
        }
        action()
    }

    private fun <T> Flow<T>.afterRecovery(): Flow<T> = onStart { access { } }
    private fun Preferences.view(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            dailyQuestion = this[Keys.question] ?: defaults.dailyQuestion,
            entryPolicy = EntryPolicy.values().find { it.name == this[Keys.policy] } ?: defaults.entryPolicy,
            hasPin = this[Keys.hash] != null && this[Keys.salt] != null && this[Keys.rounds] != null,
            guardPausedUntil = this[Keys.guardUntil] ?: 0,
            allPausedUntil = this[Keys.allUntil] ?: 0
        )
    }
    val settings: Flow<AppSettings> = prefs.data.filter { it[Keys.clearing] != true }
        .map { it.view() }.distinctUntilChanged().afterRecovery()
    val rules = dao.rules().afterRecovery()
    val dates = dao.dates().afterRecovery()
    internal val allPlans = dao.observeAllTodos().afterRecovery()
    fun todos(date: String) = dao.todos(date).afterRecovery()
    fun reflections(date: String) = dao.reflections(date).afterRecovery()

    suspend fun addTodo(date: String, title: String) = access {
        LocalDate.parse(date)
        require(title.isNotBlank()) { "请输入计划标题" }
        val now = System.currentTimeMillis()
        dao.add(TodoItem(date = date, title = title.trim(), createdAt = now, updatedAt = now))
    }
    suspend fun renameTodo(id: Long, title: String) = access {
        require(title.isNotBlank()) { "请输入计划标题" }
        dao.rename(id, title.trim(), System.currentTimeMillis())
    }
    suspend fun setDone(id: Long, done: Boolean) = access { dao.setDone(id, done, System.currentTimeMillis()) }
    suspend fun deleteTodo(id: Long) = access { dao.deleteTodo(id) }

    suspend fun saveRule(rule: GuardRule) = access {
        require(rule.packageName.isNotBlank() && rule.question.isNotBlank() && rule.cooldownSeconds >= 0) {
            "请填写应用、问题及有效的冷却时间"
        }
        db.withTransaction {
            dao.save(rule.copy(lastSubmittedAt = dao.rule(rule.packageName)?.lastSubmittedAt))
            if (!rule.enabled) dao.revokePass(rule.packageName, Long.MAX_VALUE)
        }
    }
    suspend fun deleteRule(packageName: String) = access {
        db.withTransaction {
            dao.deleteRule(packageName)
            dao.revokePass(packageName, Long.MAX_VALUE)
        }
    }
    suspend fun updateSettings(dailyQuestion: String, entryPolicy: EntryPolicy) = access {
        require(dailyQuestion.isNotBlank()) { "问题不能为空" }
        prefs.edit {
            it[Keys.question] = dailyQuestion.trim()
            it[Keys.policy] = entryPolicy.name
        }
        Unit
    }
    suspend fun setPin(pin: String) = access {
        require(pin.matches(Regex("[0-9]{4,12}"))) { "请输入 4 至 12 位数字 PIN" }
        val salt = PinHash.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHash.encode(pin, salt) }
        prefs.edit {
            it[Keys.salt] = salt
            it[Keys.hash] = hash
            it[Keys.rounds] = PinHash.iterations
        }
        Unit
    }
    private suspend fun matchesPin(pin: String): Boolean {
        if (pin.length !in 4..12) return false
        val values = prefs.data.first()
        val salt = values[Keys.salt] ?: return false
        val hash = values[Keys.hash] ?: return false
        val rounds = values[Keys.rounds] ?: return false
        return withContext(Dispatchers.Default) { PinHash.matches(pin, salt, rounds, hash) }
    }
    suspend fun verifyPin(pin: String) = access { matchesPin(pin) }
    suspend fun pauseGuard() = access {
        prefs.edit { it[Keys.guardUntil] = System.currentTimeMillis() + 30 * 60_000L }
        Unit
    }
    suspend fun emergencyPause(pin: String) = access {
        if (!matchesPin(pin)) return@access false
        prefs.edit { it[Keys.allUntil] = System.currentTimeMillis() + 30 * 60_000L }
        true
    }
    suspend fun shouldAskToday(date: String): Boolean = access {
        val s = prefs.data.first().view()
        s.askingAt(System.currentTimeMillis()) && (s.entryPolicy == EntryPolicy.EVERY_ENTRY || !dao.answeredToday(date))
    }
    suspend fun eligibleRule(packageName: String): GuardRule? = access {
        val s = prefs.data.first().view()
        val now = System.currentTimeMillis()
        if (s.guardingAt(now)) dao.rule(packageName)?.takeIf { it.readyAt(now) } else null
    }

    suspend fun submit(sessionId: String, question: String, answer: String, source: String, targetPackage: String? = null) = access {
        require(sessionId.isNotBlank() && question.isNotBlank())
        require(source in setOf("TODO_ENTRY", "OVERLAY", "LAUNCHER_ENTRY"))
        require(if (source == "TODO_ENTRY") targetPackage == null else !targetPackage.isNullOrBlank())
        val s = prefs.data.first().view()
        db.withTransaction {
            if (dao.submitted(sessionId)) return@withTransaction
            require(canSubmitAnswer(answer)) { "有效字数不足" }
            val now = System.currentTimeMillis()
            require(s.askingAt(now)) { "当前已暂停提问" }
            if (targetPackage != null) {
                require(s.guardingAt(now) && dao.rule(targetPackage)?.enabled == true) { "此规则已暂停或关闭" }
            }
            val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            dao.insert(ReflectionRecord(sessionId = sessionId, question = question, answer = answer,
                source = source, targetPackage = targetPackage, submittedAt = now, date = date))
            if (targetPackage != null) {
                dao.markSubmitted(targetPackage, now)
                if (source == "LAUNCHER_ENTRY") dao.grant(LaunchPass(packageName = targetPackage, issuedAt = now))
            }
        }
    }

    suspend fun consumeLaunchPass(packageName: String, observedAt: Long = System.currentTimeMillis()): Boolean = access {
        db.withTransaction {
            val pass = dao.pass() ?: return@withTransaction false
            if (pass.issuedAt > observedAt) return@withTransaction false
            dao.clearPassThrough(observedAt)
            pass.packageName == packageName && observedAt - pass.issuedAt in 0L..30_000L
        }
    }
    suspend fun departedPackage(previousPkg: String, observedAt: Long = System.currentTimeMillis()) =
        access { dao.revokePass(previousPkg, observedAt) }
    suspend fun clearLaunchPass(observedAt: Long = System.currentTimeMillis()) =
        access { dao.clearPassThrough(observedAt) }

    suspend fun clearAll() = access {
        recovered = false
        prefs.edit { it[Keys.clearing] = true }
        finishClear()
        recovered = true
    }

    suspend fun exportJson(): String = access {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val todos = JSONArray()
                dao.allTodos().forEach { t -> todos.put(JSONObject().put("id",t.id).put("date",t.date)
                    .put("title",t.title).put("done",t.done).put("createdAt",t.createdAt).put("updatedAt",t.updatedAt)) }
                val answers = JSONArray()
                dao.allAnswers().forEach { r -> answers.put(JSONObject().put("id",r.id).put("date",r.date)
                    .put("question",r.question).put("answer",r.answer).put("source",r.source)
                    .put("targetPackage",r.targetPackage ?: JSONObject.NULL).put("submittedAt",r.submittedAt)) }
                val rules = JSONArray()
                dao.allRules().forEach { r -> rules.put(JSONObject().put("packageName",r.packageName)
                    .put("label",r.label).put("enabled",r.enabled).put("question",r.question)
                    .put("cooldownSeconds",r.cooldownSeconds).put("lastSubmittedAt",r.lastSubmittedAt ?: JSONObject.NULL)) }
                JSONObject().put("schemaVersion",1).put("todos",todos).put("reflections",answers).put("rules",rules).toString(2)
            }
        }
    }
}
