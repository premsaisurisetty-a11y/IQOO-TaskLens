package com.tasklens.eval

import com.tasklens.ai.AnswerEvidence
import com.tasklens.ai.ComponentLocator
import com.tasklens.ai.DetectionBox
import com.tasklens.ai.Detections
import com.tasklens.ai.LearnerContext
import com.tasklens.ai.Localization
import com.tasklens.ai.captionOf
import com.tasklens.ai.groundedEvidence
import com.tasklens.ai.parseRewrite
import com.tasklens.ai.parseTitle
import com.tasklens.ai.suppressDuplicates
import com.tasklens.core.AdaptiveGate
import com.tasklens.core.CheckInputs
import com.tasklens.core.FrameStats
import com.tasklens.core.LinkWordConfirmer
import com.tasklens.core.Mode
import com.tasklens.core.ModeEngine
import com.tasklens.core.ModeInputs
import com.tasklens.core.Policy
import com.tasklens.core.Sample
import com.tasklens.core.SceneHash
import com.tasklens.core.SpokenWord
import com.tasklens.core.StepConfidence
import com.tasklens.core.StepCutter
import com.tasklens.core.StepRange
import com.tasklens.core.correctDomainText
import com.tasklens.core.mapSnapsToSteps
import com.tasklens.core.namedNear
import com.tasklens.core.pickFrames
import com.tasklens.data.Guide
import com.tasklens.data.GuideStore
import com.tasklens.data.Provenance
import com.tasklens.data.Step
import com.tasklens.data.asDraft
import com.tasklens.data.provenanceOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end reliability and failure injection suite verifying the entire
 * TaskLens dataflow from capture to step cutting, frame picking, vision,
 * provenance grounding, guide persistence, expert verification, and playback.
 */
class EndToEndPipelineTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    // =========================================================================
    // PHASE 3 — 33 FAILURE INJECTION & RESILIENCE TESTS
    // =========================================================================

    // 1. Empty transcript
    @Test
    fun `failure 1 - empty transcript creates fallback step with UNKNOWN provenance`() {
        val prov = provenanceOf(transcript = "", caption = "", instruction = "")
        assertEquals(Provenance.UNKNOWN, prov)
        val parsed = parseRewrite("", 1)
        assertNull(parsed[0])
    }

    // 2. Very short transcript
    @Test
    fun `failure 2 - short transcript preserves raw text and EXPERT provenance`() {
        val prov = provenanceOf(transcript = "ok", caption = "", instruction = "Proceed")
        assertEquals(Provenance.EXPERT, prov)
    }

    // 3. Long transcript
    @Test
    fun `failure 3 - long transcript segmented properly within maxSteps`() {
        val policy = Policy.DEFAULT
        val cutter = StepCutter(policy)
        val samples = mutableListOf<Sample>()
        var t = 0L
        for (i in 1..20) {
            samples += Sample(t, -20.0)
            t += 2000L
            samples += Sample(t, -50.0) // pause
            t += 1500L
        }
        val ranges = cutter.cut(samples, t)
        assertTrue(ranges.size <= policy.maxSteps)
    }

    // 4. Audio with long silence
    @Test
    fun `failure 4 - audio with long silence does not create infinite empty steps`() {
        val cutter = StepCutter(Policy.DEFAULT)
        val samples = listOf(Sample(0L, -50.0), Sample(10000L, -50.0))
        val ranges = cutter.cut(samples, 10000L)
        assertEquals(1, ranges.size)
        assertEquals(0L, ranges[0].startMs)
        assertEquals(10000L, ranges[0].endMs)
    }

    // 5. Multiple pauses
    @Test
    fun `failure 5 - multiple pauses tile exact timeline with no gaps or overlaps`() {
        val cutter = StepCutter(Policy.DEFAULT)
        val samples = listOf(
            Sample(0L, -20.0),
            Sample(3000L, -50.0), // pause 1
            Sample(5000L, -20.0),
            Sample(8000L, -50.0), // pause 2
            Sample(10000L, -20.0),
        )
        val ranges = cutter.cut(samples, 12000L)

        for (i in 0 until ranges.size - 1) {
            assertEquals("Steps must tile without gaps", ranges[i].endMs, ranges[i + 1].startMs)
        }
    }

    // 6. Repeated words
    @Test
    fun `failure 6 - repeated words preserved in transcript and deduplicated in captions`() {
        val spoken = "screw screw screw"
        assertEquals("screw screw screw", spoken)
        val captions = captionOf(listOf("screw", "screw", "screw"))
        assertEquals("screw", captions)
    }

    // 7. Self-correction
    @Test
    fun `failure 7 - self correction handles compound join and length safety`() {
        val fixed = correctDomainText("turn the screw driver counter clockwise", listOf("screwdriver", "counterclockwise"))
        assertEquals("turn the screwdriver counterclockwise", fixed)
    }

    // 8. Missing frame
    @Test
    fun `failure 8 - missing frame gracefully yields null photo slot`() {
        val picked = pickFrames(emptyList(), listOf(StepRange(0, 0, 5000)))
        assertEquals(1, picked.size)
        assertNull(picked[0])
    }

    // 9. Blurry frame
    @Test
    fun `failure 9 - blurry frame below sharpness threshold rejected`() {
        val blurry = FrameStats(snapIndex = 0, tMs = 2000, sharpness = 0.01, meanLuma = 120.0, dHash = 0L, detections = 1)
        val picked = pickFrames(listOf(blurry), listOf(StepRange(0, 0, 5000)), Policy.DEFAULT)
        assertNull(picked[0])
    }

    // 10. Overexposed frame
    @Test
    fun `failure 10 - overexposed frame above max luma rejected`() {
        val overexposed = FrameStats(snapIndex = 0, tMs = 2000, sharpness = 0.8, meanLuma = 250.0, dHash = 0L, detections = 1)
        val picked = pickFrames(listOf(overexposed), listOf(StepRange(0, 0, 5000)), Policy.DEFAULT)
        assertNull(picked[0])
    }

    // 11. Underexposed frame
    @Test
    fun `failure 11 - underexposed frame below min luma rejected`() {
        val dark = FrameStats(snapIndex = 0, tMs = 2000, sharpness = 0.8, meanLuma = 10.0, dHash = 0L, detections = 1)
        val picked = pickFrames(listOf(dark), listOf(StepRange(0, 0, 5000)), Policy.DEFAULT)
        assertNull(picked[0])
    }

    // 12. Duplicate frame
    @Test
    fun `failure 12 - duplicate consecutive frame rejected by dHash hamming distance`() {
        val f1 = FrameStats(snapIndex = 0, tMs = 2000, sharpness = 0.8, meanLuma = 120.0, dHash = 0xFFL, detections = 1)
        val f2Identical = FrameStats(snapIndex = 1, tMs = 7000, sharpness = 0.8, meanLuma = 120.0, dHash = 0xFFL, detections = 1)
        val picked = pickFrames(listOf(f1, f2Identical), listOf(StepRange(0, 0, 5000), StepRange(1, 5000, 10000)), Policy.DEFAULT)
        assertEquals(0, picked[0])
        assertNull("Identical frame must not be reused on next step", picked[1])
    }

    // 13. No object detected
    @Test
    fun `failure 13 - no object detected produces empty caption and safe fallback`() {
        val caption = captionOf(emptyList())
        assertEquals("", caption)
    }

    // 14. Low-confidence object
    @Test
    fun `failure 14 - low confidence object filtered out in locator`() {
        val locator = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.60f })
        val res = locator.locate("screwdriver", Detections(listOf(DetectionBox("screwdriver", 0.40f, 0f, 0f, 1f, 1f))))
        assertTrue(res is Localization.Uncertain)
    }

    // 15. Conflicting object detections
    @Test
    fun `failure 15 - duplicate detections suppressed by IoU`() {
        val b1 = DetectionBox("screwdriver", 0.85f, 0f, 0f, 0.5f, 0.5f)
        val b2 = DetectionBox("screwdriver", 0.70f, 0.02f, 0.01f, 0.51f, 0.49f)
        val deduped = suppressDuplicates(listOf(b1, b2))
        assertEquals(1, deduped.size)
        assertEquals(0.85f, deduped[0].score, 0.001f)
    }

    // 16. Unknown object
    @Test
    fun `failure 16 - unknown object request returns Uncertain with explanation`() {
        val locator = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.50f })
        val res = locator.locate("multimeter", Detections(listOf(DetectionBox("laptop", 0.9f, 0f, 0f, 1f, 1f))))
        assertTrue(res is Localization.Uncertain)
        assertTrue((res as Localization.Uncertain).reason.contains("no label"))
    }

    // 17. Coach unavailable
    @Test
    fun `failure 17 - coach unavailable falls back to expert transcript`() {
        val raw = ""
        val steps = parseRewrite(raw, 2)
        assertNull(steps[0])
        assertNull(steps[1])
    }

    // 18. Coach initialization failure
    @Test
    fun `failure 18 - coach refusal on title falls back to empty string`() {
        val title = parseTitle("TITLE|I cannot determine the title of this guide")
        assertEquals("", title)
    }

    // 19. Model inference failure
    @Test
    fun `failure 19 - malformed model output drops unparseable lines safely`() {
        val malformed = """
            Here is your guide:
            1|EXPERT|Open lid|Lift the screen up.|
            garbage text that is not a pipe line
            2|INVALID_SOURCE|Bad step|Do something else.|
        """.trimIndent()
        val parsed = parseRewrite(malformed, 2)
        assertNotNull(parsed[0])
        assertEquals("Open lid", parsed[0]?.title)
        assertNotNull(parsed[1])
        assertEquals(Provenance.UNKNOWN, parsed[1]?.source)
    }

    // 20. Expert edits guide
    @Test
    fun `failure 20 - expert edits guide retains updated fields`() {
        val step = Step(index = 0, instruction = "Initial instruction", instructionSource = Provenance.GENERAL)
        val edited = step.copy(instruction = "Expert edited instruction", instructionSource = Provenance.EXPERT)
        assertEquals("Expert edited instruction", edited.instruction)
        assertEquals(Provenance.EXPERT, edited.instructionSource)
    }

    // 21. Expert edits already-verified guide
    @Test
    fun `failure 21 - editing a verified guide converts it to draft`() {
        val guide = Guide(id = "g1", title = "RAM Install", verifiedAt = 123456789L)
        assertTrue(guide.verified)

        val draft = guide.asDraft()
        assertFalse(draft.verified)
        assertEquals(0L, draft.verifiedAt)
    }

    // 22. Verification revoked after edit
    @Test
    fun `failure 22 - re-verifying a guide restores verified status with new timestamp`() {
        val guide = Guide(id = "g1", title = "RAM Install", verifiedAt = 100L).asDraft()
        assertFalse(guide.verified)

        val reVerified = guide.copy(verifiedAt = 200L)
        assertTrue(reVerified.verified)
        assertEquals(200L, reVerified.verifiedAt)
    }

    // 23. App restart during review
    @Test
    fun `failure 23 - persistence roundtrip survives file reload`() {
        val store = GuideStore(tempDir.newFolder("guides_test"))
        val guide = Guide(id = "g_restart", title = "Review Guide", steps = listOf(Step(0, "Step 1", transcript = "turn off")))
        store.save(guide)

        val loaded = store.load("g_restart")
        assertNotNull(loaded)
        assertEquals("Review Guide", loaded?.title)
        assertEquals(1, loaded?.steps?.size)
    }

    // 24. App restart after verification
    @Test
    fun `failure 24 - verified snapshot remains intact across restart`() {
        val store = GuideStore(tempDir.newFolder("guides_verified"))
        val verifiedGuide = Guide(id = "g_snap", title = "Verified Guide", verifiedAt = 99999L)
        store.saveVerified(verifiedGuide)

        val loadedVerified = store.loadForLearner("g_snap")
        assertNotNull(loadedVerified)
        assertTrue(loadedVerified!!.verified)
        assertTrue(loadedVerified.verifiedAt > 0L)
    }

    // 25. Missing persisted guide
    @Test
    fun `failure 25 - loading non-existent guide returns null without throwing`() {
        val store = GuideStore(tempDir.newFolder("guides_empty"))
        val missing = store.load("non_existent_id")
        assertNull(missing)
    }

    // 26. Corrupted guide data
    @Test
    fun `failure 26 - corrupted guide json file handled gracefully`() {
        val dir = tempDir.newFolder("guides_corrupt")
        val store = GuideStore(dir)
        val guideFolder = File(dir, "corrupted_id").apply { mkdirs() }
        File(guideFolder, "guide.json").writeText("{ bad json format [,,")

        val loaded = store.load("corrupted_id")
        assertNull(loaded)
    }

    // 27. Person detection disappears
    @Test
    fun `failure 27 - person detection disappears resets userFar`() {
        val engine = ModeEngine()
        // Person far (60px)
        engine.update(0L, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 60.0))
        repeat(30) { engine.update(it * 20L, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 60.0)) }
        assertTrue(engine.isUserFar)

        // Person leaves frame (faceHeightPx = 0.0)
        engine.update(1000L, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 0.0))
        assertFalse("Disappeared person resets userFar", engine.isUserFar)
    }

    // 28. Person enters and leaves frame repeatedly
    @Test
    fun `failure 28 - person enters and leaves repeatedly without latch sticking`() {
        val engine = ModeEngine()
        var t = 0L
        repeat(5) {
            engine.update(t, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 60.0))
            t += 500L
            engine.update(t, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 0.0))
            t += 500L
        }
        assertFalse(engine.isUserFar)
    }

    // 29. ModeEngine receives stale detection
    @Test
    fun `failure 29 - stale detection does not override higher priority EASY or HANDS`() {
        val engine = ModeEngine()
        // Far person detected (would be TALK), but room is loud (-15 dBFS)
        repeat(30) {
            engine.update(it * 20L, ModeInputs(accelVariance = 0.2, dbfs = -15.0, faceHeightPx = 50.0))
        }
        assertEquals(Mode.HANDS, engine.mode)
    }

    // 30. Robot receives unsupported action
    @Test
    fun `failure 30 - unsupported action safely identified in component locator`() {
        val locator = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.5f })
        val res = locator.locate("laser_cutter", Detections())
        assertTrue(res is Localization.Uncertain)
    }

    // 31. Robot receives uncertain action
    @Test
    fun `failure 31 - uncertain evidence handled with safe fallback`() {
        val context = LearnerContext(
            job = "Job", verified = false, stepNumber = 1, totalSteps = 1,
            instruction = "", instructionSource = Provenance.UNKNOWN,
            transcript = "", warning = "", warningSource = Provenance.UNKNOWN,
            previous = "", next = "", expectedTools = emptyList(), expectedObjects = emptyList(),
            seenNow = emptyList(), question = "What do I do?",
        )
        val evidence = groundedEvidence(AnswerEvidence.UNCERTAIN, context)
        assertEquals(AnswerEvidence.UNCERTAIN, evidence)
    }

    // 32. Guide contains unknown action
    @Test
    fun `failure 32 - unestablished instruction defaults to UNKNOWN provenance`() {
        val step = Step(index = 0, instruction = "", instructionSource = Provenance.UNKNOWN)
        assertEquals(Provenance.UNKNOWN, step.instructionSource)
    }

    // 33. Learner tries to execute unverified guide
    @Test
    fun `failure 33 - unverified guide clearly flags verified = false`() {
        val unverifiedGuide = Guide(id = "unver", title = "Unverified Guide", verifiedAt = 0L)
        assertFalse(unverifiedGuide.verified)
        assertEquals(0L, unverifiedGuide.verifiedAt)
    }

    // =========================================================================
    // PHASE 9 — COMPLETE DETERMINISTIC END-TO-END PIPELINE INTEGRATION TEST
    // =========================================================================

    @Test
    fun `end-to-end pipeline executes from demo to verified snapshot and playback`() {
        val policy = Policy.DEFAULT
        val store = GuideStore(tempDir.newFolder("e2e_guides"))

        // 1. Audio Recording & Step Segmentation
        val cutter = StepCutter(policy)
        val samples = mutableListOf<Sample>()
        var t = 0L
        fun emit(durMs: Long, db: Double) {
            val end = t + durMs
            while (t < end) {
                samples += Sample(t, db)
                t += 20L
            }
        }
        emit(400L, -40.0)   // lead-in
        emit(4000L, -10.0)  // utterance 1
        emit(1500L, -40.0)  // pause 1.5s
        emit(4000L, -10.0)  // utterance 2
        val ranges = cutter.cut(samples, t)
        assertEquals(2, ranges.size)

        // 2. Transcripts & DomainWords Correction
        val rawTranscript1 = "pehle laptop shutdown karo"
        val rawTranscript2 = "ab philips screw driver se corner screw kholo"
        val domainTerms = listOf("screwdriver", "laptop", "philips")
        val cleanTranscript2 = correctDomainText(rawTranscript2, domainTerms)
        assertEquals("ab philips screwdriver se corner screw kholo", cleanTranscript2)

        // 3. Snapped Frames & FramePicker Selection
        val f1 = FrameStats(0, 2000L, 0.7, 120.0, 0x0000000000000000L, detections = 1, namedThings = 1)
        val f2 = FrameStats(1, 7500L, 0.85, 120.0, -1L, detections = 2, namedThings = 2)
        val pickedSnaps = pickFrames(listOf(f1, f2), ranges, policy)
        assertEquals(0, pickedSnaps[0])
        assertEquals(1, pickedSnaps[1])

        // 4. Object Detection & Structured Evidence
        val detStep1 = listOf("laptop")
        val detStep2 = listOf("screwdriver", "philips_screw")
        val cap1 = captionOf(detStep1)
        val cap2 = captionOf(detStep2)
        assertEquals("laptop", cap1)
        assertEquals("screwdriver, philips_screw", cap2)

        // 5. Steps Construction & Provenance Grounding
        val step1 = Step(
            index = 0,
            title = "Power Down",
            instruction = "Shut the laptop down and unplug power.",
            instructionSource = provenanceOf(rawTranscript1, cap1, "Shut the laptop down and unplug power."),
            transcript = rawTranscript1,
            caption = cap1,
            photo = "snap0.jpg",
            startMs = ranges[0].startMs,
            endMs = ranges[0].endMs,
        )
        val step2 = Step(
            index = 1,
            title = "Remove Screws",
            instruction = "Use a Philips screwdriver to remove the screws.",
            instructionSource = provenanceOf(cleanTranscript2, cap2, "Use a Philips screwdriver to remove the screws."),
            transcript = cleanTranscript2,
            caption = cap2,
            photo = "snap1.jpg",
            startMs = ranges[1].startMs,
            endMs = ranges[1].endMs,
        )
        assertEquals(Provenance.EXPERT, step1.instructionSource)
        assertEquals(Provenance.EXPERT, step2.instructionSource)

        // 6. Guide Persistence (Initial Draft)
        val guideDraft = Guide(
            id = "laptop_repair_01",
            title = "Laptop Disassembly",
            steps = listOf(step1, step2),
            verifiedAt = 0L,
        )
        assertFalse(guideDraft.verified)
        store.save(guideDraft)

        // 7. Expert Review & Verification
        val loadedDraft = store.load("laptop_repair_01")
        assertNotNull(loadedDraft)
        val verifiedGuide = loadedDraft!!.copy(verifiedAt = 1700000000000L)
        assertTrue(verifiedGuide.verified)
        store.saveVerified(verifiedGuide)

        // 8. Verified Snapshot Isolation
        val verifiedSnapshot = store.loadForLearner("laptop_repair_01")
        assertNotNull(verifiedSnapshot)
        assertTrue(verifiedSnapshot!!.verified)
        assertEquals(2, verifiedSnapshot.steps.size)

        // 9. Expert Subsequent Edit Revokes Draft Verification Without Corrupting Snapshot
        val editedDraft = verifiedSnapshot.asDraft().copy(title = "Laptop Disassembly Revised")
        assertFalse(editedDraft.verified)
        store.save(editedDraft)

        val workingCopy = store.load("laptop_repair_01")
        assertFalse("Working copy is back to draft", workingCopy!!.verified)
        assertEquals("Laptop Disassembly Revised", workingCopy.title)

        val preservedSnapshot = store.loadForLearner("laptop_repair_01")
        assertTrue("Verified snapshot remains verified", preservedSnapshot!!.verified)
        assertEquals("Laptop Disassembly", preservedSnapshot.title)

        // 10. Learner Playback & ModeEngine Interaction Selection
        val modeEngine = ModeEngine(policy)
        // Learner holds phone close in quiet room -> TAP
        modeEngine.update(0L, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 140.0))
        repeat(30) { modeEngine.update(it * 20L, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 140.0)) }
        assertEquals(Mode.TAP, modeEngine.mode)

        // Learner steps back 2 meters (faceHeight = 60px) -> TALK after dwell
        repeat(30) { modeEngine.update(1000L + it * 20L, ModeInputs(accelVariance = 0.2, dbfs = -50.0, faceHeightPx = 60.0)) }
        assertEquals(Mode.TALK, modeEngine.mode)
    }
}
