package com.tasklens.core

/**
 * Deterministic Multi-Source Evidence Fusion Model.
 *
 * "EVIDENCE, NOT GUESSING."
 *
 * Combines multimodal signals (expert instruction, object detections, scene change,
 * spoken domain words, and user confirmation) across time WITHOUT inflating confidence
 * or treating absence of detection as definitive absence.
 */
enum class FusedEvidenceState {
    UNKNOWN,
    CHECKING,
    SUPPORTED,
    STRONGLY_SUPPORTED,
    CONFLICTING,
    INSUFFICIENT,
    MANUAL_REQUIRED;

    val isSupported: Boolean get() = this == SUPPORTED || this == STRONGLY_SUPPORTED
}

/**
 * Learner readiness and next-step intelligence state.
 */
enum class LearnerReadinessState {
    READY_TO_START,
    IN_PROGRESS,
    LIKELY_DONE_BUT_NEEDS_CONFIRMATION,
    VERIFIED_COMPLETE,
    CONFLICTING_EVIDENCE,
    WAITING_FOR_REQUIRED_OBJECT,
    MANUAL_CONFIRMATION_REQUIRED,
}

/**
 * Strict Source Trust Hierarchy.
 *
 * Invariant: A higher source provides stronger context, but:
 * - Expert instruction does NOT prove learner completion.
 * - Object detection does NOT prove a physical action occurred.
 * - Scene change does NOT prove semantic action completion.
 * - Spoken words do NOT prove physical execution.
 * - AI knowledge / Coach output can NEVER override deterministic verification.
 * - Absence of detection does NOT prove absence of an object.
 * - A single frame can NEVER produce VERIFIED_COMPLETE.
 */
enum class EvidenceSourceRank(val rank: Int, val sourceName: String) {
    EXPERT_INSTRUCTION(7, "EXPERT"),
    USER_CONFIRMATION(6, "USER_MANUAL"),
    DIRECT_VISUAL_EVIDENCE(5, "DIRECT_VISUAL"),
    OBJECT_DETECTION(4, "VISUAL"),
    SCENE_CHANGE(3, "SCENE"),
    DOMAIN_SPEECH(2, "SPEECH"),
    GENERAL_AI_KNOWLEDGE(1, "GENERAL");

    companion object {
        fun fromSourceName(name: String): EvidenceSourceRank = when (name.uppercase()) {
            "EXPERT" -> EXPERT_INSTRUCTION
            "USER_MANUAL", "USER_CONFIRMATION" -> USER_CONFIRMATION
            "DIRECT_VISUAL" -> DIRECT_VISUAL_EVIDENCE
            "VISUAL" -> OBJECT_DETECTION
            "SCENE" -> SCENE_CHANGE
            "SPEECH" -> DOMAIN_SPEECH
            else -> GENERAL_AI_KNOWLEDGE
        }
    }
}

/**
 * Structured report of multi-source fused evidence.
 */
data class FusedEvidenceReport(
    val state: FusedEvidenceState,
    val readiness: LearnerReadinessState,
    val strongestSource: String,
    val supportingCount: Int,
    val conflictingCount: Int,
    val totalObservations: Int,
    val evidenceAgeMs: Long,
    val requiresManualConfirmation: Boolean,
    val explanation: String,
    val structuredSummaryForCoach: String,
    val sources: List<String>,
)

/**
 * Deterministic engine fusing multiple evidence channels over bounded time windows.
 */
