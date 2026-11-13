package com.fem.linexa.service.workspace

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.repository.WorkspaceRepositoryImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P11: encrypts workspace secrets that were stored as plaintext before encryption
 * existed (today: the desktop VNC password).
 *
 * Without this, a workspace the user never edits would keep its password in the
 * database in the clear indefinitely: new and changed values are encrypted on write, but
 * an untouched row is never written. The sweep is idempotent, so it runs at every
 * process start (see `LinexaApplication`); a start with nothing to migrate costs one
 * read. A failure is logged and swallowed — a broken Keystore must not stop the app, and
 * the legacy value is left in place for the next attempt.
 *
 * Follows the `WorkspaceMetadataBackfillManager` pattern.
 */
@Singleton
class VncPasswordMigrationManager @Inject constructor(
    private val repository: WorkspaceRepositoryImpl
) {

    suspend fun migrate() {
        try {
            val rewritten = repository.migrateLegacySecrets()
            if (rewritten > 0) {
                Logger.i(TAG, "Encrypted legacy workspace secrets for $rewritten workspace(s)")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Legacy secret migration failed; will retry on next start: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "VncPasswordMigration"
    }
}
