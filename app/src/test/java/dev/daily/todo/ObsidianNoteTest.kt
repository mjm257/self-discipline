package dev.daily.todo

import org.junit.Assert.*
import org.junit.Test

class ObsidianNoteTest {
    private val owner = "a30ea71c"
    private val task = TodoItem(7, "2026-11-20", "读书", false, 1, 1)
    @Test fun mergePreservesHandwrittenTasksOtherSectionsAndLineEndings() {
        val start = "# 日记\r\n\r\n## 今日 To Do List\r\n- [ ] 手写事项\r\n\r\n## 复盘反思\r\n不改动\r\n"
        val added = ObsidianNote.merge(start, owner, listOf(task))
        assertTrue(added.contains("- [ ] 读书 <!-- daily-todo:a30ea71c:7 -->\r\n"))
        assertTrue(added.indexOf("读书") < added.indexOf("## 复盘"))
        assertEquals(added, ObsidianNote.merge(added, owner, listOf(task)))
        val done = ObsidianNote.merge(added, owner, listOf(task.copy(done = true, title = "复习")))
        assertTrue(done.contains("- [x] 复习 "))
        assertEquals(start, ObsidianNote.merge(done, owner, emptyList()))
    }
    @Test fun newNoteAndMultilineTitle() {
        val note = ObsidianNote.merge("", owner, listOf(task.copy(title = "学习\n## 复盘<script>")))
        assertTrue(note.startsWith("## 今日待办\n- [ ] "))
        assertFalse(note.contains("\n## 复盘"))
        assertFalse(note.contains("<script>"))
    }
    @Test fun ignoresCodeExamplesAndOtherAuthorsMarkers() {
        val sample = "```md\n## 今日待办\n- [ ] 例子 <!-- daily-todo:a30ea71c:7 -->\n```\n"
        val original = sample + "## 今日待办\n- [x] 他人 <!-- daily-todo:other:7 -->\n"
        assertTrue(ObsidianNote.merge(original, owner, listOf(task)).contains(sample))
        assertTrue(ObsidianNote.merge(original, owner, listOf(task)).contains("他人 <!-- daily-todo:other:7 -->"))
    }
}
