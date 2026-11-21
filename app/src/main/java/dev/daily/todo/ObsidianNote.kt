package dev.daily.todo

/** Update only our marked checklist rows inside the daily task section. */
internal object ObsidianNote {
    private val lines = Regex("""[^\r\n]*(?:\r\n|\n|\r)|[^\r\n]+$""")
    private val heading = Regex("""^##[ \t]+(?:今日待办|今日\s*to\s*do\s*list|to\s*do\s*list)\s*#*\s*$""", RegexOption.IGNORE_CASE)
    private val sectionEnd = Regex("""^#{1,2}(?:\s+|$).*""")
    private val fenceStart = Regex("""^ {0,3}(`{3,}|~{3,}).*$""")
    private val mark = Regex("""^- \[[ xX]\] .* <!-- daily-todo:([a-zA-Z0-9-]+):(\d+) -->$""")
    private val markdown = setOf('\\', '*', '_', '[', ']', '`', '~', '|', '#', '^', '=')

    private fun literal(title: String) = buildString {
        for (c in title) when {
            c == '\n' || c == '\r' || c == '\t' -> append(' ')
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '&' -> append("&amp;")
            c == '%' -> append("&#37;")
            c in markdown -> { append('\\'); append(c) }
            else -> append(c)
        }
    }

    fun merge(original: String, owner: String, tasks: List<TodoItem>): String {
        val rows = lines.findAll(original).map { it.value }.toMutableList()
        val nl = Regex("""\r\n|\n|\r""").find(original)?.value ?: "\n"
        val active = BooleanArray(rows.size)
        var frontmatter = rows.firstOrNull()?.trimEnd('\r', '\n')?.removePrefix("\uFEFF") == "---"
        var fence = ""
        for (i in rows.indices) {
            val line = rows[i].trimEnd('\r', '\n')
            if (frontmatter) {
                if (i > 0 && (line == "---" || line == "...")) frontmatter = false
            } else if (fence.isNotEmpty()) {
                val closing = line.trim()
                if (closing.length >= fence.length && closing.all { it == fence.first() }) fence = ""
            } else {
                fence = fenceStart.matchEntire(line)?.groupValues?.get(1).orEmpty()
                active[i] = fence.isEmpty()
            }
        }
        val start = rows.indices.firstOrNull { active[it] && heading.matches(rows[it].trimEnd().removePrefix("\uFEFF")) }
        fun render(t: TodoItem) = "- [" + (if (t.done) "x" else " ") + "] " + literal(t.title) +
            " <!-- daily-todo:" + owner + ":" + t.id + " -->"
        if (start == null) {
            if (tasks.isEmpty()) return original
            require(!frontmatter && fence.isEmpty()) { "日记中有未闭合的代码块或属性区，已保留原文。" }
            val gap = if (original.isEmpty()) "" else if (original.endsWith(nl)) nl else nl + nl
            return original + gap + "## 今日待办" + nl + tasks.joinToString(nl, postfix = nl, transform = ::render)
        }
        val end = (start + 1 until rows.size).firstOrNull {
            active[it] && sectionEnd.matches(rows[it].trimEnd())
        } ?: rows.size
        val pending = tasks.associateByTo(linkedMapOf()) { it.id }
        for (i in start + 1 until end) {
            if (!active[i]) continue
            val raw = rows[i].trimEnd('\r', '\n')
            val marker = mark.matchEntire(raw) ?: continue
            if (marker.groupValues[1] != owner) continue
            val id = marker.groupValues[2].toLongOrNull() ?: continue
            val task = pending.remove(id)
            rows[i] = if (task == null) "" else render(task) + rows[i].substring(raw.length)
        }
        if (pending.isNotEmpty()) {
            if (!rows[start].endsWith('\r') && !rows[start].endsWith('\n')) rows[start] += nl
            rows.add(start + 1, pending.values.joinToString(nl, postfix = nl, transform = ::render))
        }
        return rows.joinToString("")
    }
}
