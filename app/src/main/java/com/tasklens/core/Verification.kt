package com.tasklens.core

/**
 * How much the bench in front of the learner looks like the step they are on.
 *
 * Three values, and none of them is a verdict on the person. There is
 * deliberately no BLOCKED: nothing in this app disables Next, hides Continue or
 * refuses to advance because a camera disagreed with a photograph. The expert
 * who recorded this guide finished the job, and a learner who is holding the
 * board at a different angle is not wrong.
 */
enum class StepCheck {
    /** The scene matches the step's photograph and the same things are in it. */
    CORRECT,

    /** Some of the evidence is there. Worth saying, not worth insisting on. */
    LIKELY_CORRECT,

    /**
     * Not enough to say either way -- the camera is off, the phone is still
     * moving, or nothing recognisable is in frame.
     *
     * The ordinary answer, and the only one that ever justifies asking the
     * coach. Everything above this was settled with arithmetic.
     */
    UNCERTAIN,
}

/**
 * What the check is given. All of it already exists elsewhere in the app.
 *
 * @param sceneSimilarity [SceneHash.similarity] between the live frame and the
 *   step's saved photograph, 0..1. Zero when nothing is being watched.
 * @param frameToFrameChange bits of a 64-bit dHash that changed since the last
 *   frame. A phone still swinging toward the bench changes in dozens of them.
 * @param expected detector labels from the step's photograph.
 * @param seen detector labels from the live camera right now.
 */
data class CheckInputs(
    val sceneSimilarity: Float,
    val frameToFrameChange: Int,
    val expected: List<String>,
    val seen: List<String>,
)

/**
 * How much this one frame agrees with the step, 0..1, or null when the frame
 * has nothing to say at all.
 *
 * A number rather than a verdict, and that is the whole change. A frame at 74%
 * of the expected boxes and a frame at 76% are the same bench photographed a
 * moment apart; a threshold applied to each of them separately turns that into
 * "can't tell" followed by "that looks right", which is the app looking broken
 * twice in a row. The verdict is made later, in [StepConfidence], out of many
 * of these.
 *
 * Nothing here reads a label as a component. The loaded detector knows "laptop"
 * and "keyboard" and has no idea what a RAM module is -- so this compares its
 * labels to its own earlier labels and draws no conclusion about parts.
 */
fun frameEvidence(i: CheckInputs, p: Policy = Policy.DEFAULT): Float? {
    // What the detector found, against what it found in the photograph,
    // counted. Where it has an opinion, it is the opinion: everyone's desk,
    // lighting and camera angle differ, and the objects are the part that
    // travels between benches.
    labelOverlap(i.expected, i.seen)?.let { return it.toFloat() }

    // No detector, or a photograph nothing was recognised in. Then, and only
    // then, structure and colour against the step's photograph decides -- a
    // bench that merely *looks* like the photograph while the parts are wrong
    // must never be rescued by this, and above it never is.
    if (i.sceneSimilarity <= 0f) return null
    return ramp(i.sceneSimilarity, p.checkLikelySimilarity, p.checkCorrectSimilarity)
}

/**
 * How much this frame's evidence is worth, 1 for a still phone down to 0 for
 * one still swinging.
 *
 * The old code threw the frame away above [Policy.checkSettledMaxChange], and
 * that is half of why the check could never make its mind up: a hand-held
 * phone over a workbench is never perfectly still, so most frames counted for
 * nothing and the few that got through decided alone. Faded instead -- a
 * slightly shaky frame is weak evidence, not no evidence, and only a frame
 * changing twice over the threshold is worth nothing at all.
 */
fun settleWeight(frameToFrameChange: Int, p: Policy = Policy.DEFAULT): Float {
    val steady = p.checkSettledMaxChange.toFloat()
    return ramp(frameToFrameChange.toFloat(), 2f * steady, steady)
}

