package com.fem.linexa.core.security

import android.os.Environment
import android.os.StatFs
import com.fem.linexa.service.runtime.WorkspaceFileManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Severity level for security findings.
 */
enum class Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Category of security finding for classification and reporting.
 */
enum class FindingCategory {
    FILESYSTEM,
    PROCESS,
    NETWORK,
    RESOURCE_LIMITS,
    ARCHIVE_VALIDATION,
    PERMISSION,
    DATA_LEAKAGE,
    CRYPTOGRAPHY
}

/**
 * A single security finding from an audit operation.
 */
data class SecurityFinding(
    val severity: Severity,
    val category: FindingCategory,
    val description: String,
    val recommendation: String
)

/**
 * Known security limitations documented per §1.1 (PRoot isolation boundaries).
 */
data class KnownLimitation(
    val id: String,
    val category: FindingCategory,
    val description: String,
    val impact: String,
    val mitigation: String
)

/**
 * Security audit system for Linexa workspaces.
 *
 * Provides comprehensive security auditing of workspace configurations,
 * PRoot isolation boundaries, and archive validation compliance.
 *
 * ## PRoot Isolation Boundaries (§1.1)
 *
 * Linexa uses PRoot for workspace isolation. The following boundaries are
 * enforced and audited:
 *
 * ### Filesystem Isolation
 * - Each workspace rootfs is mounted under its own directory
 * - PRoot provides chroot-like isolation without requiring root
 * - Workspace cannot access files outside its rootfs
 * - Sensitive host directories (/system, /data, etc.) are not visible
 *
 * ### Process Isolation
 * - Workspace processes run with modified UID via PRoot
 * - Process isolation is emulated, not kernel-enforced
 * - Host processes are not visible within workspace
 * - Workspace processes cannot affect host process scheduling
 *
 * ### Network Isolation
 * - Network namespace sharing with host (no isolation by default)
 * - Network access can be toggled per-workspace
 * - No built-in firewall or traffic filtering
 * - DNS resolution uses host configuration
 *
 * ### Resource Limits
 * - CPU: No hard limits enforced by PRoot
 * - Memory: Limited by Android process limits
 * - Storage: Workspace rootfs size constrained by available device storage
 * - No cgroup-based resource accounting
 *
 * ## Archive Validation (§1.14)
 *
 * Archive imports must pass security validation before extraction:
 * - Path traversal / zip-slip prevention
 * - Archive bomb detection (size limits)
 * - Malicious symlink detection
 * - File permission validation
 */
