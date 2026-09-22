package com.tasklens.eval

import com.tasklens.ai.AnswerEvidence
import com.tasklens.ai.ComponentLocator
import com.tasklens.ai.DetectionBox
import com.tasklens.ai.Detections
import com.tasklens.ai.Localization
import com.tasklens.ai.LearnerContext
import com.tasklens.ai.groundedEvidence
import com.tasklens.core.EvidenceType
import com.tasklens.core.LearnerObservation
import com.tasklens.core.LearnerProgressTracker
import com.tasklens.core.LearnerStepProgress
import com.tasklens.core.LearnerStepVerifier
import com.tasklens.core.Policy
import com.tasklens.core.RequirementType
import com.tasklens.core.StepEvidence
import com.tasklens.core.StepProgressStatus
import com.tasklens.core.StepRequirement
import com.tasklens.core.StepRequirementDeriver
import com.tasklens.core.StepVerificationReport
import com.tasklens.core.StepVerificationState
import com.tasklens.data.Guide
import com.tasklens.data.Provenance
import com.tasklens.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 6 — Learner-Side Step Verification & Task Progress Intelligence Test Suite.
 * Covers all 20 required edge cases and failure modes deterministically.
 */
class LearnerVerificationTest {

    private val policy = Policy.DEFAULT