/**
 * The running answer to "does this look like the step", over frames rather
 * than off one of them.
 *
 * Two failures it exists to remove, both reported from the bench:
 *
 *  - **It could never get confident enough.** Every frame was judged alone and
 *    any camera movement discarded it outright, so the evidence never added up
 *    and the page never turned.
 *  - **Then it would suddenly be certain.** One frame landing over a hard
 *    threshold was a verdict, so a hand passing across the bench read as the
 *    work being finished.
 *
 * An exponential average fixes both: confidence has to be *earned* over about
 * half a second of agreeing frames before it can reach CORRECT, and it survives
 * the odd bad frame instead of collapsing. The bands then have separate enter
 * and exit levels ([Schmitt]), so a value sitting on the line cannot flicker --
 * the same trick, and for the same reason, as [ModeEngine].
 *
 * Stateful, and therefore [reset] on every step change.
 */
class StepConfidence(private val p: Policy = Policy.DEFAULT) {

    /**
     * 0..1, and the number the screen should show.
     *
     * The raw scene similarity was on screen before this existed, next to a
     * verdict computed from something else. "84%" over "can't tell yet" is two
     * measurements pretending to be one, and the learner believes the one they
     * can read.
     */
    var value: Float = 0f
        private set

    var check: StepCheck = StepCheck.UNCERTAIN
        private set

    private val correct = Schmitt(
        p.checkLabelOverlap,
        p.checkLabelOverlap - p.confidenceHysteresis,
    )
    private val likely = Schmitt(
        p.confidenceLikely,
        p.confidenceLikely - p.confidenceHysteresis,
    )

    /**
     * May the guide turn its own page right now?
     *
     * One value and not a condition spelled out at the call site, because the
     * screen and the page turn have to agree: a bar that says "that looks
     * right" over a guide that then sits there is the app contradicting
     * itself, and that is what a learner reads as broken.
     *
     * CORRECT is the whole bar now. It used to be CORRECT *plus* a second
     * similarity threshold on a different measurement, which is one of the two
     * ways the page turn could be unreachable on a bench that plainly matched.
     * [Policy.advanceOnMatchSimilarity] keeps its job as the on/off switch and
     * gives up its second one.
     */
    val mayAdvance: Boolean
        get() = p.advanceOnMatchSimilarity > 0f && check == StepCheck.CORRECT

    /** Feed one analysed frame. Returns the band to show right now. */
    fun update(i: CheckInputs): StepCheck {
        val weight = settleWeight(i.frameToFrameChange, p)
        val evidence = frameEvidence(i, p)
        value += if (evidence != null && weight > 0f) {
            // Weighted by how still the phone was, so a shaky frame nudges
            // where a steady one moves.
            p.confidenceRiseCoef * weight * (evidence - value)
        } else {
            // Camera off, phone mid-swing, nothing in shot. That is an absence
            // and not a disagreement, so confidence fades rather than being
            // scored zero -- and it fades slower than it builds, or a hand
            // reaching across the bench would undo ten good frames.
            p.confidenceFallCoef * (0f - value)
        }
        // An average only ever approaches its target, and a band set exactly at
        // that target is then unreachable: three of four expected boxes is
        // 0.75 on every frame forever, and 0.75 is the bar. Close enough is
        // arrival -- this is float convergence, not a tunable.
        if (evidence != null && weight > 0f && kotlin.math.abs(evidence - value) < ARRIVED) {
            value = evidence
        }
        value = value.coerceIn(0f, 1f)

        // Both, every frame, whichever wins: a Schmitt that is only asked
        // sometimes keeps a stale state and reports it later as news.
        val isCorrect = correct.update(value.toDouble())
        val isLikely = likely.update(value.toDouble())
        check = when {
            isCorrect -> StepCheck.CORRECT
            isLikely -> StepCheck.LIKELY_CORRECT
            else -> StepCheck.UNCERTAIN
        }
        return check
    }

    fun reset() {
        value = 0f
        check = StepCheck.UNCERTAIN
        correct.reset()
        likely.reset()
    }

    private companion object {
        const val ARRIVED = 0.005f
    }
}

/**
 * How much of what the photograph showed is in front of the camera now, 0..1,
 * or null when either side has nothing to say.
 *
 * **Counted, not just named.** Two philips heads in the photograph and one on
 * the bench is half the evidence, not all of it -- and "half" is the whole
 * difference between a panel with its screws out and a panel with one screw
 * out. A set comparison called those identical, which is how a step could be
 * satisfied by a laptop merely being on the desk.
 *
 * Null and not zero. An empty list means no detector, or nothing recognised,
 * and reporting that as "none of the expected things are here" would turn a
 * missing model into a warning about the learner's work.
 */
