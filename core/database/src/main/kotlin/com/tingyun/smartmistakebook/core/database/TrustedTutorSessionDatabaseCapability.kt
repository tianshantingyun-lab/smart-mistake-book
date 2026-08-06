package com.tingyun.smartmistakebook.core.database

/**
 * Non-forgeable production access to tutor session coordination.
 *
 * It exposes conversation lifecycle, cancellation, and the v44 evidence intent/acknowledgement
 * protocol. It has no submitted legacy fact writer.
 */
sealed interface TrustedTutorSessionDatabaseCapability :
    TutorConversationSessionDatabasePort,
    TutorLearningEvidenceSessionDatabasePort,
    CurrentTutorInteractionSessionDatabasePort,
    CurrentTutorSessionHostWorkDatabasePort

internal class RoomTrustedTutorSessionDatabaseCapability(
    database: RoomStudyDatabase,
) : TrustedTutorSessionDatabaseCapability,
    TutorConversationSessionDatabasePort by database,
    TutorLearningEvidenceSessionDatabasePort by database,
    CurrentTutorInteractionSessionDatabasePort by database,
    CurrentTutorSessionHostWorkDatabasePort by database
