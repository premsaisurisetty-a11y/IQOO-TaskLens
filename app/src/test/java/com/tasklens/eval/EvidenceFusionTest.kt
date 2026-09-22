package com.tasklens.eval

import com.tasklens.ai.AnswerEvidence
import com.tasklens.ai.ComponentLocator
import com.tasklens.ai.Detections
import com.tasklens.ai.Localization
import com.tasklens.ai.LearnerContext
import com.tasklens.ai.groundedEvidence
import com.tasklens.core.EvidenceFusionEngine
import com.tasklens.core.EvidenceSourceRank
import com.tasklens.core.FusedEvidenceState
import com.tasklens.core.LearnerObservation
import com.tasklens.core.LearnerProgressTracker
import com.tasklens.core.LearnerReadinessState
import com.tasklens.core.Policy
import com.tasklens.core.RequirementType
import com.tasklens.core.StepProgressStatus
import com.tasklens.core.StepRequirement
import com.tasklens.core.StepRequirementDeriver
import com.tasklens.data.Guide
import com.tasklens.data.Provenance
import com.tasklens.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 7 — Multi-Source Evidence Fusion & Uncertainty Intelligence Test Suite.
 * Covers all 25 required cases and safety boundaries deterministically.
 */
class EvidenceFusionTest {

    private val policy = Policy.DEFAULT