internal fun labelOverlap(expected: List<String>, seen: List<String>): Double? {
    val want = counts(expected)
    val have = counts(seen)
    if (want.isEmpty() || have.isEmpty()) return null
    val matched = want.entries.sumOf { (label, n) -> minOf(n, have[label] ?: 0) }
    return matched.toDouble() / want.values.sum()
}

/**
 * What is still missing from the bench, one entry per box short, or empty.
 *
 * For telling the learner what to do rather than handing them a percentage.
 * Same arithmetic as [labelOverlap]; a shortfall of two screws names the screw
 * twice, because "still looking for 2 philips screws" is the useful sentence.
 */
fun labelShortfall(expected: List<String>, seen: List<String>): List<String> {
    val want = counts(expected)
    val have = counts(seen)
    if (want.isEmpty()) return emptyList()
    return want.flatMap { (label, n) -> List((n - (have[label] ?: 0)).coerceAtLeast(0)) { label } }
}

private fun counts(labels: List<String>): Map<String, Int> =
    labels.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()

/** 0 at [lo], 1 at [hi], a straight line between. [hi] below [lo] runs downhill. */
private fun ramp(v: Float, lo: Float, hi: Float): Float =
    if (lo == hi) (if (v >= hi) 1f else 0f) else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

// =========================================================================
// LEARNER-SIDE STEP VERIFICATION CONTRACT & INTELLIGENCE
// =========================================================================

/**
 * Deterministic learner-side step verification state.
 *
 * "EVIDENCE, NOT GUESSING."
 *
 * UNKNOWN: No verification attempt yet.
 * CHECKING: Evidence is currently being collected over temporal window.
 * PASS: Available evidence satisfies the step's configured requirements.
 * INSUFFICIENT_EVIDENCE: The system cannot determine completion (NOT failure).
 * CONFLICT: Available signals disagree.
 * MANUAL_CONFIRMATION: Action/component cannot be safely verified by on-device models.
 */
enum class StepVerificationState {
    UNKNOWN,
    CHECKING,
    PASS,
    INSUFFICIENT_EVIDENCE,
    CONFLICT,
    MANUAL_CONFIRMATION;

    val isTerminalSuccess: Boolean get() = this == PASS
}

enum class RequirementType {
    OBJECT_PRESENT,
    OBJECT_COUNT,
    SCENE_SIMILARITY,
    SCENE_CHANGE,
    DOMAIN_TERM,
    MANUAL_CONFIRMATION,
}

enum class EvidenceType {
    OBJECT_DETECTION,
    SCENE_SIMILARITY,
    SCENE_CHANGE,
    OBJECT_COUNT,
    DOMAIN_WORD,
    USER_CONFIRMATION,
    EXPERT_INSTRUCTION,
}

data class StepEvidence(
    val source: String, // "EXPERT", "VISUAL", "USER_MANUAL", "SYSTEM"
    val type: EvidenceType,
    val confidence: Float,
    val explanation: String,
    val timestampMs: Long,
    val stepIndex: Int = -1,
)

data class StepRequirement(
    val type: RequirementType,
    val target: String = "",
    val expectedCount: Int = 1,
    val minConfidence: Float = 0.3f,
    val description: String = "",
)

data class StepVerificationReport(
    val state: StepVerificationState,
    val required: List<StepRequirement>,
    val satisfied: List<StepEvidence>,
    val missing: List<String>,
    val conflicting: List<String>,
    val observationCount: Int,
    val durationMs: Long,
    val explanation: String,
)

enum class StepProgressStatus {
    PENDING,
    IN_PROGRESS,
    VERIFIED_COMPLETE,
    MANUAL_COMPLETE,
    SKIPPED_UNVERIFIED,
}

data class LearnerStepProgress(
    val stepIndex: Int,
    val status: StepProgressStatus,
    val verificationState: StepVerificationState = StepVerificationState.UNKNOWN,
    val lastReport: StepVerificationReport? = null,
)

