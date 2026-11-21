package dev.daily.todo

import androidx.room.*

@Entity(indices = [Index("date")])
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, val title: String, val done: Boolean = false,
    val createdAt: Long, val updatedAt: Long
)

@Entity(indices = [Index(value = ["sessionId"], unique = true), Index("date")])
data class ReflectionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val question: String, val answer: String,
    val source: String, val targetPackage: String?, val submittedAt: Long, val date: String
)

@Entity
data class GuardRule(
    @PrimaryKey val packageName: String, val label: String, val enabled: Boolean = true,
    val question: String = "使用这个应用的目的是什么？", val cooldownSeconds: Int = 900,
    val lastSubmittedAt: Long? = null
) {
    fun readyAt(now: Long) = isRuleReady(enabled, cooldownSeconds, lastSubmittedAt, now)
}

@Entity
internal data class LaunchPass(@PrimaryKey val slot: Int = 1, val packageName: String, val issuedAt: Long)

@Dao
internal interface DailyDao {
    @Query("SELECT * FROM TodoItem WHERE date=:date ORDER BY createdAt,id")
    fun todos(date: String): kotlinx.coroutines.flow.Flow<List<TodoItem>>
    @Query("SELECT * FROM ReflectionRecord WHERE date=:date ORDER BY submittedAt DESC,id DESC")
    fun reflections(date: String): kotlinx.coroutines.flow.Flow<List<ReflectionRecord>>
    @Query("SELECT date FROM TodoItem UNION SELECT date FROM ReflectionRecord ORDER BY date DESC")
    fun dates(): kotlinx.coroutines.flow.Flow<List<String>>
    @Query("SELECT * FROM GuardRule ORDER BY label,packageName")
    fun rules(): kotlinx.coroutines.flow.Flow<List<GuardRule>>
    @Insert suspend fun add(item: TodoItem)
    @Query("UPDATE TodoItem SET title=:title,updatedAt=:now WHERE id=:id")
    suspend fun rename(id: Long, title: String, now: Long)
    @Query("UPDATE TodoItem SET done=:done,updatedAt=:now WHERE id=:id")
    suspend fun setDone(id: Long, done: Boolean, now: Long)
    @Query("DELETE FROM TodoItem WHERE id=:id") suspend fun deleteTodo(id: Long)
    @Query("SELECT * FROM GuardRule WHERE packageName=:pkg") suspend fun rule(pkg: String): GuardRule?
    @Upsert suspend fun save(rule: GuardRule)
    @Query("DELETE FROM GuardRule WHERE packageName=:pkg") suspend fun deleteRule(pkg: String)
    @Query("SELECT EXISTS(SELECT 1 FROM ReflectionRecord WHERE source='TODO_ENTRY' AND date=:date)")
    suspend fun answeredToday(date: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM ReflectionRecord WHERE sessionId=:session)")
    suspend fun submitted(session: String): Boolean
    @Insert suspend fun insert(record: ReflectionRecord)
    @Query("UPDATE GuardRule SET lastSubmittedAt=:now WHERE packageName=:pkg")
    suspend fun markSubmitted(pkg: String, now: Long)
    @Upsert suspend fun grant(pass: LaunchPass)
    @Query("SELECT * FROM LaunchPass WHERE slot=1") suspend fun pass(): LaunchPass?
    @Query("DELETE FROM LaunchPass") suspend fun clearPass()
    @Query("DELETE FROM LaunchPass WHERE issuedAt <= :observedAt") suspend fun clearPassThrough(observedAt: Long)
    @Query("DELETE FROM LaunchPass WHERE packageName=:pkg AND issuedAt <= :observedAt")
    suspend fun revokePass(pkg: String, observedAt: Long)
    @Query("SELECT * FROM TodoItem ORDER BY date,id") suspend fun allTodos(): List<TodoItem>
    @Query("SELECT * FROM TodoItem ORDER BY date,id") fun observeAllTodos(): kotlinx.coroutines.flow.Flow<List<TodoItem>>
    @Query("SELECT * FROM ReflectionRecord ORDER BY id") suspend fun allAnswers(): List<ReflectionRecord>
    @Query("SELECT * FROM GuardRule ORDER BY packageName") suspend fun allRules(): List<GuardRule>
    @Query("DELETE FROM TodoItem") suspend fun clearTodos()
    @Query("DELETE FROM ReflectionRecord") suspend fun clearAnswers()
    @Query("DELETE FROM GuardRule") suspend fun clearRules()
}

@Database(entities = [TodoItem::class, ReflectionRecord::class, GuardRule::class, LaunchPass::class], version = 1, exportSchema = false)
internal abstract class DailyDatabase : RoomDatabase() {
    abstract fun dao(): DailyDao
}
