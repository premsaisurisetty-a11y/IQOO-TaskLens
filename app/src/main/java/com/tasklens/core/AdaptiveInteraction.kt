package com.tasklens.core

/**
 * Adaptive Hands-Free Interaction Engine for TaskLens.
 *
 * "TaskLens should speak when it helps,
 *  stay silent when it doesn't,
 *  ask for confirmation when it cannot know,
 *  and never pretend to know what it cannot verify."
 *
 * Pure Kotlin: Zero android.* imports.
 */

enum class InteractionAction {
    SILENT,
    SPEAK_STEP,
    SPEAK_RETRY,
    SHOW_GUIDANCE,
    REQUEST_CONFIRMATION,
    WAIT_FOR_CAMERA,
    WAIT_FOR_USER,
    ADVANCE_STEP,
    SHOW_WARNING,
}

enum class AudioPriority(val level: Int) {
    ACKNOWLEDGEMENT(1),
    RETRY(2),
    STEP_INSTRUCTION(3),
    MANUAL_CONFIRMATION(4),
    SAFETY_WARNING(5);

    fun canInterrupt(current: AudioPriority?): Boolean {
        if (current == null) return true
        return this.level > current.level
    }
}

data class AdaptiveInteractionInputs(
    val mode: Mode = Mode.TAP,
    val stepIndex: Int = 0,
    val stepTitle: String = "",
    val stepInstruction: String = "",
    val stepTranscript: String = "",
    val requiredObjects: List<String> = emptyList(),
    val readiness: LearnerReadinessState = LearnerReadinessState.READY_TO_START,
    val fusedState: FusedEvidenceState = FusedEvidenceState.UNKNOWN,
    val personDetected: Boolean = false,
    val userFar: Boolean = false,
    val cameraStable: Boolean = true,
    val isManuallyConfirmed: Boolean = false,
    val isCoachProcessing: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasSafetyWarning: Boolean = false,
    val safetyWarningText: String? = null,
)

data class AdaptiveInteractionDecision(
    val action: InteractionAction,
    val spokenText: String?,
    val visualGuidance: String?,
    val audioPriority: AudioPriority,
    val reason: String,
    val cooldownRemainingMs: Long = 0L,
    val stuckTimeMs: Long = 0L,
)