data class LearnerObservation(
    val timestampMs: Long,
    val seenLabels: List<String> = emptyList(),
    val labelScores: Map<String, Float> = emptyMap(),
    val sceneSimilarity: Float = 0f,
    val frameToFrameChange: Int = 0,
    val isUserConfirmed: Boolean = false,
)

/**
 * Derives realistic, honest requirements for a step without assuming models can see
 * what they were never trained to detect.
 */
object StepRequirementDeriver {
    fun derive(
        stepIndex: Int,
        title: String,
        instruction: String,
        transcript: String,
        caption: String,
        objects: List<String>,
        policy: Policy = Policy.DEFAULT,
    ): List<StepRequirement> {
        val text = "$title $instruction $transcript $caption".lowercase()
        val requirements = mutableListOf<StepRequirement>()

        // 1. Check for tools/objects known to fine-tuned or COCO models
        val knownDetectables = policy.componentAliases.filterValues { it.isNotEmpty() }.keys
        val foundTools = knownDetectables.filter { text.contains(it) }

        // 2. Check for unsupported components (e.g. battery connector, RAM, SSD, power cables)
        val unsupportedItems = policy.componentAliases.filterValues { it.isEmpty() }.keys
        val mentionsUnsupported = unsupportedItems.any { text.contains(it) } ||
                text.contains("power down") ||
                text.contains("disconnect") ||
                text.contains("unplug") ||
                text.contains("battery connector")

        // 3. Action classifications
        val mentionsRemoval = text.contains("remove") || text.contains("unscrew") || text.contains("undo")
        val mentionsOpen = text.contains("open") || text.contains("take off") || text.contains("lift")

        if (mentionsUnsupported && foundTools.isEmpty() && !mentionsOpen) {
            requirements.add(
                StepRequirement(
                    type = RequirementType.MANUAL_CONFIRMATION,
                    target = "manual_safety",
                    description = "Step involves safety action or components not supported by on-device visual detector",
                )
            )
            return requirements
        }

        if (foundTools.contains("screwdriver") || text.contains("screwdriver") || text.contains("pechkas")) {
            val minScore = policy.detectLabelMinScore["screwdriver"] ?: policy.detectMinScore
            requirements.add(
                StepRequirement(
                    type = RequirementType.OBJECT_PRESENT,
                    target = "screwdriver",
                    expectedCount = 1,
                    minConfidence = minScore,
                    description = "Required screwdriver present in workspace",
                )
            )
        }

        if (mentionsOpen) {
            requirements.add(
                StepRequirement(
                    type = RequirementType.OBJECT_PRESENT,
                    target = "laptop",
                    expectedCount = 1,
                    minConfidence = policy.detectMinScoreCoco,
                    description = "Laptop present on workbench",
                )
            )
            requirements.add(
                StepRequirement(
                    type = RequirementType.SCENE_CHANGE,
                    target = "panel_state",
                    description = "Structural scene change from opening panel",
                )
            )
        } else if (mentionsRemoval) {
            val screwLabels = policy.componentAliases["screw"] ?: emptyList()
            if (screwLabels.isNotEmpty() && objects.any { it.contains("screw") }) {
                requirements.add(
                    StepRequirement(
                        type = RequirementType.SCENE_CHANGE,
                        target = "screw_state",
                        description = "Scene state change after screw removal",
                    )
                )
            } else {
                // If fine-grained screw counting is unsupported on this hardware, require manual check
                requirements.add(
                    StepRequirement(
                        type = RequirementType.MANUAL_CONFIRMATION,
                        target = "screw_removal",
                        description = "Physical screw removal confirmation",
                    )
                )
            }
        }

        if (requirements.isEmpty()) {
            // Default to requiring visual presence if objects exist, else manual confirmation
            if (objects.isNotEmpty()) {
                val primary = objects.first()
                val minScore = policy.detectLabelMinScore[primary] ?: policy.detectMinScoreCoco
                requirements.add(
                    StepRequirement(
                        type = RequirementType.OBJECT_PRESENT,
                        target = primary,
                        expectedCount = 1,
                        minConfidence = minScore,
                        description = "$primary detected in scene",
                    )
                )
            } else {
                requirements.add(
                    StepRequirement(
                        type = RequirementType.MANUAL_CONFIRMATION,
                        target = "step_completion",
                        description = "Manual completion confirmation required",
                    )
                )
            }
        }

        return requirements
    }
}