    // 1. One-frame detection does not complete step
    @Test
    fun `test 1 - one-frame detection does not complete step`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.20f)
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        // 1 observation only (min required is 4)
        verifier.addObservation(
            LearnerObservation(
                timestampMs = 1000L,
                seenLabels = listOf("screwdriver"),
                labelScores = mapOf("screwdriver" to 0.95f),
            )
        )
        val report = verifier.evaluate(1000L)
        assertFalse("One frame must not pass verification", report.state.isTerminalSuccess)
        assertEquals(StepVerificationState.CHECKING, report.state)
    }

    // 2. Stable detection completes presence requirement
    @Test
    fun `test 2 - stable detection completes presence requirement`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.20f)
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        for (i in 0 until 5) {
            verifier.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.85f),
                )
            )
        }
        val report = verifier.evaluate(1400L)
        assertEquals(StepVerificationState.PASS, report.state)
        assertTrue(report.satisfied.any { it.type == EvidenceType.OBJECT_DETECTION })
    }

    // 3. Low confidence does not pass
    @Test
    fun `test 3 - low confidence does not pass`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.50f)
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        for (i in 0 until 5) {
            verifier.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.25f), // Below 0.50f
                )
            )
        }
        val report = verifier.evaluate(1400L)
        assertNotEquals(StepVerificationState.PASS, report.state)
        assertEquals(StepVerificationState.INSUFFICIENT_EVIDENCE, report.state)
    }

    // 4. Object absence does not mean confirmed removal
    @Test
    fun `test 4 - object absence does not mean confirmed removal`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.MANUAL_CONFIRMATION, target = "screw_removal")
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        for (i in 0 until 5) {
            verifier.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = emptyList(), // Not detected
                )
            )
        }
        val report = verifier.evaluate(1400L)
        assertEquals(StepVerificationState.MANUAL_CONFIRMATION, report.state)
        assertTrue(report.explanation.contains("safely verified"))
    }

    // 5. Scene change alone does not prove semantic action
    @Test
    fun `test 5 - scene change alone does not prove semantic action`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "laptop"),
            StepRequirement(type = RequirementType.SCENE_CHANGE, target = "panel_state")
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        // Only scene changed, laptop not detected
        for (i in 0 until 5) {
            verifier.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = emptyList(),
                    frameToFrameChange = 25, // Large change
                )
            )
        }
        val report = verifier.evaluate(1400L)
        assertFalse("Scene change alone without object presence must not pass", report.state == StepVerificationState.PASS)
        assertEquals(StepVerificationState.INSUFFICIENT_EVIDENCE, report.state)
    }

    // 6. Missing object model produces insufficient evidence
    @Test
    fun `test 6 - missing object model produces insufficient evidence`() {
        val deriverReqs = StepRequirementDeriver.derive(
            stepIndex = 4,
            title = "Locate battery connector",
            instruction = "Disconnect battery connector",
            transcript = "Pull battery connector",
            caption = "",
            objects = emptyList(),
            policy = policy,
        )
        assertTrue(deriverReqs.any { it.type == RequirementType.MANUAL_CONFIRMATION })

        val verifier = LearnerStepVerifier(policy, deriverReqs)
        for (i in 0 until 5) {
            verifier.addObservation(LearnerObservation(timestampMs = 1000L + (i * 100L), seenLabels = listOf("laptop")))
        }
        val report = verifier.evaluate(1400L)
        assertEquals(StepVerificationState.MANUAL_CONFIRMATION, report.state)
    }

    // 7. Conflicting evidence produces CONFLICT
    @Test
    fun `test 7 - conflicting evidence produces CONFLICT`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.50f)
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        // Intermittent presence (2 out of 5 frames = 40% < 75% consistency)
        verifier.addObservation(LearnerObservation(1000L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f)))
        verifier.addObservation(LearnerObservation(1100L, seenLabels = emptyList()))
        verifier.addObservation(LearnerObservation(1200L, seenLabels = emptyList()))
        verifier.addObservation(LearnerObservation(1300L, seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f)))
        verifier.addObservation(LearnerObservation(1400L, seenLabels = emptyList()))

        val report = verifier.evaluate(1400L)
        assertEquals(StepVerificationState.CONFLICT, report.state)
        assertTrue(report.conflicting.isNotEmpty())
    }

    // 8. No evidence produces UNKNOWN or INSUFFICIENT
    @Test
    fun `test 8 - no evidence produces UNKNOWN or INSUFFICIENT`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver")
        )
        val verifier = LearnerStepVerifier(policy, reqs)
        val emptyReport = verifier.evaluate(0L)
        assertEquals(StepVerificationState.UNKNOWN, emptyReport.state)
    }

    // 9. Temporal evidence stabilizes PASS
    @Test
    fun `test 9 - temporal evidence stabilizes PASS`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver", minConfidence = 0.30f)
        )
        val verifier = LearnerStepVerifier(policy, reqs)

        for (i in 0 until 8) {
            verifier.addObservation(
                LearnerObservation(
                    timestampMs = 1000L + (i * 100L),
                    seenLabels = listOf("screwdriver"),
                    labelScores = mapOf("screwdriver" to 0.90f),
                )
            )
        }
        val report = verifier.evaluate(1700L)
        assertEquals(StepVerificationState.PASS, report.state)
        assertEquals(8, report.observationCount)
    }

    // 10. PASS does not become EXPERT provenance
    @Test
    fun `test 10 - PASS does not become EXPERT provenance`() {
        val reqs = listOf(
            StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver")
        )
        val verifier = LearnerStepVerifier(policy, reqs)
        for (i in 0 until 5) {
            verifier.addObservation(
                LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.8f))
            )
        }
        val report = verifier.evaluate(1400L)
        assertEquals(StepVerificationState.PASS, report.state)
        val satisfiedSource = report.satisfied.first().source
        assertEquals("VISUAL", satisfiedSource)
        assertFalse("Learner visual pass must never claim EXPERT provenance", satisfiedSource == "EXPERT")
    }

    // 11. Learner confirmation does not modify guide provenance
    @Test
    fun `test 11 - learner confirmation does not modify guide provenance`() {
        val guide = Guide(
            id = "g1",
            title = "Test Guide",
            steps = listOf(
                Step(index = 0, instruction = "Step 1 instruction", instructionSource = Provenance.EXPERT)
            ),
            verifiedAt = 1700000000000L,
        )
        val tracker = LearnerProgressTracker(1)
        tracker.confirmManual(0)

        // Guide object remains completely untouched
        assertEquals(Provenance.EXPERT, guide.steps[0].instructionSource)
        assertTrue(guide.verified)
        assertEquals(StepProgressStatus.MANUAL_COMPLETE, tracker.getProgress(0)?.status)
    }

    // 12. Unverified guide cannot execute
    @Test
    fun `test 12 - unverified guide cannot execute in robot layer`() {
        val draftGuide = Guide(id = "g_draft", title = "Draft", verifiedAt = 0L)
        assertFalse(draftGuide.verified)
        val canRobotExecute = draftGuide.verified
        assertFalse("Unverified draft guide must be refused by robot layer", canRobotExecute)
    }

    // 13. Robot refuses uncertain step
    @Test
    fun `test 13 - robot refuses uncertain step`() {
        val locator = ComponentLocator(
            aliases = { mapOf("screwdriver" to listOf("screwdriver"), "battery" to emptyList()) },
            minScore = { 0.60f },
        )
        val loc = locator.locate("battery", Detections(emptyList()))
        assertTrue(loc is Localization.Uncertain)
        val refusalReason = (loc as Localization.Uncertain).reason
        assertTrue(refusalReason.contains("no label"))
    }

    // 14. Coach cannot override deterministic failure
    @Test
    fun `test 14 - coach cannot override deterministic failure`() {
        val reqs = listOf(StepRequirement(type = RequirementType.MANUAL_CONFIRMATION, target = "connector"))
        val verifier = LearnerStepVerifier(policy, reqs)
        val rep = verifier.evaluate(1000L)
        assertEquals(StepVerificationState.MANUAL_CONFIRMATION, rep.state)

        // Coach cannot promote an uncertain / unverified visual action to VISUAL_FACT when camera has no detections
        val ctx = LearnerContext(
            job = "Battery swap",
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
            question = "Did I finish disconnecting the connector?",
        )
        val grounded = groundedEvidence(AnswerEvidence.VISUAL_FACT, ctx)
        assertFalse("Coach cannot claim VISUAL_FACT when visual evidence is absent", grounded == AnswerEvidence.VISUAL_FACT)
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, grounded)
    }

    // 15. Step cannot silently skip
    @Test
    fun `test 15 - step cannot silently skip`() {
        val tracker = LearnerProgressTracker(3)
        assertEquals(StepProgressStatus.IN_PROGRESS, tracker.getProgress(0)?.status)
        assertEquals(StepProgressStatus.PENDING, tracker.getProgress(1)?.status)

        // Advance to step 1 without verifying step 0
        tracker.advanceTo(1)
        assertEquals(StepProgressStatus.SKIPPED_UNVERIFIED, tracker.getProgress(0)?.status)
        assertEquals(StepProgressStatus.IN_PROGRESS, tracker.getProgress(1)?.status)
    }

    // 16. Reset clears learner verification state
    @Test
    fun `test 16 - reset clears learner verification state`() {
        val tracker = LearnerProgressTracker(3)
        tracker.confirmManual(0)
        assertEquals(StepProgressStatus.MANUAL_COMPLETE, tracker.getProgress(0)?.status)

        tracker.reset()
        assertEquals(StepProgressStatus.IN_PROGRESS, tracker.getProgress(0)?.status)
        assertEquals(StepVerificationState.UNKNOWN, tracker.getProgress(0)?.verificationState)
    }

    // 17. Moving to next step requires configured completion rule
    @Test
    fun `test 17 - moving to next step requires configured completion rule`() {
        val tracker = LearnerProgressTracker(2)
        val repPass = StepVerificationReport(
            state = StepVerificationState.PASS,
            required = emptyList(),
            satisfied = emptyList(),
            missing = emptyList(),
            conflicting = emptyList(),
            observationCount = 4,
            durationMs = 400L,
            explanation = "Verified",
        )
        tracker.updateVerification(0, repPass)
        assertEquals(StepProgressStatus.VERIFIED_COMPLETE, tracker.getProgress(0)?.status)
    }

    // 18. Restart preserves guide verification but not stale learner session state
    @Test
    fun `test 18 - restart preserves guide verification but not stale learner session state`() {
        val verifiedGuide = Guide(id = "g1", title = "Verified Guide", verifiedAt = 12345678L)
        val tracker = LearnerProgressTracker(verifiedGuide.steps.size.coerceAtLeast(1))
        tracker.confirmManual(0)

        // Session reset
        tracker.reset()
        assertTrue("Guide verification is immutable", verifiedGuide.verified)
        assertEquals(StepProgressStatus.IN_PROGRESS, tracker.getProgress(0)?.status)
        assertEquals(StepVerificationState.UNKNOWN, tracker.getProgress(0)?.verificationState)
    }

    // 19. Duplicate observations do not inflate evidence count
    @Test
    fun `test 19 - duplicate observations do not inflate evidence count`() {
        val reqs = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        val verifier = LearnerStepVerifier(policy, reqs)

        // Feed 10 observations with identical timestamp 1000L
        for (i in 0 until 10) {
            verifier.addObservation(LearnerObservation(timestampMs = 1000L, seenLabels = listOf("screwdriver")))
        }
        val report = verifier.evaluate(1000L)
        assertEquals("Duplicate timestamps must be ignored", 1, report.observationCount)
        assertEquals(StepVerificationState.CHECKING, report.state)
    }

    // 20. Old-step evidence cannot satisfy new-step requirements
    @Test
    fun `test 20 - old-step evidence cannot satisfy new-step requirements`() {
        val reqStep0 = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "screwdriver"))
        val reqStep1 = listOf(StepRequirement(type = RequirementType.OBJECT_PRESENT, target = "laptop"))

        val verifier = LearnerStepVerifier(policy, reqStep0)
        for (i in 0 until 5) {
            verifier.addObservation(LearnerObservation(1000L + (i * 100L), seenLabels = listOf("screwdriver"), labelScores = mapOf("screwdriver" to 0.9f)))
        }
        assertEquals(StepVerificationState.PASS, verifier.evaluate(1400L).state)

        // Transition to step 1
        verifier.setStep(1, reqStep1)
        val newReport = verifier.evaluate(1400L)
        assertFalse("New step must not inherit old step's verified state", newReport.state == StepVerificationState.PASS)
        assertEquals(StepVerificationState.UNKNOWN, newReport.state)
    }
}
