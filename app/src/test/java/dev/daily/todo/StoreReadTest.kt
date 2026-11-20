package dev.daily.todo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class StoreReadTest {
    @Test fun aFailedReadCanBeRetriedWithoutInventingInitialData() = runBlocking {
        var reads = 0
        val updates = flow {
            if (++reads == 1) throw IOException()
            emit(listOf("保留的计划"))
        }.readUpdates()
        assertEquals(listOf(ReadUpdate.Failed), updates.toList())
        assertEquals(listOf(ReadUpdate.Value(listOf("保留的计划"))), updates.toList())
    }

    @Test fun aFailureAfterRealDataNeverPublishesAnEmptyList() = runBlocking {
        val values = flow {
            emit(listOf("原计划"))
            throw IOException()
        }.readUpdates().toList()
        assertEquals(listOf(ReadUpdate.Value(listOf("原计划")), ReadUpdate.Failed), values)
    }

    @Test(expected = CancellationException::class)
    fun lifecycleCancellationIsPropagated() {
        runBlocking { flow<Int> { throw CancellationException() }.readUpdates().toList() }
    }
}
