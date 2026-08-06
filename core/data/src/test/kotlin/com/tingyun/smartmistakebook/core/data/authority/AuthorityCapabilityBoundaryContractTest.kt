package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepositoryFactory
import com.tingyun.smartmistakebook.core.data.review.LearnerBoundDailyReviewPlanPort
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.knowledge.database.HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME
import com.tingyun.smartmistakebook.core.mastery.database.LEARNER_MASTERY_DATABASE_NAME
import com.tingyun.smartmistakebook.core.student.mistake.database.STUDENT_MISTAKE_DATABASE_NAME
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityCapabilityBoundaryContractTest {
    @Test
    fun businessAuthorityDatabaseFilesAreExactlyTheThreeIndependentStores() {
        val businessAuthorityFiles =
            setOf(
                STUDENT_MISTAKE_DATABASE_NAME,
                LEARNER_MASTERY_DATABASE_NAME,
                HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME,
            )

        assertEquals(
            setOf(
                "student-mistakes.db",
                "learner-mastery.db",
                "high-school-knowledge.db",
            ),
            businessAuthorityFiles,
        )
        assertFalse(StudyDatabaseFactory.DEFAULT_DATABASE_NAME in businessAuthorityFiles)
        assertEquals("smart-mistake-book.db", StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
    }

    @Test
    fun appFeaturesProviderAndDomainCannotCompileAgainstAuthorityOwnerApis() {
        val root = projectRoot()
        val forbiddenProductionRoots =
            listOf(
                File(root, "app"),
                File(root, "feature"),
                File(root, "core/model-provider"),
                File(root, "core/domain"),
            )
        val directAuthorityReferences =
            forbiddenProductionRoots
                .flatMap(::productionSourceFiles)
                .filter { source ->
                    val text = source.readText()
                    "com.tingyun.smartmistakebook.core.mastery.database" in text ||
                        "com.tingyun.smartmistakebook.core.student.mistake.database" in text ||
                        "com.tingyun.smartmistakebook.core.knowledge.database" in text ||
                        "project(\":core:learner-mastery-database\")" in text ||
                        "project(\":core:student-mistake-database\")" in text ||
                        "project(\":core:knowledge-database\")" in text ||
                        "KnowledgeReferenceProofAuthority" in text ||
                        "KnowledgeReferenceProofIssuer" in text ||
                        "KnowledgeReferenceProofVerifier" in text
                }

        assertTrue(
            "External production code directly references authority database APIs: " +
                directAuthorityReferences.map { it.relativeTo(root).invariantSeparatorsPath },
            directAuthorityReferences.isEmpty(),
        )
    }

    @Test
    fun onlyCoreDataAuthorityOwnerReferencesPrivilegedCapabilities() {
        val root = projectRoot()
        val javaAuthorityOwnerFiles =
            setOf(
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LearnerMasteryOwnerAccess.java",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "StudentMistakeOwnerAccess.java",
            )
        val privilegedNames =
            listOf(
                "LearnerMasteryRuntimeFactory",
                "LearnerMasteryRuntimeCapabilities",
                "LearnerMasteryRelayCapability",
                "LearnerMasteryObservationSink",
                "LearnerMasteryReader",
                "LearnerMasteryKnowledgeEvidenceAuthorizer",
                "LearnerMasteryModelAccessProvider",
                "LearnerMasteryOwnerKey",
                "CoreDataLearnerMasteryOwnerBridge",
                "openLearnerMasteryOwnerCapabilities",
                "StudentMistakeRuntimeFactory",
                "StudentMistakeRuntimeCapabilities",
                "StudentMistakeRelayCapability",
                "StudentMistakeMigrationPortFactory",
                "StudentMistakeCutoverControlPortFactory",
                "StudentMistakeCutoverControlPort",
                "StudentMistakeOwnerKey",
                "CoreDataStudentMistakeOwnerBridge",
                "openStudentMistakeOwnerCapabilities",
                "openStudentMistakeMigrationOwner",
                "openStudentMistakeCutoverControlOwner",
            )
        val violations =
            productionSourceFiles(root)
                .filterNot { source ->
                    val path = source.invariantSeparatorsPath
                    val relativePath = source.relativeTo(root).invariantSeparatorsPath
                    path.contains("/core/learner-mastery-database/") ||
                        path.contains("/core/student-mistake-database/") ||
                        relativePath in javaAuthorityOwnerFiles ||
                        path.contains(
                            "/core/data/src/main/kotlin/com/tingyun/smartmistakebook/" +
                                "core/data/authority/",
                        )
                }
                .filter { source ->
                    val text = source.readText()
                    privilegedNames.any(text::contains)
                }

        assertTrue(
            "Production code outside the core:data authority owner obtained a privileged " +
                "authority capability: " +
                violations.map { it.relativeTo(root).invariantSeparatorsPath },
            violations.isEmpty(),
        )
    }

    @Test
    fun authorityDatabaseDependenciesRemainNonTransitiveAndNoCycleIsIntroduced() {
        val root = projectRoot()
        val dataBuild = File(root, "core/data/build.gradle.kts").readText()
        val masteryBuild = File(root, "core/learner-mastery-database/build.gradle.kts").readText()
        val studentBuild = File(root, "core/student-mistake-database/build.gradle.kts").readText()
        val knowledgeBuild = File(root, "core/knowledge-database/build.gradle.kts").readText()

        listOf(
            ":core:learner-mastery-database",
            ":core:student-mistake-database",
            ":core:knowledge-database",
        ).forEach { module ->
            assertTrue(
                "core:data must hide $module from consumers",
                "implementation(project(\"$module\"))" in dataBuild,
            )
            assertFalse(
                "core:data must not export $module",
                "api(project(\"$module\"))" in dataBuild,
            )
        }
        val authorityBuilds =
            mapOf(
                ":core:student-mistake-database" to studentBuild,
                ":core:learner-mastery-database" to masteryBuild,
                ":core:knowledge-database" to knowledgeBuild,
            )
        authorityBuilds.forEach { (sourceModule, sourceBuild) ->
            assertFalse(
                "$sourceModule must not depend on the legacy session/migration database",
                "project(\":core:database\")" in sourceBuild,
            )
            authorityBuilds.keys
                .filterNot(sourceModule::equals)
                .forEach { targetModule ->
                    assertFalse(
                        "$sourceModule must not depend on authority implementation $targetModule",
                        "project(\"$targetModule\")" in sourceBuild,
                    )
                }
        }
        assertTrue(
            "Mastery must depend directly on the shared core:model contract",
            "api(project(\":core:model\"))" in masteryBuild,
        )
        assertTrue(
            "Student must depend directly on the shared core:model contract",
            "api(project(\":core:model\"))" in studentBuild,
        )
        val masterySplitPackageFiles =
            productionSourceFiles(
                File(root, "core/learner-mastery-database"),
            ).filter { source ->
                "package com.tingyun.smartmistakebook.core.student.mistake.database" in
                    source.readText()
            }
        assertTrue(
            "Mastery must not join the student authority package to issue relay messages: " +
                masterySplitPackageFiles.map {
                    it.relativeTo(root).invariantSeparatorsPath
                },
            masterySplitPackageFiles.isEmpty(),
        )
    }

    @Test
    fun productionCodeNeverAttachesAuthorityDatabaseFiles() {
        val root = projectRoot()
        val forbiddenSql =
            Regex(
                pattern = """(?i)\bATTACH\s+(?:DATABASE\s+)?|\bDETACH\s+(?:DATABASE\s+)?""",
            )
        val violations =
            productionSourceFiles(root)
                .filter { source -> forbiddenSql.containsMatchIn(source.readText()) }
                .map { source -> source.relativeTo(root).invariantSeparatorsPath }

        assertTrue(
            "Authority databases must be composed through typed ports, never SQLite ATTACH: " +
                violations,
            violations.isEmpty(),
        )
    }

    @Test
    fun exportedAuthoritySchemasContainOnlyTheirOwnDisjointBusinessTables() {
        val root = projectRoot()
        val schemaTables =
            mapOf(
                "student" to
                    latestSchemaTables(
                        File(
                            root,
                            "core/student-mistake-database/schemas/" +
                                "com.tingyun.smartmistakebook.core.student.mistake.database." +
                                "StudentMistakeRoomDatabase",
                        ),
                    ),
                "mastery" to
                    latestSchemaTables(
                        File(
                            root,
                            "core/learner-mastery-database/schemas/" +
                                "com.tingyun.smartmistakebook.core.mastery.database." +
                                "LearnerMasteryRoomDatabase",
                        ),
                    ),
                "knowledge" to
                    latestSchemaTables(
                        File(
                            root,
                            "core/knowledge-database/schemas/" +
                                "com.tingyun.smartmistakebook.core.knowledge.database." +
                                "HighSchoolKnowledgeRoomDatabase",
                        ),
                    ),
            )

        schemaTables.forEach { (authority, tables) ->
            assertTrue("$authority authority must export at least one table", tables.isNotEmpty())
            assertTrue(
                "$authority authority schema contains another authority's table: $tables",
                tables.all { table -> table.startsWith("${authority}_") },
            )
        }
        schemaTables.entries.forEach { left ->
            schemaTables.entries
                .filterNot { right -> right.key == left.key }
                .forEach { right ->
                    assertTrue(
                        "${left.key} and ${right.key} authority schemas overlap",
                        left.value.intersect(right.value).isEmpty(),
                    )
                }
        }
    }

    @Test
    fun masteryFactoriesAndAuthorityInterfacesRemainModuleInternal() {
        val root = projectRoot()
        val masteryAuthority =
            File(
                root,
                "core/learner-mastery-database/src/main/kotlin/com/tingyun/" +
                    "smartmistakebook/core/mastery/database/LearnerMasteryAuthority.kt",
            ).readText()
        val masteryDatabase =
            File(
                root,
                "core/learner-mastery-database/src/main/kotlin/com/tingyun/" +
                    "smartmistakebook/core/mastery/database/LearnerMasteryDatabase.kt",
            ).readText()

        assertTrue("internal interface LearnerMasteryAuthority" in masteryAuthority)
        assertTrue("internal object LearnerMasteryAuthorityFactory" in masteryDatabase)
        assertTrue("internal object LearnerMasteryRuntimeFactory" in masteryDatabase)
        assertFalse("class RecordTrustedLearningObservationCommand(" in masteryAuthority)
        assertTrue("class LearningObservationFacts(" in masteryAuthority)
        assertTrue(
            "typealias RecordTrustedLearningObservationCommand = LearningObservationFacts" in
                masteryAuthority,
        )
    }

    @Test
    fun studentMistakeProductionDatabaseHasOneFixedPackagePrivateOpener() {
        val root = projectRoot()
        val studentModule =
            File(root, "core/student-mistake-database")
        val roomBuilderPattern =
            Regex(
                pattern = """\bRoom\s*\.\s*(?:databaseBuilder|inMemoryDatabaseBuilder)\s*\(""",
            )
        val productionRoomOpeners =
            productionSourceFiles(studentModule)
                .filter { source -> roomBuilderPattern.containsMatchIn(source.readText()) }
                .map { source -> source.relativeTo(root).invariantSeparatorsPath }

        assertEquals(
            listOf(
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeOwnedDatabase.java",
            ),
            productionRoomOpeners,
        )

        val ownedDatabase = studentAuthorityClass("StudentMistakeOwnedDatabase")
        assertJvmPackagePrivate(ownedDatabase)
        assertTrue(Modifier.isFinal(ownedDatabase.modifiers))
        assertTrue(
            ownedDatabase.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )

        val ownerKey = studentAuthorityClass("StudentMistakeOwnerKey")
        val openers =
            ownedDatabase.declaredMethods.filter { method ->
                method.name == "openDatabase" || method.name == "openStore"
            }
        assertEquals(setOf("openDatabase", "openStore"), openers.mapTo(mutableSetOf()) { it.name })
        openers.forEach { method ->
            assertFalse(Modifier.isPublic(method.modifiers))
            assertFalse(Modifier.isProtected(method.modifiers))
            assertTrue(
                "${method.name} must require the unexported owner key",
                ownerKey in method.parameterTypes,
            )
            assertTrue(
                "${method.name} must not accept a caller-selected database location",
                method.parameterTypes.none(::isDatabaseLocatorType) &&
                    String::class.java !in method.parameterTypes,
            )
        }

        val openerSource =
            File(
                studentModule,
                "src/main/java/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeOwnedDatabase.java",
            ).readText()
        val canonicalBuilderArgument =
            Regex(
                pattern =
                    """Room\s*\.\s*databaseBuilder\s*\(\s*""" +
                        """[^,]+,\s*StudentMistakeRoomDatabase\s*\.\s*class\s*,\s*""" +
                        """StudentMistakeStoreKt\s*\.\s*STUDENT_MISTAKE_DATABASE_NAME\s*\)""",
                options = setOf(RegexOption.DOT_MATCHES_ALL),
            )
        assertTrue(
            "The sole production opener must bind Room to the canonical student-mistakes.db " +
                "constant rather than a caller-controlled name",
            canonicalBuilderArgument.containsMatchIn(openerSource),
        )
    }

    @Test
    fun studentMistakeDatabaseAuthorityHasNoCrossPackageCallableJvmSurface() {
        val sensitiveAuthorityTypes =
            listOf(
                studentAuthorityClass("StudentMistakeOwnedDatabase"),
                studentAuthorityClass("StudentMistakeRoomDatabase"),
                studentAuthorityClass("StudentMistakeOwnerKey"),
                studentAuthorityClass("CoreDataStudentMistakeOwnerBridge"),
                studentAuthorityClass("StudentProblemIdentityEvidenceOwner"),
            )
        sensitiveAuthorityTypes.forEach(::assertJvmPackagePrivate)

        val ownerKey = studentAuthorityClass("StudentMistakeOwnerKey")
        assertTrue(
            ownerKey.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
        assertTrue(
            ownerKey.declaredFields.all { field ->
                !Modifier.isPublic(field.modifiers) &&
                    !Modifier.isProtected(field.modifiers)
            },
        )

        val roomDatabase = studentAuthorityClass("StudentMistakeRoomDatabase")
        assertTrue(
            "The Room authority must not publish DAO accessors",
            roomDatabase.declaredMethods.all { method ->
                !Modifier.isPublic(method.modifiers)
            },
        )

        val productionAssembly =
            studentAuthorityClass("CoreDataStudentMistakeOwnerBridge")
        assertTrue(
            "Production assembly must remain JVM-private to the audited split package",
            productionAssembly.declaredMethods.all { method ->
                !Modifier.isPublic(method.modifiers) &&
                    !Modifier.isProtected(method.modifiers)
            },
        )

        val ownerGatedFactories =
            listOf(
                "StudentMistakeRuntimeFactory",
                "StudentMistakeMigrationPortFactory",
                "StudentMistakeCutoverControlPortFactory",
                "StudentMistakeModelReadPortFactory",
            ).map(::studentAuthorityClass)
        ownerGatedFactories.forEach { factory ->
            val openerMethods =
                factory.declaredMethods.filter { method -> method.name == "open" }
            assertTrue(
                "${factory.name} must expose an owner-gated open operation",
                openerMethods.isNotEmpty(),
            )
            openerMethods.forEach { method ->
                assertTrue(
                    "${factory.name}.${method.name} must require the package-private owner key",
                    ownerKey in method.parameterTypes,
                )
                assertFalse(
                    "${factory.name}.${method.name} became callable from another JVM package",
                    isCallableFromAnotherJvmPackage(method),
                )
                assertTrue(
                    "${factory.name}.${method.name} must not accept a database path or URI",
                    method.parameterTypes.none(::isDatabaseLocatorType),
                )
                assertFalse(apiSignature(method).contains("StudentMistakeRoomDatabase"))
                assertFalse(apiSignature(method).contains("StudentMistakeDao"))
            }
        }

        val externallyVisibleCapabilities =
            listOf(
                "StudentMistakeRuntimeCapabilities",
                "StudentMistakeLibraryCursor",
                "StudentMistakeMigrationPort",
                "StudentMistakeCutoverControlPort",
                "StudentMistakeModelReadPort",
            ).map(::studentAuthorityClass)
        val forbiddenPhysicalAuthorityTypes =
            listOf(
                "StudentMistakeOwnedDatabase",
                "StudentMistakeRoomDatabase",
                "RoomStudentMistakeStore",
                "StudentMistakeDao",
                "StudentMistakeOwnerKey",
                "androidx.room",
            )
        externallyVisibleCapabilities.forEach { capability ->
            capability.methods
                .filter(::isCallableFromAnotherJvmPackage)
                .forEach { method ->
                    val signature = apiSignature(method)
                    assertTrue(
                        "${capability.name}.${method.name} leaks physical database authority",
                        forbiddenPhysicalAuthorityTypes.none(signature::contains),
                    )
                }
        }
    }

    @Test
    fun onlyAuditedCoreDataFilesMayJoinAuthorityDatabasePackages() {
        val root = projectRoot()
        val authorityPackages =
            setOf(
                "package com.tingyun.smartmistakebook.core.mastery.database",
                "package com.tingyun.smartmistakebook.core.student.mistake.database",
            )
        val splitPackageFiles =
            productionSourceFiles(root)
                .filter { source ->
                    val path = source.invariantSeparatorsPath
                    !path.contains("/core/learner-mastery-database/") &&
                        !path.contains("/core/student-mistake-database/") &&
                        authorityPackages.any(source.readText()::contains)
                }
                .mapTo(sortedSetOf()) { source ->
                    source.relativeTo(root).invariantSeparatorsPath
                }

        assertEquals(
            sortedSetOf(
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LearnerMasteryOwnerAccess.java",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "StudentMistakeOwnerAccess.java",
            ),
            splitPackageFiles,
        )
    }

    @Test
    fun captureIdentityAndAtomicWriterAreComposedInsideTheStudentOwnerBridge() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "StudentMistakeOwnerAccess.java",
            ).readText()
        val captureOwner =
            source.substringAfter("openProductionStudentCaptureOccurrenceOwner")

        assertTrue("ProductionStudentCaptureOccurrenceOwner" in captureOwner)
        assertTrue("invokeSaveExactAssetSelectionOrUnresolved" in captureOwner)
        assertFalse("TrustedSource" in captureOwner)
        assertFalse("ReviewedAlias" in captureOwner)
        assertFalse("capabilities.captureOccurrences" in source)
        assertFalse("StudentProblemIdentityEvidenceAuthority.Verifier" in source)
        assertFalse("LearnerBoundStudentCaptureOccurrencePort" in source)
        assertFalse("createWithIdentityEvidence" in source)
    }

    @Test
    fun runtimeHasExplicitStartupRecoveryAndPostObservationRelayTriggers() {
        val runtimeSource =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ).readText()
        val observationWrite = "learnerMastery.observationSink.record(command)"
        val firstWriteIndex = runtimeSource.indexOf(observationWrite)
        val postWriteTriggerIndex =
            if (firstWriteIndex < 0) {
                -1
            } else {
                runtimeSource.indexOf("triggerAuthorityRelay()", startIndex = firstWriteIndex)
            }
        val runtimeAssemblyIndex = runtimeSource.indexOf("val runtime =")
        val startupTriggerIndex =
            if (runtimeAssemblyIndex < 0) {
                -1
            } else {
                runtimeSource.indexOf(
                    "runtime.triggerAuthorityRelay()",
                    startIndex = runtimeAssemblyIndex,
                )
            }

        assertTrue(firstWriteIndex >= 0)
        assertTrue(postWriteTriggerIndex > firstWriteIndex)
        assertTrue(runtimeAssemblyIndex >= 0)
        assertTrue(startupTriggerIndex > runtimeAssemblyIndex)
        assertTrue("relayDrainMutex.withLock" in runtimeSource)
    }

    @Test
    fun runtimeTutorAssemblyUsesOneTrustedSessionOwnerAndRealMasteryAuthorities() {
        val runtimeSource =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ).readText()

        assertTrue("TrustedTutorSessionDatabaseCapability" in runtimeSource)
        assertTrue("CurrentTutorSessionProductionOwnerFactory.open" in runtimeSource)
        assertTrue("bindCurrentTutorSessionToProductionGeneration" in runtimeSource)
        assertTrue("generationIdentity = generationBinding" in runtimeSource)
        assertTrue("context = canonicalContext" in runtimeSource)
        assertTrue("currentSessionProofSource = currentTutorSessionOwner.learningEvidenceProofSource" in runtimeSource)
        assertTrue("TutorKnowledgeEvidenceAuthorizer(" in runtimeSource)
        assertTrue("learnerMastery.knowledgeEvidenceAuthorizer::authorize" in runtimeSource)
        assertTrue("productionOwnerIsCurrent = currentTutorSessionOwner::isOpen" in runtimeSource)
        assertFalse("TutorConversationSessionDatabasePort" in runtimeSource)
        assertFalse("TutorLearningEvidenceSessionDatabasePort" in runtimeSource)
        assertFalse("TutorLearningMemoryDatabasePort" in runtimeSource)
        assertFalse("TrustedTutorLearningMemoryDatabaseCapability" in runtimeSource)
    }

    @Test
    fun runtimeExposesTutorMasteryOnlyThroughTheBoundedDomainReadContract() {
        val method =
            LocalLearningAuthorityRuntime::class.java.methods.single {
                it.name == "tutorMasteryContextRepository"
            }

        assertEquals(0, method.parameterCount)
        assertEquals(TutorMasteryContextRepository::class.java, method.returnType)
        assertFalse(method.returnType.name.contains(".database."))
    }

    @Test
    fun runtimeExposesLearningMasteryOnlyThroughTheSafeDomainDisplayContract() {
        val method =
            LocalLearningAuthorityRuntime::class.java.methods.single {
                it.name == "learningMasteryDisplayRepository"
            }

        assertEquals(0, method.parameterCount)
        assertEquals(LearningMasteryDisplayRepository::class.java, method.returnType)
        assertFalse(method.returnType.name.contains(".database."))
    }

    @Test
    fun runtimeExposesOnlyALearnerBoundDailyReviewPlanningPort() {
        val runtimeMethod =
            LocalLearningAuthorityRuntime::class.java.methods.single {
                it.name == "dailyReviewPlanPort"
            }
        assertEquals(0, runtimeMethod.parameterCount)
        assertEquals(LearnerBoundDailyReviewPlanPort::class.java, runtimeMethod.returnType)

        val planMethod =
            LearnerBoundDailyReviewPlanPort::class.java.declaredMethods.single {
                it.name == "planDailyReview"
            }
        val signature =
            buildString {
                append(planMethod.genericReturnType.typeName)
                planMethod.genericParameterTypes.forEach { append(it.typeName) }
            }
        assertEquals(
            listOf(
                java.lang.Long.TYPE,
                String::class.java,
                java.lang.Long.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                com.tingyun.smartmistakebook.core.domain.ReviewExamTarget::class.java,
                kotlin.coroutines.Continuation::class.java,
            ),
            planMethod.parameterTypes.toList(),
        )
        assertFalse(signature.contains(".database."))
        assertFalse(signature.contains("StudentMistakeStore"))
        assertFalse(signature.contains("LearnerMastery"))

        val runtimeSource =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ).readText()
        assertTrue("openThreeAuthorityReviewPlanCoordinator(" in runtimeSource)
        assertFalse("studentMistakes.businessStore" in runtimeSource)
        assertTrue("learnerMastery.localContextReader," in runtimeSource)
        assertTrue("RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT," in runtimeSource)
    }

    @Test
    fun runtimeExposesOneLearnerBoundEvidencePortWithoutDatabaseTypes() {
        val runtimeMethod =
            LocalLearningAuthorityRuntime::class.java.methods.single {
                it.name == "learningEvidencePort"
            }
        assertEquals(0, runtimeMethod.parameterCount)
        assertEquals(LearnerBoundLearningEvidencePort::class.java, runtimeMethod.returnType)

        val exposedSignatures =
            LearnerBoundLearningEvidencePort::class.java.methods
                .joinToString(separator = "\n") { method ->
                    buildString {
                        append(method.genericReturnType.typeName)
                        method.genericParameterTypes.forEach { append(it.typeName) }
                    }
                }
        assertFalse(exposedSignatures.contains(".database."))

        val runtimeSource =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ).readText()
        assertTrue("studentMistakes = studentMistakes" in runtimeSource)
        assertTrue("learnerMastery = learnerMastery" in runtimeSource)
    }

    @Test
    fun appFacingStudentCatalogFactoryHidesThePhysicalMistakeDatabasePort() {
        val methods =
            StudentMistakeLibraryCatalogRepositoryFactory::class.java.methods
                .filter { method ->
                    Modifier.isPublic(method.modifiers) && !method.isSynthetic
                }

        assertTrue(methods.any { it.name == "createDeferredFromAuthorityRuntime" })
        val exposedSignatures =
            methods.joinToString(separator = "\n") { method ->
                buildString {
                    append(method.returnType.name)
                    method.parameterTypes.forEach { append(it.name) }
                }
            }
        assertFalse(exposedSignatures.contains(".database."))
        assertFalse(exposedSignatures.contains("LearnerBoundStudentMistakeLibraryPort"))
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun studentAuthorityClass(simpleName: String): Class<*> =
        Class.forName(
            "com.tingyun.smartmistakebook.core.student.mistake.database.$simpleName",
            false,
            javaClass.classLoader,
        )

    private fun assertJvmPackagePrivate(type: Class<*>) {
        assertFalse("${type.name} must not be public", Modifier.isPublic(type.modifiers))
        assertFalse("${type.name} must not be protected", Modifier.isProtected(type.modifiers))
    }

    private fun isCallableFromAnotherJvmPackage(method: java.lang.reflect.Method): Boolean =
        Modifier.isPublic(method.declaringClass.modifiers) &&
            Modifier.isPublic(method.modifiers) &&
            isPubliclyNameable(method.returnType) &&
            method.parameterTypes.all(::isPubliclyNameable)

    private fun isPubliclyNameable(type: Class<*>): Boolean =
        when {
            type.isPrimitive -> true
            type.isArray -> isPubliclyNameable(type.componentType)
            else -> Modifier.isPublic(type.modifiers)
        }

    private fun isDatabaseLocatorType(type: Class<*>): Boolean =
        type.name in
            setOf(
                "java.io.File",
                "java.net.URI",
                "java.nio.file.Path",
                "android.net.Uri",
            )

    private fun apiSignature(method: java.lang.reflect.Method): String =
        buildString {
            append(method.returnType.name)
            method.parameterTypes.forEach { parameter -> append(parameter.name) }
        }

    private fun productionSourceFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { directory ->
                directory.name !in setOf("build", ".gradle", ".git")
            }
            .filter(File::isFile)
            .filter { file ->
                (
                    file.invariantSeparatorsPath.contains("/src/main/") ||
                        file.name == "build.gradle.kts"
                    ) &&
                    file.extension in setOf("kt", "java", "kts")
            }
            .toList()
    }

    private fun latestSchemaTables(schemaDirectory: File): Set<String> {
        val latestSchema =
            schemaDirectory
                .listFiles { file -> file.isFile && file.extension == "json" }
                ?.maxByOrNull { file -> file.nameWithoutExtension.toIntOrNull() ?: -1 }
                ?: error("Missing exported Room schema in ${schemaDirectory.invariantSeparatorsPath}")
        return Regex(""""tableName"\s*:\s*"([^"]+)"""")
            .findAll(latestSchema.readText())
            .map { match -> match.groupValues[1] }
            .toSet()
    }
}
