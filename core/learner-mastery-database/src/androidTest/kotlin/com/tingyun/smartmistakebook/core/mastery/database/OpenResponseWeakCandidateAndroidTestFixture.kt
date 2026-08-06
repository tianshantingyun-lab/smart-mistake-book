package com.tingyun.smartmistakebook.core.mastery.database

import android.content.Context
import android.content.ContextWrapper
import com.tingyun.smartmistakebook.core.model.NoopProjectionWorkloadGate
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import java.io.File

/** Opens the production one-shot owner chain while routing its database into a test-only file. */
internal object OpenResponseWeakCandidateAndroidTestFixture {
    fun open(
        context: Context,
        databaseName: String,
        learnerId: String,
    ): OpenResponseWeakCandidateAndroidTestSession {
        require(databaseName.endsWith(LEARNER_MASTERY_TEST_DATABASE_SUFFIX))
        val routedContext =
            RoutedMasteryDatabaseContext(
                base = context.applicationContext,
                testDatabaseName = databaseName,
            )
        val knowledgeAuthority = KnowledgeReferenceProofAuthority.create()
        val runtime =
            CoreDataLearnerMasteryOwnerBridge.open(
                routedContext,
                learnerId,
                knowledgeAuthority.verifier,
                NoopProjectionWorkloadGate,
            )
        return try {
            val grant =
                CoreDataLearnerMasteryOwnerBridge.claimOpenResponseOwnerGrant(runtime)
            val owner =
                CoreDataLearnerMasteryOwnerBridge.openOpenResponseWeakCandidateOwner(
                    routedContext,
                    learnerId,
                    grant,
                ) as RoomOpenResponseWeakCandidateOwner
            OpenResponseWeakCandidateAndroidTestSession(
                databaseOwner = owner,
                runtimeOwner = runtime,
            )
        } catch (failure: Throwable) {
            runCatching { runtime.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }
}

internal class OpenResponseWeakCandidateAndroidTestSession(
    internal val databaseOwner: RoomOpenResponseWeakCandidateOwner,
    private val runtimeOwner: LearnerMasteryRuntimeCapabilities,
) : LearnerMasteryOpenResponseWeakCandidateOwner by databaseOwner {
    override fun close() {
        var closeFailure: Throwable? = null
        runCatching { databaseOwner.close() }
            .onFailure { failure -> closeFailure = failure }
        runCatching { runtimeOwner.close() }
            .onFailure { failure ->
                val existingFailure = closeFailure
                if (existingFailure == null) {
                    closeFailure = failure
                } else {
                    existingFailure.addSuppressed(failure)
                }
            }
        closeFailure?.let { throw it }
    }
}

private class RoutedMasteryDatabaseContext(
    base: Context,
    private val testDatabaseName: String,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File {
        requireProductionDatabaseName(name)
        return baseContext.getDatabasePath(testDatabaseName)
    }

    override fun deleteDatabase(name: String): Boolean {
        requireProductionDatabaseName(name)
        return baseContext.deleteDatabase(testDatabaseName)
    }

    private fun requireProductionDatabaseName(name: String) {
        check(name == LEARNER_MASTERY_DATABASE_NAME) {
            "Android-test mastery context cannot route an unexpected database"
        }
    }
}
