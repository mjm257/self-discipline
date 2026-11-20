package dev.daily.todo

import java.util.UUID

internal class GuardSessions {
    class Visit(val pkg: String, var released: Boolean = false)
    class Draft(val question: String, val id: String = UUID.randomUUID().toString(), var text: String = "")
    private val drafts = mutableMapOf<String, Draft>()
    private var lastConnection: Pair<Visit, Long>? = null
    var current: Visit? = null
        private set
    var hasForegroundEvidence = false
        private set

    fun enter(pkg: String, elapsedMs: Long): Visit {
        hasForegroundEvidence = true
        current?.takeIf { it.pkg == pkg }?.let { return it }
        val recent = lastConnection
        lastConnection = null
        val visit = recent?.first?.takeIf { it.pkg == pkg && elapsedMs - recent.second in 0L..30_000L }
            ?: Visit(pkg)
        current = visit
        return visit
    }

    fun obscure() { hasForegroundEvidence = false }

    fun leave() {
        obscure()
        current = null
        lastConnection = null
    }

    fun disconnect(elapsedMs: Long) {
        current?.let { lastConnection = it to elapsedMs }
        current = null
        obscure()
    }

    fun draft(pkg: String, question: String): Draft = drafts[pkg]?.takeIf { it.question == question }
        ?: Draft(question).also { drafts[pkg] = it }

    fun forget(pkg: String, draft: Draft) { drafts.remove(pkg, draft) }
    fun prune(enabled: Set<String>) { drafts.keys.retainAll(enabled) }
}
