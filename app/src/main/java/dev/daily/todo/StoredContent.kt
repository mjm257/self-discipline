package dev.daily.todo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal sealed interface ReadUpdate<out T> {
    data class Value<T>(val value: T) : ReadUpdate<T>
    data object Failed : ReadUpdate<Nothing>
}

internal fun <T> Flow<T>.readUpdates(): Flow<ReadUpdate<T>> = map<T, ReadUpdate<T>> {
    ReadUpdate.Value(it)
}.catch { failure ->
    if (failure is CancellationException || failure !is Exception) throw failure
    emit(ReadUpdate.Failed)
}

@Composable
internal fun <T : Any> StoredContent(
    stream: Flow<T>, label: String, content: @Composable (T) -> Unit
) {
    var latest by remember(stream) { mutableStateOf<T?>(null) }
    var failed by remember(stream) { mutableStateOf(false) }
    var attempt by remember(stream) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(stream, attempt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            stream.readUpdates().collect { update ->
                when (update) {
                    is ReadUpdate.Value -> {
                        latest = update.value
                        failed = false
                    }
                    ReadUpdate.Failed -> failed = true
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (failed) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("无法读取" + label + "。", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { failed = false; attempt++ }) { Text("重试") }
            }
        }
        val value = latest
        if (value != null) content(value)
        else if (!failed) CircularProgressIndicator(Modifier.padding(24.dp))
    }
}