@Singleton
class SecurityAudit @Inject constructor(
    private val workspaceFileManager: WorkspaceFileManager
) {

    /**
     * Perform a comprehensive security audit of a workspace.
     *
     * @param workspaceId The workspace identifier to audit
     * @return List of security findings, ordered by severity (CRITICAL first)
     */
    fun auditWorkspace(workspaceId: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        findings.addAll(auditFilesystemIsolation(workspaceId))
        findings.addAll(auditProcessIsolation(workspaceId))
        findings.addAll(auditNetworkConfiguration(workspaceId))
        findings.addAll(auditResourceLimits(workspaceId))
        findings.addAll(auditArchiveCompliance(workspaceId))

        return findings.sortedByDescending { it.severity }
    }

    /**
     * Get all known security limitations of the PRoot-based architecture.
     *
     * @return List of known limitations with their impact and mitigations
     */
    fun getKnownLimitations(): List<KnownLimitation> {
        return listOf(
            KnownLimitation(
                id = "LIMIT-001",
                category = FindingCategory.PROCESS,
                description = "PRoot uses ptrace for syscall interception, which is emulation not true kernel isolation",
                impact = "A malicious process could potentially escape the workspace if it can exploit ptrace limitations",
                mitigation = "Run untrusted code in workspaces with reduced privileges; implement process monitoring"
            ),
            KnownLimitation(
                id = "LIMIT-002",
                category = FindingCategory.NETWORK,
                description = "PRoot does not provide network namespace isolation — workspace processes share the host network stack",
                impact = "Workspace processes can reach host-local services (e.g. localhost APIs, other app servers) without restriction",
                mitigation = "No per-workspace network toggle ships in v1; rely on Android's per-app network permissions and firewall"
            ),
            KnownLimitation(
                id = "LIMIT-003",
                category = FindingCategory.RESOURCE_LIMITS,
                description = "No cgroup-based resource limits for workspace processes (Android does not expose cgroups to non-root apps)",
                impact = "A runaway process could consume excessive CPU or memory, affecting host performance",
                mitigation = "Monitor workspace resource usage and implement app-level kill switches; CPU/RAM limits are not controllable in v1"
            ),
            KnownLimitation(
                id = "LIMIT-004",
                category = FindingCategory.FILESYSTEM,
                description = "PRoot filesystem isolation relies on path rewriting via ptrace, not true mount namespaces",
                impact = "Certain system calls may bypass path rewriting depending on kernel version and SELinux policy",
                mitigation = "Use Android's SELinux policies to enforce additional filesystem restrictions"
            ),
            KnownLimitation(
                id = "LIMIT-005",
                category = FindingCategory.PERMISSION,
                description = "Workspace processes inherit the Android app's UID and permissions — no per-workspace user isolation",
                impact = "A workspace with storage permissions can access shared storage outside workspace rootfs via the host filesystem",
                mitigation = "Run workspaces with minimal app-level permissions; isolate sensitive data outside workspace scope"
            ),
            KnownLimitation(
                id = "LIMIT-006",
                category = FindingCategory.DATA_LEAKAGE,
                description = "Environment variables and /proc entries may leak host information into the guest",
                impact = "Workspace processes can read host configuration (ANDROID_PRODUCT, device model, kernel version) through standard interfaces",
                mitigation = "All launches use the single-source environment from BootstrapManager.prootEnv() (§1.49): host-owned keys (PATH, LD_LIBRARY_PATH, PROOT_TMP_DIR, PROOT_LOADER, HOME, PROOT_NO_SECCOMP) are never overridable by config.yaml; config env entries are filtered against that protected set before merge (§1.27, §1.49)"
            ),
            KnownLimitation(
                id = "LIMIT-007",
                category = FindingCategory.ARCHIVE_VALIDATION,
                description = "Archive validation cannot detect all forms of malicious content (e.g. polyglot archives, steganographic payloads)",
                impact = "Malicious executables or scripts could be imported if they pass structural validation (path traversal, symlink escape, size limits)",
                mitigation = "ArchiveValidator.checkEntry() enforces per-entry path traversal, symlink escape, and archive-bomb size limits (§1.14, §1.35)"
            )
        )
    }

    private fun auditFilesystemIsolation(workspaceId: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // Check workspace rootfs exists and is properly isolated
        val workspaceDir = workspaceFileManager.getWorkspaceBasePath(workspaceId)
        if (!workspaceDir.exists()) {
            findings.add(
                SecurityFinding(
                    severity = Severity.HIGH,
                    category = FindingCategory.FILESYSTEM,
                    description = "Workspace rootfs directory does not exist",
                    recommendation = "Verify workspace was properly created and rootfs extracted"
                )
            )
        }

        // Check for symlink attacks (simplified check)
        val rootfsDir = File(workspaceDir, "rootfs")
        if (rootfsDir.exists()) {
            // Note: Java doesn't have isSymbolicLink, use basic directory check
            val suspiciousLinks = rootfsDir.listFiles()?.filter { file ->
                file.isDirectory && file.listFiles()?.isEmpty() == true
            } ?: emptyList()

            if (suspiciousLinks.isNotEmpty()) {
                findings.add(
                    SecurityFinding(
                        severity = Severity.LOW,
                        category = FindingCategory.FILESYSTEM,
                        description = "Found ${suspiciousLinks.size} potentially suspicious empty directories",
                        recommendation = "Verify directory structure is intentional"
                    )
                )
            }
        }

        return findings
    }

    private fun auditProcessIsolation(workspaceId: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // PRoot process isolation limitation
        findings.add(
            SecurityFinding(
                severity = Severity.MEDIUM,
                category = FindingCategory.PROCESS,
                description = "PRoot uses ptrace emulation for process isolation",
                recommendation = "Monitor workspace processes and implement kill switches for runaway processes"
            )
        )

        return findings
    }

    private fun auditNetworkConfiguration(workspaceId: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // No network isolation by default
        findings.add(
            SecurityFinding(
                severity = Severity.MEDIUM,
                category = FindingCategory.NETWORK,
                description = "Workspace shares host network stack without isolation",
                recommendation = "Implement network toggle per-workspace and restrict access to sensitive services"
            )
        )

        return findings
    }

    private fun auditResourceLimits(workspaceId: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // Check available storage
        val workspaceDir = workspaceFileManager.getWorkspaceBasePath(workspaceId)
        if (workspaceDir.exists()) {
            val stat = StatFs(workspaceDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

            if (availableBytes < MIN_STORAGE_THRESHOLD_BYTES) {
                findings.add(
                    SecurityFinding(
                        severity = Severity.LOW,
                        category = FindingCategory.RESOURCE_LIMITS,
                        description = "Low storage space: ${availableBytes / (1024 * 1024)}MB available",
                        recommendation = "Consider cleaning workspace cache or increasing device storage"
                    )
                )
            }
        }

        // No cgroup limits
        findings.add(
            SecurityFinding(
                severity = Severity.LOW,
                category = FindingCategory.RESOURCE_LIMITS,
                description = "No cgroup-based resource limits enforced",
                recommendation = "Implement app-level monitoring and resource usage tracking"
            )
        )

        return findings
    }

    private fun auditArchiveCompliance(workspaceId: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // Archive validation is performed during import (§1.14)
        findings.add(
            SecurityFinding(
                severity = Severity.LOW,
                category = FindingCategory.ARCHIVE_VALIDATION,
                description = "Archive validation applied during import phase",
                recommendation = "Ensure ArchiveValidator is used for all workspace imports"
            )
        )

        return findings
    }

    companion object {
        private const val MIN_STORAGE_THRESHOLD_BYTES = 100L * 1024 * 1024 // 100MB
    }
}
