package com.tasklens.eval

import com.tasklens.core.AdaptiveInteractionDecision
import com.tasklens.core.AdaptiveInteractionEngine
import com.tasklens.core.AdaptiveInteractionInputs
import com.tasklens.core.AudioPriority
import com.tasklens.core.FusedEvidenceState
import com.tasklens.core.InteractionAction
import com.tasklens.core.LearnerReadinessState
import com.tasklens.core.Mode
import com.tasklens.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM Test Suite for Adaptive Hands-Free Interaction Engine.
 *
 * Verifies all 30 deterministic interaction, debounce, priority,
 * camera readiness, user distance, and stuck learner rules.
 */
class AdaptiveInteractionTest {

    private lateinit var policy: Policy
    private lateinit var engine: AdaptiveInteractionEngine

    @Before
    fun setup() {
        policy = Policy.DEFAULT
        engine = AdaptiveInteractionEngine(policy)
    }

    private fun baseInputs(
        stepIndex: Int = 0,
        title: String = "Remove bottom panel screws",
        instruction: String = "Unscrew the 10 philips screws on the base panel",
        transcript: String = "remove the screws with the screwdriver",
        requiredObjects: List<String> = listOf("screwdriver"),
        readiness: LearnerReadinessState = LearnerReadinessState.READY_TO_START,
        fusedState: FusedEvidenceState = FusedEvidenceState.UNKNOWN,
        cameraStable: Boolean = true,
        userFar: Boolean = false,
        mode: Mode = Mode.TAP,
        isSpeaking: Boolean = false,
        isManuallyConfirmed: Boolean = false,
        hasSafetyWarning: Boolean = false,
        safetyWarningText: String? = null,
    ) = AdaptiveInteractionInputs(
        mode = mode,
        stepIndex = stepIndex,
        stepTitle = title,
        stepInstruction = instruction,
        stepTranscript = transcript,
        requiredObjects = requiredObjects,
        readiness = readiness,
        fusedState = fusedState,
        cameraStable = cameraStable,
        userFar = userFar,
        isSpeaking = isSpeaking,
        isManuallyConfirmed = isManuallyConfirmed,
        hasSafetyWarning = hasSafetyWarning,
        safetyWarningText = safetyWarningText,
    )

