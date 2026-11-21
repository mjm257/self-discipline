package dev.daily.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ObsidianStatus(val folder: String? = null, val message: String = "")

internal class ObsidianSync(private val context: Context, private val store: Store) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("obsidian_sync", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val folder get() = prefs.getString("tree", null)?.let(Uri::parse)
    private val progress = MutableStateFlow(ObsidianStatus(folder?.folderName(), "等待同步"))
    val status = progress.asStateFlow()
    private val synced = mutableMapOf<String, List<TodoItem>>()

    suspend fun connect(uri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) {
                "请选择手机本地存储中 Obsidian 库的每日笔记文件夹。"
            }
            val old = folder
            resolver.takePersistableUriPermission(uri, GRANT)
            val editor = prefs.edit().putString("tree", uri.toString())
            if (!prefs.contains("owner")) editor.putString("owner", UUID.randomUUID().toString())
            if (!editor.commit()) {
                if (old != uri) release(uri)
                error("保存文件夹连接失败，请重试。")
            }
            if (old != null && old != uri) release(old)
            synced.clear()
            progress.value = ObsidianStatus(uri.folderName(), "等待同步")
        }
        sync(force = true)
    }

    suspend fun disconnect(clearLocalState: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val old = folder
            val editor = prefs.edit()
            if (clearLocalState) editor.clear() else editor.remove("tree")
            check(editor.commit()) { "保存断开状态失败，请重试。" }
            old?.let(::release)
            synced.clear()
            progress.value = ObsidianStatus()
            if (clearLocalState) {
                context.noBackupFilesDir.listFiles().orEmpty().filter { JOURNAL_FILE.matches(it.name) }
                    .forEach { check(it.delete()) { "无法清除同步恢复文件，请重试。" } }
            }
        }
    }

    suspend fun sync(force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val tree = folder ?: return@withLock
            val label = tree.folderName()
            try {
                val owner = checkNotNull(prefs.getString("owner", null))
                val tasks = store.allPlans.first().groupBy(TodoItem::date)
                val tracked = prefs.getStringSet("dates", emptySet()).orEmpty()
                val dates = (tracked + tasks.keys).filter { force || synced[it] != tasks[it].orEmpty() }
                if (dates.isEmpty() && !force && progress.value.message != "等待同步") return@withLock
                progress.value = ObsidianStatus(label, "正在同步…")
                val documents = dailyDocuments(tree)
                // Remember dates before writing so deleting the final task still removes its row after a restart.
                check(prefs.edit().putStringSet("dates", tracked + tasks.keys).commit()) {
                    "保存同步进度失败，请重试。"
                }
                val failures = mutableListOf<String>()
                for (date in dates.sorted()) {
                    try {
                        val plans = tasks[date].orEmpty()
                        updateNote(tree, documents, date, plans, owner)
                        synced[date] = plans
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        throw e
                    } catch (_: Exception) {
                        synced.remove(date)
                        failures += date
                    }
                }
                progress.value = ObsidianStatus(label, if (failures.isEmpty()) "已同步至手机本地笔记"
                    else "${failures.first()} 等 ${failures.size} 篇日记写入失败，请检查文件后重试。")
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                progress.value = ObsidianStatus(label, "文件夹授权已失效，请重新选择。")
            } catch (_: Exception) {
                progress.value = ObsidianStatus(label, "暂时无法同步，请检查文件夹后重试。")
            }
        }
    }

    private fun dailyDocuments(tree: Uri): Map<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return buildMap {
            val cursor = resolver.query(children, columns, null, null, null) ?: error("无法读取文件夹。")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    if (DAILY_FILE.matches(name) && it.getString(2) != Document.MIME_TYPE_DIR) {
                        check(put(name, DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))) == null)
                    }
                }
            }
        }
    }

    private fun updateNote(tree: Uri, documents: Map<String, Uri>, date: String, plans: List<TodoItem>, owner: String) {
        require(LocalDate.parse(date).toString() == date && DAILY_FILE.matches("$date.md"))
        val name = "$date.md"
        val existing = documents[name]
        if (existing == null && plans.isEmpty()) return
        existing?.let { recover(it, journal(it)) }
        val original = existing?.let(::read) ?: byteArrayOf()
        val content = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original)).toString()
        val merged = ObsidianNote.merge(content, owner, plans)
        if (merged == content) return
        val output = merged.toByteArray(Charsets.UTF_8)
        check(output.size <= MAX_BYTES)
        val uri = existing ?: run {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val created = DocumentsContract.createDocument(resolver, parent, "text/markdown", name)
                ?: error("无法创建每日笔记。")
            val display = resolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            check(display == name) { "每日笔记文件名已被占用。" }
            created
        }

        val backup = journal(uri)
        check(read(uri).contentEquals(original)) { "笔记刚被修改，请重试。" }
        val handle = backup.startWrite()
        try {
            val record = DataOutputStream(handle)
            record.writeInt(original.size)
            record.write(original)
            record.writeInt(output.size)
            record.write(output)
            backup.finishWrite(handle)
        } catch (e: Exception) {
            backup.failWrite(handle)
            throw e
        }
        if (!read(uri).contentEquals(original)) {
            backup.delete()
            error("笔记刚被修改，请重试。")
        }
        try {
            write(uri, output)
            check(read(uri).contentEquals(output))
            backup.delete()
        } catch (e: Exception) {
            runCatching { recover(uri, backup) }
            throw e
        }
    }

    private fun journal(uri: Uri): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.noBackupFilesDir, "obsidian-$key.pending"))
    }

    private fun recover(uri: Uri, backup: AtomicFile) {
        if (!backup.baseFile.exists()) return
        val (before, after) = DataInputStream(backup.openRead()).use { record ->
            fun bytes(): ByteArray {
                val size = record.readInt()
                check(size in 0..MAX_BYTES)
                return ByteArray(size).also { record.readFully(it) }
            }
            bytes() to bytes()
        }
        val current = read(uri)
        if (!current.contentEquals(before) && !current.contentEquals(after)) {
            fun isPartial(bytes: ByteArray) = current.size < bytes.size && current.indices.all { current[it] == bytes[it] }
            check(isPartial(after) || isPartial(before)) {
                "笔记在中断后被其他应用修改。"
            }
            write(uri, before)
            check(read(uri).contentEquals(before))
        }
        backup.delete()
    }

    private fun read(uri: Uri): ByteArray {
        val stream = resolver.openInputStream(uri) ?: error("无法读取笔记。")
        return stream.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count == -1) break
                check(out.size() + count <= MAX_BYTES) { "笔记过大。" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        val stream = resolver.openOutputStream(uri, "wt") ?: error("无法写入笔记。")
        stream.use {
            it.write(bytes)
            (it as? FileOutputStream)?.fd?.sync()
        }
    }

    private fun Uri.folderName() = DocumentsContract.getTreeDocumentId(this).substringAfter(':')
    private fun release(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, GRANT) }
    }

    companion object {
        private const val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val DAILY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.md""")
        private val JOURNAL_FILE = Regex("""obsidian-[0-9a-f]{64}\.pending(?:\.new|\.bak)?""")
    }
}
