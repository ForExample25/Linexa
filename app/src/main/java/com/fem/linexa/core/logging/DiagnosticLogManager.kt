package com.fem.linexa.core.logging

import android.app.Application
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.53: Persistent per-workspace diagnostic log manager.
 *
 * Every installation automatically generates persistent logs under
 * `{workspace}/logs/`. These survive process death, app restarts, and
 * device reboots — they are never auto-deleted on failure.
 *
 * Log files:
 *   bootstrap.log        — BootstrapManager lifecycle (setup, validation, binaries)
 *   extraction.log       — Native tar extraction (argv, env, paths, proot output)
 *   runtime.log          — PRootContainerRuntime session lifecycle
 *   diagnostics.log      — Full diagnoseAll() dumps at each pipeline stage
 *   proot-command.log    — Exact proot commands + shell-copyable strings
 *   environment.log      — Complete env dumps (sorted, highlighted)
 *   latest-error.log     — The most recent failure's full report (kept across retries)
 *
 * If installation fails, every log is preserved. The latest-error.log is
 * always overwritten with the most recent failure so it's easy to find.
 */
@Singleton
class DiagnosticLogManager @Inject constructor(
    private val application: Application
) {

    /**
     * §1.53: Returns the logs directory for a workspace, creating it if needed.
     * Location: `{app.filesDir}/workspaces/{workspaceId}/logs/`
     */
    fun getLogsDir(workspaceId: String): File {
        val base = File(application.filesDir, "workspaces")
        val dir = File(base, "$workspaceId/logs")
        dir.mkdirs()
        return dir
    }

    /**
     * §1.53: Write a diagnostic entry to a named log file within a workspace's logs directory.
     * Appends to the file. Thread-safe (synchronized on the file).
     */
    fun appendLog(workspaceId: String, fileName: String, content: String) {
        try {
            val logFile = File(getLogsDir(workspaceId), fileName)
            synchronized(logFile) {
                FileWriter(logFile, true).use { writer ->
                    writer.append(content)
                    writer.append("\n")
                    writer.flush()
                }
            }
        } catch (_: Exception) {
            // Best effort — don't crash the app for logging failures
        }
    }

    /**
     * §1.53: Write the full bootstrap diagnostics to the diagnostics log.
     */
    fun logBootstrapDiagnostics(workspaceId: String, diagnoseAll: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        appendLog(workspaceId, "diagnostics.log", "[$timestamp] === Bootstrap Diagnostics ===\n$diagnoseAll\n")
    }

    /**
     * §1.53: Write extraction command, env, and paths to the extraction log.
     */
    fun logExtractionCommand(
        workspaceId: String,
        fullArgv: List<String>,
        prootRunEnv: Map<String, String>,
        shellCopyable: String,
        archivePath: String,
        stagingPath: String,
        elapsedMs: Long
    ) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] === Extraction Command ===")
        sb.appendLine("Full argv:")
        fullArgv.forEachIndexed { i, arg ->
            sb.appendLine("  argv[$i] = ${arg.take(512)}")
        }
        sb.appendLine("Shell-copyable: $shellCopyable")
        sb.appendLine("Archive: $archivePath")
        sb.appendLine("Staging: $stagingPath")
        sb.appendLine("Elapsed: ${elapsedMs}ms")
        sb.appendLine()
        sb.appendLine("[$timestamp] === Extraction Environment ===")
        prootRunEnv.toSortedMap().forEach { (key, value) ->
            sb.appendLine("  $key = $value")
        }
        sb.appendLine()
        appendLog(workspaceId, "extraction.log", sb.toString())
    }

    /**
     * §1.53: Write extraction result (success or failure).
     */
    fun logExtractionResult(
        workspaceId: String,
        success: Boolean,
        exitCode: Int?,
        outputTail: String?,
        elapsedMs: Long
    ) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] === Extraction Result ===")
        sb.appendLine("Success: $success")
        if (exitCode != null) sb.appendLine("Exit code: $exitCode")
        sb.appendLine("Elapsed: ${elapsedMs}ms")
        if (outputTail != null) {
            sb.appendLine("Output tail:")
            sb.appendLine(outputTail)
        }
        sb.appendLine()
        appendLog(workspaceId, "extraction.log", sb.toString())
    }

    /**
     * §1.53: Write a proot command entry.
     */
    fun logProotCommand(
        workspaceId: String,
        command: String,
        env: Map<String, String>,
        cwd: String?,
        rootfsPath: String?,
        bindMounts: List<String>?
    ) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] === PRoot Command ===")
        sb.appendLine("Command: $command")
        if (cwd != null) sb.appendLine("CWD: $cwd")
        if (rootfsPath != null) sb.appendLine("Rootfs: $rootfsPath")
        if (bindMounts != null) {
            sb.appendLine("Bind mounts:")
            bindMounts.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine("Environment:")
        env.toSortedMap().forEach { (key, value) ->
            sb.appendLine("  $key = $value")
        }
        sb.appendLine()
        appendLog(workspaceId, "proot-command.log", sb.toString())
    }

    /**
     * §1.53: Write a full environment dump.
     */
    fun logEnvironment(workspaceId: String, label: String, env: Map<String, String>) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] === Environment: $label ===")
        env.toSortedMap().forEach { (key, value) ->
            sb.appendLine("  $key = $value")
        }
        sb.appendLine()
        appendLog(workspaceId, "environment.log", sb.toString())
    }

    /**
     * §1.53: Write the full error report for a failed installation.
     * Also writes to latest-error.log (overwrites previous failure).
     */
    fun logError(workspaceId: String, report: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        appendLog(workspaceId, "latest-error.log", "[$timestamp] === Error Report ===\n$report\n")

        // Also append to a rolling error history
        appendLog(workspaceId, "error-history.log", "[$timestamp] === Error Report ===\n$report\n---\n")
    }

    /**
     * §1.53: Write runtime session lifecycle events.
     */
    fun logRuntimeEvent(workspaceId: String, event: String, details: String? = null) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] === Runtime Event ===")
        sb.appendLine("Event: $event")
        if (details != null) sb.appendLine("Details: $details")
        sb.appendLine()
        appendLog(workspaceId, "runtime.log", sb.toString())
    }

    /**
     * §1.53: List all log files for a workspace.
     */
    fun listLogs(workspaceId: String): List<File> {
        return getLogsDir(workspaceId).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * §1.53: Read the content of a specific log file.
     */
    fun readLog(workspaceId: String, fileName: String): String? {
        return try {
            val logFile = File(getLogsDir(workspaceId), fileName)
            if (logFile.exists()) logFile.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * §1.53: Get the total size of all logs for a workspace.
     */
    fun getLogsSize(workspaceId: String): Long {
        return listLogs(workspaceId).sumOf { it.length() }
    }

    /**
     * §1.53: Format a human-readable log path for display in the UI.
     */
    fun getLogPathDisplay(workspaceId: String): String {
        return getLogsDir(workspaceId).absolutePath
    }
}