/**
 * Deterministic evidence aggregator with sliding window temporal stability.
 *
 * Invariant: One-frame detection does not complete a step.
 * Invariant: Absence is "Not detected in this observation", not "Object definitely absent".
 * Invariant: Scene change alone does not equal semantic completion.
 */
class LearnerStepVerifier(
    private val policy: Policy = Policy.DEFAULT,
    private var requirements: List<StepRequirement> = emptyList(),
) {
    private val observations = mutableListOf<LearnerObservation>()
    private var stepIndex: Int = 0
    private var isManuallyConfirmed: Boolean = false

    fun setStep(index: Int, reqs: List<StepRequirement>) {
        stepIndex = index
        requirements = reqs
        reset()
    }

    fun reset() {
        observations.clear()
        isManuallyConfirmed = false
    }

    fun confirmManually() {
        isManuallyConfirmed = true
    }

    fun addObservation(obs: LearnerObservation) {
        // Prevent duplicate timestamps
        if (observations.isNotEmpty() && observations.last().timestampMs == obs.timestampMs) {
            return
        }
        observations.add(obs)

        // Prune older than 3x sliding window to bound memory
        val cutoff = obs.timestampMs - (policy.stepCheckWindowMs * 3)
        observations.removeAll { it.timestampMs < cutoff }
    }

    fun evaluate(currentTimestampMs: Long = observations.lastOrNull()?.timestampMs ?: 0L): StepVerificationReport {
        if (requirements.isEmpty()) {
            return StepVerificationReport(
                state = StepVerificationState.UNKNOWN,
                required = emptyList(),
                satisfied = emptyList(),
                missing = emptyList(),
                conflicting = emptyList(),
                observationCount = 0,
                durationMs = 0L,
                explanation = "No verification has started.",
            )
        }

        // Window filtering
        val windowStart = currentTimestampMs - policy.stepCheckWindowMs
        val windowObs = observations.filter { it.timestampMs in windowStart..currentTimestampMs }
        val durationMs = if (windowObs.size >= 2) windowObs.last().timestampMs - windowObs.first().timestampMs else 0L

        val satisfied = mutableListOf<StepEvidence>()
        val missing = mutableListOf<String>()
        val conflicting = mutableListOf<String>()

        // 1. Check if explicit manual confirmation is configured
        val requiresManual = requirements.any { it.type == RequirementType.MANUAL_CONFIRMATION }

        if (isManuallyConfirmed) {
            satisfied.add(
                StepEvidence(
                    source = "USER_MANUAL",
                    type = EvidenceType.USER_CONFIRMATION,
                    confidence = 1.0f,
                    explanation = "Learner confirmed step completion manually.",
                    timestampMs = currentTimestampMs,
                    stepIndex = stepIndex,
                )
            )
            return StepVerificationReport(
                state = StepVerificationState.PASS,
                required = requirements,
                satisfied = satisfied,
                missing = emptyList(),
                conflicting = emptyList(),
                observationCount = windowObs.size,
                durationMs = durationMs,
                explanation = "Step verified via learner manual confirmation.",
            )
        }

        if (requiresManual) {
            return StepVerificationReport(
                state = StepVerificationState.MANUAL_CONFIRMATION,
                required = requirements,
                satisfied = emptyList(),
                missing = listOf("Manual learner confirmation required"),
                conflicting = emptyList(),
                observationCount = windowObs.size,
                durationMs = durationMs,
                explanation = "This action cannot be safely verified automatically.",
            )
        }

        // Not enough temporal observations yet
        if (windowObs.size < policy.stepCheckMinObservations) {
            return StepVerificationReport(
                state = if (windowObs.isEmpty()) StepVerificationState.UNKNOWN else StepVerificationState.CHECKING,
                required = requirements,
                satisfied = emptyList(),
                missing = listOf("Gathering observations (${windowObs.size}/${policy.stepCheckMinObservations})"),
                conflicting = emptyList(),
                observationCount = windowObs.size,
                durationMs = durationMs,
                explanation = "Collecting observations across temporal window...",
            )
        }

        // Evaluate each requirement over window observations
        for (req in requirements) {
            when (req.type) {
                RequirementType.OBJECT_PRESENT -> {
                    var matchingCount = 0
                    var maxScore = 0f
                    for (obs in windowObs) {
                        val score = obs.labelScores[req.target] ?: (if (obs.seenLabels.contains(req.target)) 1.0f else 0.0f)
                        if (score >= req.minConfidence) {
                            matchingCount++
                            if (score > maxScore) maxScore = score
                        }
                    }
                    val consistency = matchingCount.toDouble() / windowObs.size
                    if (consistency >= policy.stepCheckRequiredConsistency) {
                        satisfied.add(
                            StepEvidence(
                                source = "VISUAL",
                                type = EvidenceType.OBJECT_DETECTION,
                                confidence = maxScore,
                                explanation = "Required ${req.target} detected consistently (${(consistency * 100).toInt()}% of frames).",
                                timestampMs = currentTimestampMs,
                                stepIndex = stepIndex,
                            )
                        )
                    } else if (matchingCount > 0) {
                        conflicting.add("${req.target} detected intermittently (consistency ${(consistency * 100).toInt()}% < ${(policy.stepCheckRequiredConsistency * 100).toInt()}%)")
                    } else {
                        missing.add("${req.target} not detected in current observations")
                    }
                }
                RequirementType.SCENE_CHANGE -> {
                    val initialObs = windowObs.first()
                    val latestObs = windowObs.last()
                    val changeBits = latestObs.frameToFrameChange
                    if (changeBits >= policy.checkSettledMaxChange / 2) {
                        satisfied.add(
                            StepEvidence(
                                source = "VISUAL",
                                type = EvidenceType.SCENE_CHANGE,
                                confidence = 0.8f,
                                explanation = "Scene changed sufficiently.",
                                timestampMs = currentTimestampMs,
                                stepIndex = stepIndex,
                            )
                        )
                    } else {
                        missing.add("Scene change not yet observed")
                    }
                }
                RequirementType.SCENE_SIMILARITY -> {
                    val avgSim = windowObs.map { it.sceneSimilarity }.average().toFloat()
                    if (avgSim >= policy.checkCorrectSimilarity) {
                        satisfied.add(
                            StepEvidence(
                                source = "VISUAL",
                                type = EvidenceType.SCENE_SIMILARITY,
                                confidence = avgSim,
                                explanation = "Workbench scene matches reference photograph.",
                                timestampMs = currentTimestampMs,
                                stepIndex = stepIndex,
                            )
                        )
                    } else {
                        missing.add("Scene similarity (${(avgSim * 100).toInt()}%) below required (${(policy.checkCorrectSimilarity * 100).toInt()}%)")
                    }
                }
                RequirementType.OBJECT_COUNT -> {
                    var countMet = 0
                    for (obs in windowObs) {
                        val count = obs.seenLabels.count { it == req.target }
                        if (count >= req.expectedCount) countMet++
                    }
                    val consistency = countMet.toDouble() / windowObs.size
                    if (consistency >= policy.stepCheckRequiredConsistency) {
                        satisfied.add(
                            StepEvidence(
                                source = "VISUAL",
                                type = EvidenceType.OBJECT_COUNT,
                                confidence = 0.9f,
                                explanation = "Count of ${req.target} (${req.expectedCount}) confirmed consistently.",
                                timestampMs = currentTimestampMs,
                                stepIndex = stepIndex,
                            )
                        )
                    } else {
                        missing.add("Required count of ${req.target} not observed consistently")
                    }
                }
                RequirementType.DOMAIN_TERM -> {
                    satisfied.add(
                        StepEvidence(
                            source = "EXPERT",
                            type = EvidenceType.DOMAIN_WORD,
                            confidence = 0.9f,
                            explanation = "Domain term '${req.target}' present in expert instructions.",
                            timestampMs = currentTimestampMs,
                            stepIndex = stepIndex,
                        )
                    )
                }
                RequirementType.MANUAL_CONFIRMATION -> {
                    missing.add("Manual confirmation required")
                }
            }
        }

        val state = when {
            conflicting.isNotEmpty() -> StepVerificationState.CONFLICT
            missing.isEmpty() && satisfied.size >= requirements.size -> StepVerificationState.PASS
            satisfied.isNotEmpty() && missing.isNotEmpty() -> StepVerificationState.INSUFFICIENT_EVIDENCE
            else -> StepVerificationState.INSUFFICIENT_EVIDENCE
        }

        val explanation = when (state) {
            StepVerificationState.PASS -> satisfied.firstOrNull()?.explanation ?: "Step appears complete."
            StepVerificationState.CONFLICT -> "Camera evidence changed while the required object was not detected consistently."
            StepVerificationState.INSUFFICIENT_EVIDENCE -> {
                if (satisfied.isNotEmpty()) {
                    "I can see ${satisfied.first().explanation.substringBefore(".")}, but ${missing.firstOrNull() ?: "action cannot be confirmed"}."
                } else {
                    "Not enough evidence to confirm step completion."
                }
            }
            StepVerificationState.MANUAL_CONFIRMATION -> "This action cannot be safely verified automatically."
            StepVerificationState.CHECKING -> "Collecting observations..."
            StepVerificationState.UNKNOWN -> "No verification has started."
        }

        return StepVerificationReport(
            state = state,
            required = requirements,
            satisfied = satisfied,
            missing = missing,
            conflicting = conflicting,
            observationCount = windowObs.size,
            durationMs = durationMs,
            explanation = explanation,
        )
    }
}

