package app.novavpn.data.log

import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.LogLevel
import app.novavpn.domain.validation.InputValidators
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {
    private const val MAX_ENTRIES = 800
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormatThreadLocal = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val redacted = InputValidators.redactSensitiveForLog(message)
        val entry = LogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = redacted
        )
        val snapshot = synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            buffer.toList()
        }
        _logs.value = snapshot
    }

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
        _logs.value = emptyList()
    }

    fun exportText(): String {
        val fmt = timeFormatThreadLocal.get()!!
        val snapshot = _logs.value
        return buildString(snapshot.size * 72) {
            snapshot.forEachIndexed { idx, entry ->
                if (idx > 0) append('\n')
                append(fmt.format(Date(entry.timestampMs)))
                append(" [")
                append(entry.level.name)
                append('/')
                append(entry.tag)
                append("] ")
                append(entry.message)
            }
        }
    }
}
