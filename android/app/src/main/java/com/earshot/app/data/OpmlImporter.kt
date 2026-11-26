package com.earshot.app.data

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportStatus {
    data object Idle : ImportStatus
    data object Reading : ImportStatus
    data class Running(val done: Int, val total: Int) : ImportStatus
    data class Finished(val added: Int, val total: Int, val failed: List<String>) : ImportStatus
    data class Error(val message: String) : ImportStatus
}

/**
 * Runs an OPML import in the app-wide scope, so it keeps going when the user leaves the Add screen
 * or closes the app's window. Progress is published as [status] for any screen to show.
 * Importing again is safe: feeds that are already in the library are skipped quickly.
 */
class OpmlImporter(private val repo: Repository, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    val status: StateFlow<ImportStatus> = _status.asStateFlow()
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Returns false if an import is already running. */
    fun start(uri: Uri): Boolean {
        if (isRunning) return false
        job = scope.launch {
            _status.value = ImportStatus.Reading
            try {
                val feeds = repo.readOpml(uri)
                if (feeds.isEmpty()) {
                    _status.value = ImportStatus.Error("No podcast feeds found in that file")
                    return@launch
                }
                _status.value = ImportStatus.Running(0, feeds.size)
                val result = repo.importFeeds(feeds) { done, total ->
                    // Progress arrives from several worker threads; never let the count go backwards.
                    _status.update { cur ->
                        if (cur is ImportStatus.Running && cur.done >= done) cur else ImportStatus.Running(done, total)
                    }
                }
                _status.value = ImportStatus.Finished(result.added, result.added + result.failed.size, result.failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = ImportStatus.Error(e.message ?: "Couldn't read that file")
            }
        }
        return true
    }

    /** Clears a finished or failed import's summary. */
    fun dismiss() {
        if (!isRunning) _status.value = ImportStatus.Idle
    }
}
