package com.tingyun.smartmistakebook.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Classification of startup-recovery outcomes (spec §10.3).
 *
 * The sweep result used to be dropped, so a rolled-back or quarantined restore
 * was indistinguishable from a clean start. This pins which outcomes the app
 * has to tell the student about: the two that leave local data different from
 * what they last saw, and only those two.
 */
class RestoreRecoveryAttentionTest {

    @Test
    fun aCleanStartNeedsNoMessage() {
        assertEquals(
            RestoreRecoveryAttention.NONE,
            RestoreStartupOutcome.NothingToRecover.attentionRequired(),
        )
    }

    @Test
    fun preSwapCleanupLeavesLiveDataUntouchedSoItStaysSilent() {
        // The interrupted restore never reached the swap, so what the student
        // sees is exactly what they left — reporting it would be noise.
        assertEquals(
            RestoreRecoveryAttention.NONE,
            RestoreStartupOutcome.Cleaned(restoreId = "restore-1").attentionRequired(),
        )
    }

    @Test
    fun aRolledBackRestoreMustBeReportedBecauseItDidNotTakeEffect() {
        assertEquals(
            RestoreRecoveryAttention.RESTORE_REVERTED,
            RestoreStartupOutcome.RolledBack(restoreId = "restore-2").attentionRequired(),
        )
    }

    @Test
    fun quarantineAndUnreadableJournalsMustBeReported() {
        assertEquals(
            RestoreRecoveryAttention.DATA_QUARANTINED,
            RestoreStartupOutcome
                .Quarantined(restoreId = "restore-3", reason = "rollback failed")
                .attentionRequired(),
        )
        assertEquals(
            RestoreRecoveryAttention.DATA_QUARANTINED,
            RestoreStartupOutcome.Unreadable(reason = "journal not readable").attentionRequired(),
        )
    }

    @Test
    fun theTwoUnreportedOutcomesAreExactlyTheOnesThatDoNotMoveData() {
        // Every outcome is classified, and only outcomes that change what the
        // student's book contains can require a message.
        val silent = listOf<RestoreStartupOutcome>(
            RestoreStartupOutcome.NothingToRecover,
            RestoreStartupOutcome.Cleaned("restore-1"),
        )
        val reported = listOf<RestoreStartupOutcome>(
            RestoreStartupOutcome.RolledBack("restore-2"),
            RestoreStartupOutcome.Quarantined("restore-3", "rollback failed"),
            RestoreStartupOutcome.Unreadable("journal not readable"),
        )
        silent.forEach {
            assertEquals(RestoreRecoveryAttention.NONE, it.attentionRequired())
        }
        reported.forEach {
            check(it.attentionRequired() != RestoreRecoveryAttention.NONE) {
                "$it left local data changed but would not be reported"
            }
        }
    }
}