/**
 * Tracks sequential progress of a learner through a guide without silently skipping.
 */
class LearnerProgressTracker(val totalSteps: Int) {
    private val steps = Array(totalSteps.coerceAtLeast(1)) { idx ->
        LearnerStepProgress(
            stepIndex = idx,
            status = if (idx == 0) StepProgressStatus.IN_PROGRESS else StepProgressStatus.PENDING,
        )
    }

    var currentStepIndex: Int = 0
        private set

    fun getProgress(stepIndex: Int): LearnerStepProgress? = steps.getOrNull(stepIndex)

    fun getAllProgress(): List<LearnerStepProgress> = steps.toList()

    fun updateVerification(stepIndex: Int, report: StepVerificationReport) {
        if (stepIndex !in steps.indices) return
        val current = steps[stepIndex]
        val newStatus = if (report.state == StepVerificationState.PASS) {
            StepProgressStatus.VERIFIED_COMPLETE
        } else {
            if (current.status == StepProgressStatus.VERIFIED_COMPLETE || current.status == StepProgressStatus.MANUAL_COMPLETE) {
                current.status
            } else {
                StepProgressStatus.IN_PROGRESS
            }
        }
        steps[stepIndex] = current.copy(
            verificationState = report.state,
            status = newStatus,
            lastReport = report,
        )
    }