class AdaptiveInteractionEngine(
    private val policy: Policy = Policy.DEFAULT,
) {
    private var currentStepIndex: Int = -1
    private var stepStartedAtMs: Long = 0L
    private var lastEvidenceChangeMs: Long = 0L
    private var lastSpokenAtMs: Long = 0L
    private var lastSpokenText: String? = null
    private var lastSpokenPriority: AudioPriority? = null
    private var hasSpokenInitialStep: Boolean = false
    private var isManuallyConfirmed: Boolean = false

    private var wasCameraUnstable: Boolean = false
    private var lastCameraWarningMs: Long = 0L

    private var lastReadinessState: LearnerReadinessState? = null
    private var lastFusedState: FusedEvidenceState? = null
    private var lastSpokenReadiness: LearnerReadinessState? = null

    private var lastStuckRetryMs: Long = 0L

    fun setStepContext(
        index: Int,
        title: String,
        instruction: String,
        transcript: String,
        reqs: List<String> = emptyList(),
        nowMs: Long = 0L,
    ) {
        currentStepIndex = index
        stepStartedAtMs = nowMs
        lastEvidenceChangeMs = nowMs
        lastSpokenAtMs = 0L
        lastSpokenText = null
        lastSpokenPriority = null
        hasSpokenInitialStep = false
        isManuallyConfirmed = false
        wasCameraUnstable = false
        lastCameraWarningMs = 0L
        lastReadinessState = null
        lastFusedState = null
        lastSpokenReadiness = null
        lastStuckRetryMs = 0L
    }

    fun reset(nowMs: Long = 0L) {
        currentStepIndex = -1
        stepStartedAtMs = nowMs
        lastEvidenceChangeMs = nowMs
        lastSpokenAtMs = 0L
        lastSpokenText = null
        lastSpokenPriority = null
        hasSpokenInitialStep = false
        isManuallyConfirmed = false
        wasCameraUnstable = false
        lastCameraWarningMs = 0L
        lastReadinessState = null
        lastFusedState = null
        lastSpokenReadiness = null
        lastStuckRetryMs = 0L
    }

    fun confirmManual(nowMs: Long = 0L) {
        isManuallyConfirmed = true
        lastEvidenceChangeMs = nowMs
    }

    fun onSpeechFinished() {
        lastSpokenPriority = null
    }

    fun evaluate(
        inputs: AdaptiveInteractionInputs,
        currentTimestampMs: Long,
    ): AdaptiveInteractionDecision {
        if (inputs.stepIndex != currentStepIndex) {
            setStepContext(
                index = inputs.stepIndex,
                title = inputs.stepTitle,
                instruction = inputs.stepInstruction,
                transcript = inputs.stepTranscript,
                reqs = inputs.requiredObjects,
                nowMs = currentTimestampMs,
            )
        }

        if (inputs.isManuallyConfirmed) {
            isManuallyConfirmed = true
        }

        // Track evidence state changes for stuck detection & transition triggers
        if (inputs.readiness != lastReadinessState || inputs.fusedState != lastFusedState) {
            lastEvidenceChangeMs = currentTimestampMs
            lastReadinessState = inputs.readiness
            lastFusedState = inputs.fusedState
        }

        val stepElapsedMs = (currentTimestampMs - stepStartedAtMs).coerceAtLeast(0L)
        val timeSinceLastEvidenceMs = (currentTimestampMs - lastEvidenceChangeMs).coerceAtLeast(0L)
        val timeSinceLastSpeechMs = (currentTimestampMs - lastSpokenAtMs).coerceAtLeast(0L)
        val cooldownRemainingMs = (policy.speechCooldownMs - timeSinceLastSpeechMs).coerceAtLeast(0L)

        // 1. SAFETY WARNING (Highest Priority)
        if (inputs.hasSafetyWarning && !inputs.safetyWarningText.isNullOrBlank()) {
            val warningText = inputs.safetyWarningText
            val priority = AudioPriority.SAFETY_WARNING
            if (priority.canInterrupt(lastSpokenPriority) || warningText != lastSpokenText || timeSinceLastSpeechMs > policy.speechCooldownMs) {
                lastSpokenAtMs = currentTimestampMs
                lastSpokenText = warningText
                lastSpokenPriority = priority
                return AdaptiveInteractionDecision(
                    action = InteractionAction.SHOW_WARNING,
                    spokenText = warningText,
                    visualGuidance = warningText,
                    audioPriority = priority,
                    reason = "Critical safety warning active",
                    cooldownRemainingMs = 0L,
                    stuckTimeMs = timeSinceLastEvidenceMs,
                )
            }
            return AdaptiveInteractionDecision(
                action = InteractionAction.SHOW_WARNING,
                spokenText = null,
                visualGuidance = warningText,
                audioPriority = priority,
                reason = "Safety warning displayed (speech debounced)",
                cooldownRemainingMs = cooldownRemainingMs,
                stuckTimeMs = timeSinceLastEvidenceMs,
            )
        }

        // 2. CAMERA INSTABILITY HANDLING
        if (!inputs.cameraStable) {
            val cameraMsg = "Hold the phone steady."
            val priority = AudioPriority.ACKNOWLEDGEMENT
            if (!wasCameraUnstable && (currentTimestampMs - lastCameraWarningMs > policy.speechCooldownMs)) {
                wasCameraUnstable = true
                lastCameraWarningMs = currentTimestampMs
                lastSpokenAtMs = currentTimestampMs
                lastSpokenText = cameraMsg
                lastSpokenPriority = priority
                return AdaptiveInteractionDecision(
                    action = InteractionAction.WAIT_FOR_CAMERA,
                    spokenText = cameraMsg,
                    visualGuidance = "Hold phone steady",
                    audioPriority = priority,
                    reason = "Camera unstable, requested stabilization",
                    cooldownRemainingMs = policy.speechCooldownMs,
                    stuckTimeMs = timeSinceLastEvidenceMs,
                )
            }
            wasCameraUnstable = true
            return AdaptiveInteractionDecision(
                action = InteractionAction.WAIT_FOR_CAMERA,
                spokenText = null,
                visualGuidance = "Hold phone steady",
                audioPriority = priority,
                reason = "Camera unstable, remaining silent until steady",
                cooldownRemainingMs = cooldownRemainingMs,
                stuckTimeMs = timeSinceLastEvidenceMs,
            )
        } else {
            wasCameraUnstable = false
        }

        // 3. USER DISTANCE (userFar) HANDLING
        if (inputs.userFar && inputs.mode != Mode.TALK) {
            // When user is far in non-TALK mode, avoid shouting or frequent audio
            // Offer concise visual fallback
        }

        // 4. VERIFIED COMPLETE
        if (inputs.readiness == LearnerReadinessState.VERIFIED_COMPLETE) {
            val completeMsg = "Step complete."
            val priority = AudioPriority.ACKNOWLEDGEMENT
            if (lastSpokenReadiness != LearnerReadinessState.VERIFIED_COMPLETE &&
                (priority.canInterrupt(lastSpokenPriority) || !inputs.isSpeaking)
            ) {
                lastSpokenReadiness = LearnerReadinessState.VERIFIED_COMPLETE
                lastSpokenAtMs = currentTimestampMs
                lastSpokenText = completeMsg
                lastSpokenPriority = priority
                return AdaptiveInteractionDecision(
                    action = InteractionAction.ADVANCE_STEP,
                    spokenText = completeMsg,
                    visualGuidance = "Step complete",
                    audioPriority = priority,
                    reason = "Step verified complete by multi-source evidence",
                    cooldownRemainingMs = policy.speechCooldownMs,
                    stuckTimeMs = 0L,
                )
            }
            return AdaptiveInteractionDecision(
                action = InteractionAction.ADVANCE_STEP,
                spokenText = null,
                visualGuidance = "Step complete",
                audioPriority = priority,
                reason = "Step verified complete (announcement completed)",
                cooldownRemainingMs = cooldownRemainingMs,
                stuckTimeMs = 0L,
            )
        }

        // 5. MANUAL CONFIRMATION REQUIRED
        if (inputs.readiness == LearnerReadinessState.MANUAL_CONFIRMATION_REQUIRED ||
            inputs.readiness == LearnerReadinessState.LIKELY_DONE_BUT_NEEDS_CONFIRMATION ||
            inputs.fusedState == FusedEvidenceState.MANUAL_REQUIRED
        ) {
            val confirmMsg = "This action needs your confirmation."
            val priority = AudioPriority.MANUAL_CONFIRMATION
            val isNewTrigger = lastSpokenReadiness != inputs.readiness
            if (isNewTrigger && cooldownRemainingMs == 0L && (!inputs.isSpeaking || priority.canInterrupt(lastSpokenPriority))) {
                lastSpokenReadiness = inputs.readiness
                lastSpokenAtMs = currentTimestampMs
                lastSpokenText = confirmMsg
                lastSpokenPriority = priority
                return AdaptiveInteractionDecision(
                    action = InteractionAction.REQUEST_CONFIRMATION,
                    spokenText = confirmMsg,
                    visualGuidance = "Confirm action completion",
                    audioPriority = priority,
                    reason = "Action requires manual user verification",
                    cooldownRemainingMs = policy.speechCooldownMs,
                    stuckTimeMs = timeSinceLastEvidenceMs,
                )
            }
            return AdaptiveInteractionDecision(
                action = InteractionAction.REQUEST_CONFIRMATION,
                spokenText = null,
                visualGuidance = "Confirm action completion",
                audioPriority = priority,
                reason = "Awaiting learner manual confirmation",
                cooldownRemainingMs = cooldownRemainingMs,
                stuckTimeMs = timeSinceLastEvidenceMs,
            )
        }

        // 6. WAITING FOR REQUIRED OBJECT
        if (inputs.readiness == LearnerReadinessState.WAITING_FOR_REQUIRED_OBJECT) {
            val missingTool = inputs.requiredObjects.firstOrNull() ?: "tool"
            val toolMsg = "I can't currently see the required $missingTool."
            val priority = AudioPriority.STEP_INSTRUCTION
            val isNewTrigger = lastSpokenReadiness != inputs.readiness
            if (isNewTrigger && cooldownRemainingMs == 0L && (!inputs.isSpeaking || priority.canInterrupt(lastSpokenPriority))) {
                lastSpokenReadiness = inputs.readiness
                lastSpokenAtMs = currentTimestampMs
                lastSpokenText = toolMsg
                lastSpokenPriority = priority
                return AdaptiveInteractionDecision(
                    action = InteractionAction.SHOW_GUIDANCE,
                    spokenText = toolMsg,
                    visualGuidance = "Looking for $missingTool",
                    audioPriority = priority,
                    reason = "Required object not yet detected in frame",
                    cooldownRemainingMs = policy.speechCooldownMs,
                    stuckTimeMs = timeSinceLastEvidenceMs,
                )
            }
            return AdaptiveInteractionDecision(
                action = InteractionAction.SHOW_GUIDANCE,
                spokenText = null,
                visualGuidance = "Looking for $missingTool",
                audioPriority = priority,
                reason = "Waiting for required object (speech debounced)",
                cooldownRemainingMs = cooldownRemainingMs,
                stuckTimeMs = timeSinceLastEvidenceMs,
            )
        }

        // 7. CONFLICTING EVIDENCE
        if (inputs.readiness == LearnerReadinessState.CONFLICTING_EVIDENCE ||
            inputs.fusedState == FusedEvidenceState.CONFLICTING
        ) {
            val conflictMsg = "The camera evidence is inconsistent. Hold the phone steady and try again."
            val priority = AudioPriority.STEP_INSTRUCTION
            val isNewTrigger = lastSpokenReadiness != inputs.readiness
            if (isNewTrigger && cooldownRemainingMs == 0L && (!inputs.isSpeaking || priority.canInterrupt(lastSpokenPriority))) {
                lastSpokenReadiness = inputs.readiness
                lastSpokenAtMs = currentTimestampMs
                lastSpokenText = conflictMsg
                lastSpokenPriority = priority
                return AdaptiveInteractionDecision(
                    action = InteractionAction.SHOW_GUIDANCE,
                    spokenText = conflictMsg,
                    visualGuidance = "Evidence inconsistent",
                    audioPriority = priority,
                    reason = "Conflicting visual or scene signals detected",
                    cooldownRemainingMs = policy.speechCooldownMs,
                    stuckTimeMs = timeSinceLastEvidenceMs,
                )
            }
            return AdaptiveInteractionDecision(
                action = InteractionAction.SHOW_GUIDANCE,
                spokenText = null,
                visualGuidance = "Evidence inconsistent",
                audioPriority = priority,
                reason = "Conflicting evidence active (speech debounced)",
                cooldownRemainingMs = cooldownRemainingMs,
                stuckTimeMs = timeSinceLastEvidenceMs,
            )
        }

        // 8. INITIAL STEP INTRODUCTION (PENDING / READY_TO_START)
        if (!hasSpokenInitialStep) {
            hasSpokenInitialStep = true
            val primaryText = inputs.stepInstruction
                .ifBlank { inputs.stepTranscript }
                .ifBlank { inputs.stepTitle }
                .ifBlank { "Step ${inputs.stepIndex + 1}" }

            val spokenText = when (inputs.mode) {
                Mode.TAP -> if (inputs.userFar) primaryText else primaryText
                Mode.TALK -> primaryText
                Mode.EASY -> primaryText
                Mode.HANDS -> primaryText
            }

            val priority = AudioPriority.STEP_INSTRUCTION
            lastSpokenAtMs = currentTimestampMs
            lastSpokenText = spokenText
            lastSpokenPriority = priority
            lastSpokenReadiness = inputs.readiness

            return AdaptiveInteractionDecision(
                action = InteractionAction.SPEAK_STEP,
                spokenText = spokenText,
                visualGuidance = inputs.stepTitle.ifBlank { primaryText },
                audioPriority = priority,
                reason = "Initial step presentation",
                cooldownRemainingMs = policy.speechCooldownMs,
                stuckTimeMs = 0L,
            )
        }

        // 9. STUCK LEARNER RETRY CHECK
        val isStuckTimeExceeded = timeSinceLastEvidenceMs >= policy.stuckLearnerDurationMs
        val isStuckRetryCooldownExceeded = (currentTimestampMs - lastStuckRetryMs) >= policy.stuckLearnerRetryIntervalMs
        val isEligibleForStuckRetry = isStuckTimeExceeded &&
                isStuckRetryCooldownExceeded &&
                !isManuallyConfirmed &&
                inputs.cameraStable &&
                !inputs.isSpeaking &&
                inputs.readiness != LearnerReadinessState.VERIFIED_COMPLETE

        if (isEligibleForStuckRetry) {
            lastStuckRetryMs = currentTimestampMs
            lastSpokenAtMs = currentTimestampMs
            val retryHint = deriveStuckRetryHint(inputs)
            val priority = AudioPriority.RETRY
            lastSpokenText = retryHint
            lastSpokenPriority = priority

            return AdaptiveInteractionDecision(
                action = InteractionAction.SPEAK_RETRY,
                spokenText = retryHint,
                visualGuidance = "Hint: $retryHint",
                audioPriority = priority,
                reason = "Step active for ${timeSinceLastEvidenceMs / 1000}s with no evidence improvement",
                cooldownRemainingMs = policy.speechCooldownMs,
                stuckTimeMs = timeSinceLastEvidenceMs,
            )
        }

        // 10. IN_PROGRESS / STRONGLY_SUPPORTED / QUIET WORKING STATE
        val workingGuidance = when {
            inputs.readiness == LearnerReadinessState.IN_PROGRESS -> "Working on step..."
            inputs.fusedState == FusedEvidenceState.SUPPORTED -> "Evidence supported"
            inputs.fusedState == FusedEvidenceState.STRONGLY_SUPPORTED -> "Observing completion..."
            inputs.fusedState == FusedEvidenceState.INSUFFICIENT -> "Observing workspace..."
            else -> "Watching for progress..."
        }

        return AdaptiveInteractionDecision(
            action = InteractionAction.SILENT,
            spokenText = null,
            visualGuidance = workingGuidance,
            audioPriority = AudioPriority.ACKNOWLEDGEMENT,
            reason = "Working in progress, staying quiet to avoid disturbing learner",
            cooldownRemainingMs = cooldownRemainingMs,
            stuckTimeMs = timeSinceLastEvidenceMs,
        )
    }

    private fun deriveStuckRetryHint(inputs: AdaptiveInteractionInputs): String {
        val raw = inputs.stepInstruction.ifBlank { inputs.stepTranscript }.lowercase()
        return when {
            raw.contains("screw") -> "Start with the visible screws."
            raw.contains("battery") || raw.contains("connector") -> "Check the battery connector position."
            raw.contains("panel") || raw.contains("cover") -> "Gently loosen around the edges."
            raw.contains("screwdriver") -> "Select the matching screwdriver."
            inputs.stepInstruction.isNotBlank() -> inputs.stepInstruction
            else -> "Continue with the current step."
        }
    }
}
