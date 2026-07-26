package com.tingyun.smartmistakebook.core.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TutorVisualHitProofTest {
    @Before
    fun clearRegistry() {
        TutorVisualHitProofRegistry.clearForTest()
    }

    @Test
    fun `renderer proof is exact short lived and consumed once`() {
        val proof = TutorVisualHitProofRegistry.issue(
            presentation = presentation(),
            panelId = "panel",
            frameFingerprint = "b".repeat(64),
            stepIndex = 0,
            selectedTargetId = "node",
            eligibleTargetIds = setOf("node"),
            issuedAtElapsedMillis = 1_000,
        )

        assertFalse(
            TutorVisualHitProofRegistry.consume(
                proof.copy(selectedTargetId = "other"),
                nowElapsedMillis = 1_001,
            ),
        )
        assertTrue(TutorVisualHitProofRegistry.consume(proof, nowElapsedMillis = 1_001))
        assertFalse(TutorVisualHitProofRegistry.consume(proof, nowElapsedMillis = 1_002))
    }

    @Test
    fun `expired renderer proof fails closed`() {
        val proof = TutorVisualHitProofRegistry.issue(
            presentation = presentation(),
            panelId = "panel",
            frameFingerprint = "b".repeat(64),
            stepIndex = 0,
            selectedTargetId = "node",
            eligibleTargetIds = setOf("node"),
            issuedAtElapsedMillis = 1_000,
        )

        assertFalse(
            TutorVisualHitProofRegistry.consume(
                proof,
                nowElapsedMillis = 1_000 + 2 * 60 * 1_000L + 1,
            ),
        )
    }

    @Test
    fun `claimed proof can be released or finalized but never claimed concurrently`() {
        val proof = TutorVisualHitProofRegistry.issue(
            presentation = presentation(),
            panelId = "panel",
            frameFingerprint = "b".repeat(64),
            stepIndex = 0,
            selectedTargetId = "node",
            eligibleTargetIds = setOf("node"),
            issuedAtElapsedMillis = 1_000,
        )

        assertTrue(TutorVisualHitProofRegistry.claim(proof, nowElapsedMillis = 1_001))
        assertFalse(TutorVisualHitProofRegistry.claim(proof, nowElapsedMillis = 1_002))
        assertTrue(TutorVisualHitProofRegistry.release(proof, nowElapsedMillis = 1_003))
        assertTrue(TutorVisualHitProofRegistry.claim(proof, nowElapsedMillis = 1_004))
        assertTrue(TutorVisualHitProofRegistry.finalize(proof))
        assertFalse(TutorVisualHitProofRegistry.claim(proof, nowElapsedMillis = 1_005))
    }

    @Test
    fun `scene fingerprint binds the full verified document`() {
        val baseline = scene(label = "物体")
        val changed = scene(label = "小车")

        assertNotEquals(
            TutorVisualSceneFingerprint.of(baseline),
            TutorVisualSceneFingerprint.of(changed),
        )
    }

    @Test
    fun `presentation state key binds owner source and full scene content`() {
        val baseline = scene(label = "物体")
        val fingerprint = TutorVisualSceneFingerprint.of(baseline)
        val inline = TutorVisualPresentationIdentity(
            ownerModelTaskRequestId = "plan-request",
            sourceKind = TutorVisualSceneSourceKind.INLINE,
            sceneTaskRequestId = "plan-request",
            sceneId = baseline.sceneId,
            sceneFingerprint = fingerprint,
        )
        val generated = inline.copy(
            sourceKind = TutorVisualSceneSourceKind.GENERATED,
            sceneTaskRequestId = "generation-request",
        )
        val otherOwner = TutorVisualPresentationIdentity(
            ownerModelTaskRequestId = "other-plan-request",
            sourceKind = TutorVisualSceneSourceKind.INLINE,
            sceneTaskRequestId = "other-plan-request",
            sceneId = baseline.sceneId,
            sceneFingerprint = fingerprint,
        )

        assertNotEquals(
            TutorVisualPresentationStateKey.of(baseline, inline),
            TutorVisualPresentationStateKey.of(baseline, generated),
        )
        assertNotEquals(
            TutorVisualPresentationStateKey.of(baseline, inline),
            TutorVisualPresentationStateKey.of(baseline, otherOwner),
        )
        assertNotEquals(
            TutorVisualPresentationStateKey.of(baseline, inline),
            TutorVisualPresentationStateKey.of(scene(label = "小车")),
        )
    }

    @Test
    fun `production proof issuance exists only in the real renderer hit path`() {
        val projectRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { path ->
            path.parent
        }.first { path -> Files.exists(path.resolve("settings.gradle.kts")) }
        val issuanceSites = Files.walk(projectRoot).use { paths ->
            paths.filter { path ->
                path.isRegularFile() &&
                    path.toString().endsWith(".kt") &&
                    path.toString().contains("${java.io.File.separator}src${java.io.File.separator}main")
            }.filter { path ->
                Files.readString(path).contains("TutorVisualHitProofRegistry.issue(")
            }.map { path ->
                projectRoot.relativize(path).toString().replace('\\', '/')
            }.sorted().toList()
        }

        assertEquals(
            listOf(
                "core/visual-ui/src/main/java/com/tingyun/smartmistakebook/" +
                    "core/visual/ui/TutorVisual2DPanel.kt",
            ),
            issuanceSites,
        )
    }

    private fun presentation() = TutorVisualPresentationIdentity(
        ownerModelTaskRequestId = "plan-request",
        sourceKind = TutorVisualSceneSourceKind.INLINE,
        sceneTaskRequestId = "plan-request",
        sceneId = "scene",
        sceneFingerprint = "a".repeat(64),
    )

    private fun scene(label: String) = TutorVisualDocumentScene(
        sceneId = "scene",
        title = "方向关系",
        panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "node",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = label,
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "先看物体",
                focusElementIds = listOf("node"),
                primaryRelationElementId = "node",
            ),
        ),
        fallbackMarkdown = "先看物体。",
        accessibilitySummary = "一个物体。",
    )
}
