package com.fem.linexa.service.runtime.pty

import com.fem.linexa.service.runtime.RuntimeTrace
import javax.inject.Inject
import javax.inject.Singleton

/** Default [PtySessionFactory], producing [TermuxPtySession]s. */
@Singleton
class TermuxPtySessionFactory @Inject constructor(
    private val runtimeTrace: RuntimeTrace
) : PtySessionFactory {

    override fun create(
        workspaceId: String,
        executable: String,
        args: List<String>,
        env: Map<String, String>,
        workingDirectory: String,
        columns: Int,
        rows: Int,
        terminalConfigHome: String?
    ): PtySession = TermuxPtySession(
        workspaceId = workspaceId,
        executable = executable,
        args = args,
        env = env,
        workingDirectory = workingDirectory,
        columns = columns,
        rows = rows,
        terminalConfigHome = terminalConfigHome,
        runtimeTrace = runtimeTrace
    )
}