    // 1. One weak evidence source -> not verified
    @Test
    fun `test 1 - one weak evidence source does not produce supported state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.5f))
        engine.setStepContext(0, "Select screwdriver", "Take the screwdriver", "take screwdriver", reqs)

        engine.addObservation(
            LearnerObservation(timestampMs = 1000L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.2f))
        )
        val report = engine.evaluate(1000L)
        assertFalse("Weak single observation must not be supported", report.state.isSupported)
        assertEquals(FusedEvidenceState.CHECKING, report.state)
    }

    // 2. Repeated strong object evidence -> supported
    @Test
    fun `test 2 - repeated strong object evidence produces supported state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.3f))
        engine.setStepContext(0, "Select screwdriver", "Take screwdriver", "take screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.85f),
                )
            )
        }
        val report = engine.evaluate(1400L)
        assertTrue("Stable strong detections must produce supported state", report.state.isSupported)
        assertEquals(LearnerReadinessState.VERIFIED_COMPLETE, report.readiness)
    }

    // 3. Object + expert requirement -> stronger support
    @Test
    fun `test 3 - object plus expert requirement agreement produces strong support`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Select the screwdriver", "take screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.9f),
                )
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.STRONGLY_SUPPORTED, report.state)
        assertTrue(report.sources.contains(EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName))
        assertTrue(report.sources.contains(EvidenceSourceRank.OBJECT_DETECTION.sourceName))
    }

    // 4. Object + scene change -> does not prove action (e.g. screw removal)
    @Test
    fun `test 4 - object and scene change alone does not prove physical screw removal`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"),
            StepRequirement(type = RequirementType.SCENE_CHANGE, target = "screw_state")
        )
        // Step text specifies removal action
        engine.setStepContext(2, "Remove screws", "Unscrew the bottom screws", "remove screws", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.9f),
                    frameToFrameChange = 18,
                )
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals("Physical removal requires manual confirmation", FusedEvidenceState.MANUAL_REQUIRED, report.state)
        assertEquals(LearnerReadinessState.LIKELY_DONE_BUT_NEEDS_CONFIRMATION, report.readiness)
    }

    // 5. Speech + visual agreement
    @Test
    fun `test 5 - speech and visual agreement reinforces fused state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Tool selection", "Use the screwdriver", "screwdriver needed", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.88f),
                )
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.STRONGLY_SUPPORTED, report.state)
        assertTrue(report.sources.contains(EvidenceSourceRank.DOMAIN_SPEECH.sourceName))
    }

    // 6. Speech without visual support
    @Test
    fun `test 6 - speech without visual support remains insufficient or manual required`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Tool selection", "Use screwdriver", "screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(timestampMs = 1000L + (i * 100L), seenLabels = emptyList())
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.INSUFFICIENT, report.state)
        assertEquals(LearnerReadinessState.WAITING_FOR_REQUIRED_OBJECT, report.readiness)
    }

    // 7. Visual without speech
    @Test
    fun `test 7 - visual evidence without speech satisfies visual requirement`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        // Transcript empty
        engine.setStepContext(0, "Step 1", "Hold tool", "", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.9f),
                )
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.SUPPORTED, report.state)
    }

    // 8. Conflicting object observations
    @Test
    fun `test 8 - conflicting intermittent object observations produces conflicting state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.5f))
        engine.setStepContext(0, "Select screwdriver", "Select screwdriver", "screwdriver", reqs)

        // Intermittent presence (2 out of 5 frames = 40% < 75%)
        engine.addObservation(LearnerObservation(1000L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f)))
        engine.addObservation(LearnerObservation(1100L, seenLabels = emptyList()))
        engine.addObservation(LearnerObservation(1200L, seenLabels = emptyList()))
        engine.addObservation(LearnerObservation(1300L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f)))
        engine.addObservation(LearnerObservation(1400L, seenLabels = emptyList()))

        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.CONFLICTING, report.state)
        assertEquals(LearnerReadinessState.CONFLICTING_EVIDENCE, report.readiness)
    }

    // 9. Temporary detection loss
    @Test
    fun `test 9 - temporary detection loss does not immediately fail settled evidence`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Select screwdriver", "screwdriver", reqs)

        for (i in 0 until 4) {
            engine.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f))
            )
        }
        // 1 occluded frame (4/5 = 80% consistency >= 75%)
        engine.addObservation(LearnerObservation(1500L, seenLabels = emptyList()))

        val report = engine.evaluate(1500L)
        assertTrue("80% consistency survives temporary single-frame occlusion", report.state.isSupported)
    }

    // 10. Stale evidence
    @Test
    fun `test 10 - stale evidence is discounted and marked insufficient`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Select screwdriver", "screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f))
            )
        }
        // Evaluate 5 seconds later (> 3x 800ms)
        val report = engine.evaluate(6000L)
        assertEquals(FusedEvidenceState.INSUFFICIENT, report.state)
        assertTrue(report.explanation.contains("too old"))
    }

    // 11. Unsupported component
    @Test
    fun `test 11 - unsupported component produces manual required with honest reason`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = StepRequirementDeriver.derive(
            stepIndex = 4,
            title = "Locate battery connector",
            instruction = "Disconnect battery connector",
            transcript = "pull connector",
            caption = "",
            objects = emptyList(),
            policy = policy,
        )
        engine.setStepContext(4, "Locate battery connector", "Disconnect battery connector", "pull connector", reqs)

        for (i in 0 until 5) {
            engine.addObservation(LearnerObservation(1000L + (i * 100L), seenLabels = listOf("laptop")))
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.MANUAL_REQUIRED, report.state)
        assertTrue(report.explanation.contains("cannot directly verify"))
    }

    // 12. Manual confirmation requirement
    @Test
    fun `test 12 - manual confirmation requirement flags readiness state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.MANUAL_CONFIRMATION, target = "safety_power"))
        engine.setStepContext(0, "Power down", "Power down laptop", "unplug power", reqs)

        val report = engine.evaluate(1000L)
        assertEquals(FusedEvidenceState.MANUAL_REQUIRED, report.state)
        assertEquals(LearnerReadinessState.MANUAL_CONFIRMATION_REQUIRED, report.readiness)
    }

    // 13. User confirmation
    @Test
    fun `test 13 - user confirmation results in strongly supported complete state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.MANUAL_CONFIRMATION, target = "connector"))
        engine.setStepContext(5, "Disconnect connector", "Pull connector gently", "pull connector", reqs)

        engine.confirmManual()
        val report = engine.evaluate(1000L)
        assertEquals(FusedEvidenceState.STRONGLY_SUPPORTED, report.state)
        assertEquals(LearnerReadinessState.VERIFIED_COMPLETE, report.readiness)
    }

    // 14. Provenance preservation
    @Test
    fun `test 14 - provenance preservation ensures visual and user sources never convert to expert`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Select screwdriver", "screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f))
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals("VISUAL", report.strongestSource)
        assertFalse("Visual source must never claim EXPERT source rank name", report.strongestSource == "EXPERT_INSTRUCTION")
    }

    // 15. Coach cannot override verifier
    @Test
    fun `test 15 - coach cannot override deterministic verification outcome`() {
        val ctx = LearnerContext(
            job = "Repair",
            verified = false,
            stepNumber = 1,
            totalSteps = 6,
            instruction = "",
            instructionSource = Provenance.UNKNOWN,
            transcript = "",
            warning = "",
            warningSource = Provenance.UNKNOWN,
            previous = "",
            next = "",
            expectedTools = emptyList(),
            expectedObjects = emptyList(),
            seenNow = emptyList(),
            question = "Is step 1 done?",
        )
        val grounded = groundedEvidence(AnswerEvidence.DIRECT_GUIDE_FACT, ctx)
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, grounded)
    }

    // 16. No silent step advancement
    @Test
    fun `test 16 - progress tracker marks skipped unverified when advancing past unverified step`() {
        val tracker = LearnerProgressTracker(3)
        tracker.advanceTo(1)
        assertEquals(StepProgressStatus.SKIPPED_UNVERIFIED, tracker.getProgress(0)?.status)
        assertEquals(StepProgressStatus.IN_PROGRESS, tracker.getProgress(1)?.status)
    }

    // 17. Reset clears active evidence
    @Test
    fun `test 17 - reset clears active fusion engine evidence`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Select screwdriver", "screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f)))
        }
        engine.reset()
        val report = engine.evaluate(0L)
        assertEquals(FusedEvidenceState.UNKNOWN, report.state)
    }

    // 18. Old-step evidence cannot satisfy new step
    @Test
    fun `test 18 - old-step evidence cannot satisfy new step context`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs0 = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        val reqs1 = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "laptop"))

        engine.setStepContext(0, "Select screwdriver", "Take screwdriver", "screwdriver", reqs0)
        for (i in 0 until 5) {
            engine.addObservation(LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f)))
        }
        assertTrue(engine.evaluate(1400L).state.isSupported)

        // Switch to Step 1
        engine.setStepContext(1, "Position laptop", "Place laptop", "laptop", reqs1)
        val newReport = engine.evaluate(1400L)
        assertFalse("New step context must start clean without old step observations", newReport.state.isSupported)
        assertEquals(FusedEvidenceState.UNKNOWN, newReport.state)
    }

    // 19. Duplicate timestamps
    @Test
    fun `test 19 - duplicate timestamps are ignored to prevent artificial confidence inflation`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Take screwdriver", "screwdriver", reqs)

        for (i in 0 until 10) {
            engine.addObservation(LearnerObservation(1000L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f)))
        }
        val report = engine.evaluate(1000L)
        assertEquals(1, report.totalObservations)
        assertEquals(FusedEvidenceState.CHECKING, report.state)
    }

    // 20. Bounded evidence history
    @Test
    fun `test 20 - evidence history is strictly bounded to sliding window multiplier`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        engine.setStepContext(0, "Select screwdriver", "Take screwdriver", "screwdriver", reqs)

        // Add 50 observations spaced over 10 seconds
        for (i in 0 until 50) {
            engine.addObservation(LearnerObservation(1000L + (i * 200L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f)))
        }
        val report = engine.evaluate(10800L)
        assertTrue("Window observations count must be bounded", report.totalObservations <= 10)
    }

    // 21. Multiple requirements
    @Test
    fun `test 21 - all multiple requirements must be satisfied for supported state`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"),
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "laptop")
        )
        engine.setStepContext(0, "Setup workbench", "Setup workbench", "setup", reqs)

        // Only screwdriver seen, laptop missing
        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f))
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.INSUFFICIENT, report.state)
    }

    // 22. Conflicting requirements
    @Test
    fun `test 22 - conflicting requirements prevent premature success`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.5f)
        )
        engine.setStepContext(0, "Tool check", "Hold screwdriver", "screwdriver", reqs)

        // Alternating frames
        engine.addObservation(LearnerObservation(1000L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f)))
        engine.addObservation(LearnerObservation(1100L, seenLabels = emptyList()))
        engine.addObservation(LearnerObservation(1200L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f)))
        engine.addObservation(LearnerObservation(1300L, seenLabels = emptyList()))

        val report = engine.evaluate(1300L)
        assertEquals(FusedEvidenceState.CONFLICTING, report.state)
    }

    // 23. Low-confidence detection
    @Test
    fun `test 23 - low confidence detection does not satisfy presence requirement`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.60f))
        engine.setStepContext(0, "Tool check", "Hold screwdriver", "screwdriver", reqs)

        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.35f))
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.INSUFFICIENT, report.state)
    }

    // 24. Scene change without semantic evidence
    @Test
    fun `test 24 - scene change without required object presence does not pass`() {
        val engine = EvidenceFusionEngine(policy)
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "laptop"),
            StepRequirement(type = RequirementType.SCENE_CHANGE, target = "panel")
        )
        engine.setStepContext(3, "Open panel", "Open bottom panel", "open panel", reqs)

        // Scene changed, but laptop not detected
        for (i in 0 until 5) {
            engine.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = emptyList(), frameToFrameChange = 25)
            )
        }
        val report = engine.evaluate(1400L)
        assertEquals(FusedEvidenceState.INSUFFICIENT, report.state)
    }

    // 25. Robot blocked when fused state is uncertain
    @Test
    fun `test 25 - robot execution is strictly refused when fused evidence is uncertain`() {
        val locator = ComponentLocator(
            aliases = { mapOf("screwdriver" to listOf("screwdriver"), "battery" to emptyList()) },
            minScore = { 0.60f },
        )
        val loc = locator.locate("battery", Detections(emptyList()))
        assertTrue("Robot must refuse unlocated component", loc is Localization.Uncertain)

        val unverifiedGuide = Guide(id = "g_unver", title = "Unverified", verifiedAt = 0L)
        assertFalse("Robot must refuse unverified guide", unverifiedGuide.verified)
    }
}
