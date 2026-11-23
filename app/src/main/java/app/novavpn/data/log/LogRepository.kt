package app.novavpn.data.log

import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.LogLevel
import app.novavpn.domain.validation.InputValidators
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {
    private const val MAX_ENTRIES = 800
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        val redacted = InputValidators.redactSensitiveForLog(message)
        val entry = LogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = redacted
        )
        _logs.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.drop(next.size - MAX_ENTRIES) else next
        }
    }

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun clear() {
        _logs.update { emptyList() }
    }

    fun exportText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _logs.value.joinToString("\n") { entry ->
            "${fmt.format(Date(entry.timestampMs))} [${entry.level.name}/${entry.tag}] ${entry.message}"
        }
    }
}