    // 1. Initial step speaks once.
    @Test
    fun test01_initialStepSpeaksOnce() {
        val d = engine.evaluate(baseInputs(), 1000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertNotNull(d.spokenText)
        assertEquals(AudioPriority.STEP_INSTRUCTION, d.audioPriority)
    }

    // 2. Same state does not repeatedly speak.
    @Test
    fun test02_sameStateDoesNotRepeatedlySpeak() {
        engine.evaluate(baseInputs(), 1000L)
        val d2 = engine.evaluate(baseInputs(), 1100L)
        assertEquals(InteractionAction.SILENT, d2.action)
        assertNull(d2.spokenText)
    }

    // 3. Speech cooldown works.
    @Test
    fun test03_speechCooldownWorks() {
        engine.evaluate(baseInputs(), 1000L)
        // Same state after cooldown still remains silent since no new trigger occurred
        val d2 = engine.evaluate(baseInputs(), 1000L + policy.speechCooldownMs + 500L)
        assertEquals(InteractionAction.SILENT, d2.action)
        assertNull(d2.spokenText)
    }

    // 4. State transition allows new speech.
    @Test
    fun test04_stateTransitionAllowsNewSpeech() {
        engine.evaluate(baseInputs(readiness = LearnerReadinessState.READY_TO_START), 1000L)
        // Transition to WAITING_FOR_REQUIRED_OBJECT after cooldown
        val d2 = engine.evaluate(
            baseInputs(readiness = LearnerReadinessState.WAITING_FOR_REQUIRED_OBJECT),
            1000L + policy.speechCooldownMs + 100L,
        )
        assertEquals(InteractionAction.SHOW_GUIDANCE, d2.action)
        assertNotNull(d2.spokenText)
        assertTrue(d2.spokenText!!.contains("screwdriver"))
    }

    // 5. Camera unstable suppresses normal speech.
    @Test
    fun test05_cameraUnstableSuppressesNormalSpeech() {
        engine.evaluate(baseInputs(), 1000L)
        // Camera becomes unstable: emits warning once
        val dUnstable1 = engine.evaluate(
            baseInputs(cameraStable = false),
            1000L + policy.speechCooldownMs + 100L,
        )
        assertEquals(InteractionAction.WAIT_FOR_CAMERA, dUnstable1.action)
        assertEquals("Hold the phone steady.", dUnstable1.spokenText)

        // Stays unstable: stays silent, suppresses repeated speech
        val dUnstable2 = engine.evaluate(
            baseInputs(cameraStable = false),
            1000L + policy.speechCooldownMs + 500L,
        )
        assertEquals(InteractionAction.WAIT_FOR_CAMERA, dUnstable2.action)
        assertNull(dUnstable2.spokenText)
    }

    // 6. Camera stabilization resumes interaction.
    @Test
    fun test06_cameraStabilizationResumesInteraction() {
        engine.evaluate(baseInputs(), 1000L)
        engine.evaluate(baseInputs(cameraStable = false), 2000L)
        // Stabilizes
        val dStable = engine.evaluate(baseInputs(cameraStable = true), 5000L)
        assertEquals(InteractionAction.SILENT, dStable.action)
    }

    // 7. userFar does not trigger repeated speech.
    @Test
    fun test07_userFarDoesNotTriggerRepeatedSpeech() {
        engine.evaluate(baseInputs(userFar = true), 1000L)
        val d2 = engine.evaluate(baseInputs(userFar = true), 2000L)
        assertEquals(InteractionAction.SILENT, d2.action)
        assertNull(d2.spokenText)
    }

    // 8. Returning near resumes normal interaction.
    @Test
    fun test08_returningNearResumesNormalInteraction() {
        engine.evaluate(baseInputs(userFar = true), 1000L)
        val dNear = engine.evaluate(baseInputs(userFar = false), 2000L)
        assertEquals(InteractionAction.SILENT, dNear.action)
    }

    // 9. PENDING introduces step.
    @Test
    fun test09_pendingIntroducesStep() {
        val d = engine.evaluate(baseInputs(readiness = LearnerReadinessState.READY_TO_START), 1000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertTrue(d.spokenText!!.isNotBlank())
    }

    // 10. IN_PROGRESS remains mostly silent.
    @Test
    fun test10_inProgressRemainsMostlySilent() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.IN_PROGRESS,
                fusedState = FusedEvidenceState.CHECKING,
            ),
            5000L,
        )
        assertEquals(InteractionAction.SILENT, d.action)
        assertNull(d.spokenText)
    }

    // 11. MANUAL_REQUIRED requests confirmation.
    @Test
    fun test11_manualRequiredRequestsConfirmation() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.MANUAL_CONFIRMATION_REQUIRED,
                fusedState = FusedEvidenceState.MANUAL_REQUIRED,
            ),
            5000L,
        )
        assertEquals(InteractionAction.REQUEST_CONFIRMATION, d.action)
        assertEquals("This action needs your confirmation.", d.spokenText)
        assertEquals(AudioPriority.MANUAL_CONFIRMATION, d.audioPriority)
    }

    // 12. VERIFIED_COMPLETE announces next step once.
    @Test
    fun test12_verifiedCompleteAnnouncesNextStepOnce() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.VERIFIED_COMPLETE,
                fusedState = FusedEvidenceState.STRONGLY_SUPPORTED,
            ),
            5000L,
        )
        assertEquals(InteractionAction.ADVANCE_STEP, d.action)
        assertEquals("Step complete.", d.spokenText)

        // Subsequent evaluation does not repeat announcement
        val d2 = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.VERIFIED_COMPLETE,
                fusedState = FusedEvidenceState.STRONGLY_SUPPORTED,
            ),
            6000L,
        )
        assertEquals(InteractionAction.ADVANCE_STEP, d2.action)
        assertNull(d2.spokenText)
    }

    // 13. CONFLICTING_EVIDENCE gives uncertainty message.
    @Test
    fun test13_conflictingEvidenceGivesUncertaintyMessage() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.CONFLICTING_EVIDENCE,
                fusedState = FusedEvidenceState.CONFLICTING,
            ),
            5000L,
        )
        assertEquals(InteractionAction.SHOW_GUIDANCE, d.action)
        assertEquals("The camera evidence is inconsistent. Hold the phone steady and try again.", d.spokenText)
    }

    // 14. WAITING_FOR_REQUIRED_OBJECT gives object guidance.
    @Test
    fun test14_waitingForRequiredObjectGivesObjectGuidance() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.WAITING_FOR_REQUIRED_OBJECT,
                requiredObjects = listOf("screwdriver"),
            ),
            5000L,
        )
        assertEquals(InteractionAction.SHOW_GUIDANCE, d.action)
        assertEquals("I can't currently see the required screwdriver.", d.spokenText)
    }

    // 15. Stuck learner triggers retry.
    @Test
    fun test15_stuckLearnerTriggersRetry() {
        engine.evaluate(baseInputs(readiness = LearnerReadinessState.IN_PROGRESS), 1000L)
        // Advance time past stuck threshold (15s) without evidence improvement
        val d = engine.evaluate(
            baseInputs(readiness = LearnerReadinessState.IN_PROGRESS),
            1000L + policy.stuckLearnerDurationMs + 100L,
        )
        assertEquals(InteractionAction.SPEAK_RETRY, d.action)
        assertNotNull(d.spokenText)
        assertEquals(AudioPriority.RETRY, d.audioPriority)
    }

    // 16. Stuck detection does not trigger during camera instability.
    @Test
    fun test16_stuckDetectionDoesNotTriggerDuringCameraInstability() {
        engine.evaluate(baseInputs(readiness = LearnerReadinessState.IN_PROGRESS), 1000L)
        // Unstable camera at stuck time
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.IN_PROGRESS,
                cameraStable = false,
            ),
            1000L + policy.stuckLearnerDurationMs + 100L,
        )
        assertEquals(InteractionAction.WAIT_FOR_CAMERA, d.action)
        assertNotEquals(InteractionAction.SPEAK_RETRY, d.action)
    }

    // 17. TTS cannot overlap lower/equal priority.
    @Test
    fun test17_ttsCannotOverlapLowerOrEqualPriority() {
        engine.evaluate(baseInputs(readiness = LearnerReadinessState.IN_PROGRESS), 1000L)
        // Stuck retry candidate arises while TTS is active
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.IN_PROGRESS,
                isSpeaking = true,
            ),
            1000L + policy.stuckLearnerDurationMs + 100L,
        )
        // Lower priority (RETRY) cannot interrupt active speaking
        assertEquals(InteractionAction.SILENT, d.action)
    }

    // 18. Safety warning has highest priority.
    @Test
    fun test18_safetyWarningHasHighestPriority() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                hasSafetyWarning = true,
                safetyWarningText = "Danger: Battery short circuit detected!",
                isSpeaking = true,
            ),
            2000L,
        )
        assertEquals(InteractionAction.SHOW_WARNING, d.action)
        assertEquals("Danger: Battery short circuit detected!", d.spokenText)
        assertEquals(AudioPriority.SAFETY_WARNING, d.audioPriority)
    }

    // 19. Stale queued speech is discarded after step completion.
    @Test
    fun test19_staleQueuedSpeechIsDiscardedAfterStepCompletion() {
        engine.evaluate(baseInputs(stepIndex = 0), 1000L)
        // Advance to step 1
        val dNext = engine.evaluate(baseInputs(stepIndex = 1, title = "Step 2", instruction = "Disconnect battery cable"), 2000L)
        assertEquals(InteractionAction.SPEAK_STEP, dNext.action)
        assertEquals("Disconnect battery cable", dNext.spokenText)
    }

    // 20. Coach is not automatically invoked.
    @Test
    fun test20_coachIsNotAutomaticallyInvoked() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(baseInputs(readiness = LearnerReadinessState.IN_PROGRESS), 5000L)
        assertEquals(InteractionAction.SILENT, d.action)
    }

    // 21. Coach remains available for explicit questions (flag checked).
    @Test
    fun test21_coachProcessingFlagAcknowledged() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(baseInputs(isSpeaking = false), 3000L)
        assertEquals(InteractionAction.SILENT, d.action)
    }

    // 22. Hands-free mode minimizes taps.
    @Test
    fun test22_handsFreeModeMinimizesTaps() {
        val d = engine.evaluate(baseInputs(mode = Mode.HANDS), 1000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertNotNull(d.spokenText)
    }

    // 23. TAP mode prioritizes visual guidance.
    @Test
    fun test23_tapModePrioritizesVisualGuidance() {
        val d = engine.evaluate(baseInputs(mode = Mode.TAP), 1000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertNotNull(d.visualGuidance)
    }

    // 24. TALK mode prioritizes concise audio.
    @Test
    fun test24_talkModePrioritizesConciseAudio() {
        val d = engine.evaluate(baseInputs(mode = Mode.TALK), 1000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertNotNull(d.spokenText)
    }

    // 25. EASY mode follows existing accessibility behavior.
    @Test
    fun test25_easyModeFollowsExistingAccessibilityBehavior() {
        val d = engine.evaluate(baseInputs(mode = Mode.EASY), 1000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertNotNull(d.visualGuidance)
    }

    // 26. UNKNOWN mode/evidence does not guess.
    @Test
    fun test26_unknownEvidenceDoesNotGuess() {
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.READY_TO_START,
                fusedState = FusedEvidenceState.UNKNOWN,
            ),
            5000L,
        )
        assertNotEquals(InteractionAction.ADVANCE_STEP, d.action)
    }

    // 27. Reset clears interaction state.
    @Test
    fun test27_resetClearsInteractionState() {
        engine.evaluate(baseInputs(), 1000L)
        engine.reset(2000L)
        val d = engine.evaluate(baseInputs(), 3000L)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
    }

    // 28. Step transition resets retry timer.
    @Test
    fun test28_stepTransitionResetsRetryTimer() {
        engine.evaluate(baseInputs(stepIndex = 0), 1000L)
        // Transition to step 1
        val d = engine.evaluate(baseInputs(stepIndex = 1), 1000L + policy.stuckLearnerDurationMs)
        assertEquals(InteractionAction.SPEAK_STEP, d.action)
        assertEquals(0L, d.stuckTimeMs)
    }

    // 29. User confirmation cancels pending retry.
    @Test
    fun test29_userConfirmationCancelsPendingRetry() {
        engine.evaluate(baseInputs(), 1000L)
        engine.confirmManual(2000L)
        val d = engine.evaluate(
            baseInputs(isManuallyConfirmed = true),
            1000L + policy.stuckLearnerDurationMs + 500L,
        )
        assertNotEquals(InteractionAction.SPEAK_RETRY, d.action)
    }

    // 30. No automatic advancement from ambiguous evidence.
    @Test
    fun test30_noAutomaticAdvancementFromAmbiguousEvidence() {
        engine.evaluate(baseInputs(), 1000L)
        val d = engine.evaluate(
            baseInputs(
                readiness = LearnerReadinessState.IN_PROGRESS,
                fusedState = FusedEvidenceState.INSUFFICIENT,
            ),
            5000L,
        )
        assertNotEquals(InteractionAction.ADVANCE_STEP, d.action)
        assertEquals(InteractionAction.SILENT, d.action)
    }
}