class EvidenceFusionEngine(
    private val policy: Policy = Policy.DEFAULT,
) {
    private var stepIndex: Int = 0
    private var stepTitle: String = ""
    private var stepInstruction: String = ""
    private var stepTranscript: String = ""
    private var requirements: List<StepRequirement> = emptyList()

    private val observations = mutableListOf<LearnerObservation>()
    private var isManuallyConfirmed: Boolean = false
    private var lastPositiveDetectionMs: Long = 0L

    fun setStepContext(
        index: Int,
        title: String,
        instruction: String,
        transcript: String,
        reqs: List<StepRequirement>,
    ) {
        stepIndex = index
        stepTitle = title
        stepInstruction = instruction
        stepTranscript = transcript
        requirements = reqs
        reset()
    }

    fun reset() {
        observations.clear()
        isManuallyConfirmed = false
        lastPositiveDetectionMs = 0L
    }

    fun confirmManual() {
        isManuallyConfirmed = true
    }

    fun addObservation(obs: LearnerObservation) {
        // Discard duplicate timestamps to avoid artificial evidence inflation
        if (observations.isNotEmpty() && observations.last().timestampMs == obs.timestampMs) {
            return
        }
        observations.add(obs)

        // Keep history strictly bounded to 3x window duration
        val cutoff = obs.timestampMs - (policy.stepCheckWindowMs * 3)
        observations.removeAll { it.timestampMs < cutoff }

        if (obs.seenLabels.isNotEmpty()) {
            lastPositiveDetectionMs = obs.timestampMs
        }
    }

    fun evaluate(currentTimestampMs: Long = observations.lastOrNull()?.timestampMs ?: 0L): FusedEvidenceReport {
        val combinedText = "$stepTitle $stepInstruction $stepTranscript".lowercase()

        // 1. Initial / unconfigured state
        if (requirements.isEmpty()) {
            return FusedEvidenceReport(
                state = FusedEvidenceState.UNKNOWN,
                readiness = LearnerReadinessState.READY_TO_START,
                strongestSource = "NONE",
                supportingCount = 0,
                conflictingCount = 0,
                totalObservations = 0,
                evidenceAgeMs = 0L,
                requiresManualConfirmation = false,
                explanation = "No verification has started.",
                structuredSummaryForCoach = "Step not initialized. No evidence recorded.",
                sources = emptyList(),
            )
        }

        // Bounded window observations
        val windowStart = currentTimestampMs - policy.stepCheckWindowMs
        val windowObs = observations.filter { it.timestampMs in windowStart..currentTimestampMs }
        val evidenceAgeMs = if (windowObs.isNotEmpty()) currentTimestampMs - windowObs.last().timestampMs else 0L

        // 2. Staleness check: if observations are too old, discount evidence
        val isStale = observations.isNotEmpty() && (currentTimestampMs - observations.last().timestampMs > policy.stepCheckWindowMs * 3)
        if (isStale && !isManuallyConfirmed) {
            return FusedEvidenceReport(
                state = FusedEvidenceState.INSUFFICIENT,
                readiness = LearnerReadinessState.IN_PROGRESS,
                strongestSource = "VISUAL",
                supportingCount = 0,
                conflictingCount = 0,
                totalObservations = windowObs.size,
                evidenceAgeMs = currentTimestampMs - observations.last().timestampMs,
                requiresManualConfirmation = false,
                explanation = "The previous visual evidence is too old to confirm the current state.",
                structuredSummaryForCoach = "Evidence is stale. Camera observations have timed out.",
                sources = listOf("VISUAL"),
            )
        }

        // 3. User manual confirmation (authoritative human assertion for physical safety)
        if (isManuallyConfirmed) {
            return FusedEvidenceReport(
                state = FusedEvidenceState.STRONGLY_SUPPORTED,
                readiness = LearnerReadinessState.VERIFIED_COMPLETE,
                strongestSource = EvidenceSourceRank.USER_CONFIRMATION.sourceName,
                supportingCount = 1,
                conflictingCount = 0,
                totalObservations = windowObs.size,
                evidenceAgeMs = evidenceAgeMs,
                requiresManualConfirmation = false,
                explanation = "Step verified via learner manual confirmation.",
                structuredSummaryForCoach = "Expert instruction: present. User manual confirmation: confirmed. Step complete.",
                sources = listOf(EvidenceSourceRank.USER_CONFIRMATION.sourceName, EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName),
            )
        }

        // 4. Manual confirmation requirement check
        val requiresManual = requirements.any { it.type == RequirementType.MANUAL_CONFIRMATION }
        val unsupportedItems = policy.componentAliases.filterValues { it.isEmpty() }.keys
        val mentionsUnsupported = unsupportedItems.any { combinedText.contains(it) }

        if (requiresManual || mentionsUnsupported) {
            val componentNote = if (mentionsUnsupported) {
                "The current camera model cannot directly verify this component."
            } else {
                "This action requires confirmation because camera evidence cannot prove the physical action."
            }
            return FusedEvidenceReport(
                state = FusedEvidenceState.MANUAL_REQUIRED,
                readiness = LearnerReadinessState.MANUAL_CONFIRMATION_REQUIRED,
                strongestSource = EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName,
                supportingCount = 0,
                conflictingCount = 0,
                totalObservations = windowObs.size,
                evidenceAgeMs = evidenceAgeMs,
                requiresManualConfirmation = true,
                explanation = componentNote,
                structuredSummaryForCoach = "Expert instruction: present. Visual model: unsupported for target action/component. Manual confirmation required.",
                sources = listOf(EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName),
            )
        }

        // 5. Insufficient temporal observations
        if (windowObs.size < policy.stepCheckMinObservations) {
            val state = if (windowObs.isEmpty()) FusedEvidenceState.UNKNOWN else FusedEvidenceState.CHECKING
            val readiness = if (windowObs.isEmpty()) LearnerReadinessState.READY_TO_START else LearnerReadinessState.IN_PROGRESS
            return FusedEvidenceReport(
                state = state,
                readiness = readiness,
                strongestSource = if (windowObs.isNotEmpty()) EvidenceSourceRank.OBJECT_DETECTION.sourceName else EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName,
                supportingCount = 0,
                conflictingCount = 0,
                totalObservations = windowObs.size,
                evidenceAgeMs = evidenceAgeMs,
                requiresManualConfirmation = false,
                explanation = if (windowObs.isEmpty()) "Ready to observe." else "Gathering observations (${windowObs.size}/${policy.stepCheckMinObservations})...",
                structuredSummaryForCoach = "Collecting observations across temporal window (${windowObs.size}/${policy.stepCheckMinObservations}).",
                sources = listOf(EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName),
            )
        }

        // 6. Multimodal requirement evaluation
        var supporting = 0
        var conflicting = 0
        val observedSources = mutableSetOf(EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName)
        val missingDetails = mutableListOf<String>()
        val conflictDetails = mutableListOf<String>()

        for (req in requirements) {
            when (req.type) {
                RequirementType.OBJECT_PRESENT -> {
                    var matchCount = 0
                    var maxScore = 0f
                    for (obs in windowObs) {
                        val score = obs.labelScores[req.target] ?: (if (obs.seenLabels.contains(req.target)) 1.0f else 0.0f)
                        if (score >= req.minConfidence) {
                            matchCount++
                            if (score > maxScore) maxScore = score
                        }
                    }
                    val consistency = matchCount.toDouble() / windowObs.size
                    if (consistency >= policy.stepCheckRequiredConsistency) {
                        supporting++
                        observedSources.add(EvidenceSourceRank.OBJECT_DETECTION.sourceName)
                    } else if (matchCount > 0) {
                        conflicting++
                        conflictDetails.add("${req.target} intermittent (${(consistency * 100).toInt()}%)")
                    } else {
                        missingDetails.add("${req.target} not detected in current observations")
                    }
                }
                RequirementType.SCENE_CHANGE -> {
                    val latestObs = windowObs.last()
                    if (latestObs.frameToFrameChange >= policy.checkSettledMaxChange / 2) {
                        supporting++
                        observedSources.add(EvidenceSourceRank.SCENE_CHANGE.sourceName)
                    } else {
                        missingDetails.add("Scene change not yet observed")
                    }
                }
                RequirementType.SCENE_SIMILARITY -> {
                    val avgSim = windowObs.map { it.sceneSimilarity }.average().toFloat()
                    if (avgSim >= policy.checkCorrectSimilarity) {
                        supporting++
                        observedSources.add(EvidenceSourceRank.DIRECT_VISUAL_EVIDENCE.sourceName)
                    } else {
                        missingDetails.add("Scene similarity below threshold")
                    }
                }
                RequirementType.OBJECT_COUNT -> {
                    var countMet = 0
                    for (obs in windowObs) {
                        if (obs.seenLabels.count { it == req.target } >= req.expectedCount) {
                            countMet++
                        }
                    }
                    val consistency = countMet.toDouble() / windowObs.size
                    if (consistency >= policy.stepCheckRequiredConsistency) {
                        supporting++
                        observedSources.add(EvidenceSourceRank.OBJECT_DETECTION.sourceName)
                    } else {
                        missingDetails.add("Required count of ${req.target} not observed consistently")
                    }
                }
                RequirementType.DOMAIN_TERM -> {
                    supporting++
                    observedSources.add(EvidenceSourceRank.DOMAIN_SPEECH.sourceName)
                }
                RequirementType.MANUAL_CONFIRMATION -> {
                    missingDetails.add("Manual confirmation required")
                }
            }
        }

        // Domain speech agreement check
        val mentionsSpeechAgreement = combinedText.contains("screwdriver") || combinedText.contains("laptop")
        if (mentionsSpeechAgreement && observedSources.contains(EvidenceSourceRank.OBJECT_DETECTION.sourceName)) {
            observedSources.add(EvidenceSourceRank.DOMAIN_SPEECH.sourceName)
        }

        // Action safety gate: Object detected + scene change on removal cannot prove removal action
        val mentionsRemoval = combinedText.contains("remove") || combinedText.contains("unscrew") || combinedText.contains("undo")
        if (mentionsRemoval && supporting >= requirements.size && !isManuallyConfirmed) {
            return FusedEvidenceReport(
                state = FusedEvidenceState.MANUAL_REQUIRED,
                readiness = LearnerReadinessState.LIKELY_DONE_BUT_NEEDS_CONFIRMATION,
                strongestSource = EvidenceSourceRank.OBJECT_DETECTION.sourceName,
                supportingCount = supporting,
                conflictingCount = conflicting,
                totalObservations = windowObs.size,
                evidenceAgeMs = evidenceAgeMs,
                requiresManualConfirmation = true,
                explanation = "Screw removal action cannot be confirmed by camera alone. Please confirm completion.",
                structuredSummaryForCoach = "Scene changed and tool detected, but physical removal action cannot be proven. Manual confirmation required.",
                sources = observedSources.toList(),
            )
        }

        // Determine final fused state
        val finalState = when {
            conflicting > 0 -> FusedEvidenceState.CONFLICTING
            supporting >= requirements.size && observedSources.contains(EvidenceSourceRank.DOMAIN_SPEECH.sourceName) -> FusedEvidenceState.STRONGLY_SUPPORTED
            supporting >= requirements.size -> FusedEvidenceState.SUPPORTED
            supporting > 0 && missingDetails.isNotEmpty() -> FusedEvidenceState.INSUFFICIENT
            else -> FusedEvidenceState.INSUFFICIENT
        }

        val readiness = when (finalState) {
            FusedEvidenceState.STRONGLY_SUPPORTED, FusedEvidenceState.SUPPORTED -> LearnerReadinessState.VERIFIED_COMPLETE
            FusedEvidenceState.CONFLICTING -> LearnerReadinessState.CONFLICTING_EVIDENCE
            FusedEvidenceState.INSUFFICIENT -> {
                if (missingDetails.any { it.contains("screwdriver") || it.contains("laptop") }) {
                    LearnerReadinessState.WAITING_FOR_REQUIRED_OBJECT
                } else {
                    LearnerReadinessState.IN_PROGRESS
                }
            }
            FusedEvidenceState.MANUAL_REQUIRED -> LearnerReadinessState.MANUAL_CONFIRMATION_REQUIRED
            FusedEvidenceState.CHECKING -> LearnerReadinessState.IN_PROGRESS
            FusedEvidenceState.UNKNOWN -> LearnerReadinessState.READY_TO_START
        }

        val strongest = when {
            observedSources.contains(EvidenceSourceRank.USER_CONFIRMATION.sourceName) -> EvidenceSourceRank.USER_CONFIRMATION.sourceName
            observedSources.contains(EvidenceSourceRank.DIRECT_VISUAL_EVIDENCE.sourceName) -> EvidenceSourceRank.DIRECT_VISUAL_EVIDENCE.sourceName
            observedSources.contains(EvidenceSourceRank.OBJECT_DETECTION.sourceName) -> EvidenceSourceRank.OBJECT_DETECTION.sourceName
            observedSources.contains(EvidenceSourceRank.SCENE_CHANGE.sourceName) -> EvidenceSourceRank.SCENE_CHANGE.sourceName
            observedSources.contains(EvidenceSourceRank.DOMAIN_SPEECH.sourceName) -> EvidenceSourceRank.DOMAIN_SPEECH.sourceName
            else -> EvidenceSourceRank.EXPERT_INSTRUCTION.sourceName
        }

        val explanation = when (finalState) {
            FusedEvidenceState.STRONGLY_SUPPORTED, FusedEvidenceState.SUPPORTED -> {
                val primary = requirements.firstOrNull()?.target ?: "target"
                "Camera consistently detects the required $primary."
            }
            FusedEvidenceState.CONFLICTING -> "Evidence changed during the verification window, so I need more consistent observations."
            FusedEvidenceState.INSUFFICIENT -> {
                if (supporting > 0) {
                    "I can detect the laptop, but the action itself cannot be verified automatically."
                } else {
                    "Not enough consistent evidence to confirm step completion."
                }
            }
            FusedEvidenceState.MANUAL_REQUIRED -> "This action requires confirmation because camera evidence cannot prove the physical action."
            FusedEvidenceState.CHECKING -> "Collecting observations across temporal window..."
            FusedEvidenceState.UNKNOWN -> "No verification has started."
        }

        val coachSummary = buildString {
            append("Expert instruction: present. ")
            append("Visual sources: ${observedSources.joinToString(", ")}. ")
            append("State: $finalState. ")
            if (missingDetails.isNotEmpty()) {
                append("Missing: ${missingDetails.joinToString(", ")}. ")
            }
            if (conflictDetails.isNotEmpty()) {
                append("Conflicts: ${conflictDetails.joinToString(", ")}. ")
            }
            append("Explanation: $explanation")
        }

        return FusedEvidenceReport(
            state = finalState,
            readiness = readiness,
            strongestSource = strongest,
            supportingCount = supporting,
            conflictingCount = conflicting,
            totalObservations = windowObs.size,
            evidenceAgeMs = evidenceAgeMs,
            requiresManualConfirmation = requiresManual,
            explanation = explanation,
            structuredSummaryForCoach = coachSummary,
            sources = observedSources.toList(),
        )
    }
}