    fun confirmManual(stepIndex: Int) {
        if (stepIndex !in steps.indices) return
        steps[stepIndex] = steps[stepIndex].copy(
            status = StepProgressStatus.MANUAL_COMPLETE,
            verificationState = StepVerificationState.PASS,
        )
    }

    fun skipCurrent(stepIndex: Int) {
        if (stepIndex !in steps.indices) return
        if (steps[stepIndex].status != StepProgressStatus.VERIFIED_COMPLETE &&
            steps[stepIndex].status != StepProgressStatus.MANUAL_COMPLETE
        ) {
            steps[stepIndex] = steps[stepIndex].copy(
                status = StepProgressStatus.SKIPPED_UNVERIFIED,
            )
        }
    }

    fun advanceTo(nextIndex: Int) {
        if (nextIndex in steps.indices) {
            skipCurrent(currentStepIndex)
            currentStepIndex = nextIndex
            if (steps[currentStepIndex].status == StepProgressStatus.PENDING) {
                steps[currentStepIndex] = steps[currentStepIndex].copy(status = StepProgressStatus.IN_PROGRESS)
            }
        }
    }

    fun reset() {
        currentStepIndex = 0
        for (i in steps.indices) {
            steps[i] = LearnerStepProgress(
                stepIndex = i,
                status = if (i == 0) StepProgressStatus.IN_PROGRESS else StepProgressStatus.PENDING,
                verificationState = StepVerificationState.UNKNOWN,
                lastReport = null,
            )
        }
    }
}
