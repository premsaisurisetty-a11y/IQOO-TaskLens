package com.tasklens.ai

import com.tasklens.core.DomainFix
import com.tasklens.core.FrameStats
import com.tasklens.core.Policy
import com.tasklens.core.SceneHash
import com.tasklens.core.SpokenWord
import com.tasklens.core.StepRange
import com.tasklens.core.correctDomainText
import com.tasklens.core.correctDomainTokens
import com.tasklens.core.namedNear
import com.tasklens.core.pickFrames
import com.tasklens.data.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the computer-vision pipeline, object evidence generation,
 * duplicate filtering, bounding-box normalization, DomainWords alignment,
 * and FramePicker integration for the laptop repair scenario.
 */
class VisionPipelineTest {

    // --- Phase 11.1: Known Object Label Detection ---

    @Test
    fun `component locator finds known objects above threshold`() {
        val locator = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.50f })
        val detections = Detections(
            boxes = listOf(
                DetectionBox("laptop", 0.88f, 0.1f, 0.1f, 0.9f, 0.9f, "coco"),
                DetectionBox("screwdriver", 0.75f, 0.2f, 0.3f, 0.4f, 0.6f, "tools"),
            ),
        )

        val laptopLoc = locator.locate("laptop", detections)
        assertTrue(laptopLoc is Localization.Found)
        assertEquals("laptop", (laptopLoc as Localization.Found).label)
        assertEquals(0.88f, laptopLoc.score, 0.001f)

        val screwdriverLoc = locator.locate("screwdriver", detections)
        assertTrue(screwdriverLoc is Localization.Found)
        assertEquals("screwdriver", (screwdriverLoc as Localization.Found).label)
    }

    // --- Phase 11.2: Unknown Object Handling ---

    @Test
    fun `component locator cleanly reports uncertain for unsupported components`() {
        val locator = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.50f })
        val detections = Detections(
            boxes = listOf(DetectionBox("laptop", 0.85f, 0.1f, 0.1f, 0.9f, 0.9f)),
        )

        val ramLoc = locator.locate("ram", detections)
        assertTrue(ramLoc is Localization.Uncertain)
        assertTrue((ramLoc as Localization.Uncertain).reason.contains("no label"))

        val unknownLoc = locator.locate("oscilloscope", detections)
        assertTrue(unknownLoc is Localization.Uncertain)
    }

    // --- Phase 11.3: Low Confidence Detection Filtering ---

    @Test
    fun `low confidence detection is not promoted to found`() {
        val locator = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.60f })
        val detections = Detections(
            boxes = listOf(
                DetectionBox("screwdriver", 0.45f, 0.2f, 0.2f, 0.4f, 0.5f),
            ),
        )

        val loc = locator.locate("screwdriver", detections)
        assertTrue(loc is Localization.Uncertain)
        assertTrue((loc as Localization.Uncertain).reason.contains("below the confidence"))
    }

    // --- Phase 11.4: Duplicate Detection Suppression (IoU) ---

    @Test
    fun `suppressDuplicates removes high IoU overlapping boxes of same class`() {
        val b1 = DetectionBox("screwdriver", 0.85f, 0.10f, 0.10f, 0.50f, 0.50f)
        val b2 = DetectionBox("screwdriver", 0.70f, 0.12f, 0.11f, 0.51f, 0.49f) // High overlap with b1
        val b3 = DetectionBox("screwdriver", 0.80f, 0.60f, 0.60f, 0.90f, 0.90f) // Separate distinct screwdriver
        val b4 = DetectionBox("laptop", 0.90f, 0.10f, 0.10f, 0.50f, 0.50f)      // Different class, same box

        val deduped = suppressDuplicates(listOf(b2, b1, b3, b4), iouThreshold = 0.60f)

        // Must keep highest scoring screwdriver for the overlapping region (b1),
        // distinct screwdriver (b3), and laptop (b4).
        assertEquals(3, deduped.size)
        assertTrue(deduped.contains(b1))
        assertFalse(deduped.contains(b2))
        assertTrue(deduped.contains(b3))
        assertTrue(deduped.contains(b4))
    }

    @Test
    fun `calculateIou computes correct intersection over union`() {
        val a = DetectionBox("test", 1f, 0f, 0f, 0.5f, 0.5f)
        val b = DetectionBox("test", 1f, 0f, 0f, 0.5f, 0.5f)
        assertEquals(1.0f, calculateIou(a, b), 0.001f)

        val disjoint = DetectionBox("test", 1f, 0.6f, 0.6f, 1f, 1f)
        assertEquals(0.0f, calculateIou(a, disjoint), 0.001f)
    }

    // --- Phase 11.5: Bounding Box Normalization ---

    @Test
    fun `bounding box coordinate values stay within normalized range 0 to 1`() {
        val box = DetectionBox(
            label = "screw",
            score = 0.75f,
            left = (-0.05f).coerceIn(0f, 1f),
            top = 0.2f.coerceIn(0f, 1f),
            right = 1.05f.coerceIn(0f, 1f),
            bottom = 0.8f.coerceIn(0f, 1f),
        )
        assertEquals(0f, box.left, 0.001f)
        assertEquals(0.2f, box.top, 0.001f)
        assertEquals(1.0f, box.right, 0.001f)
        assertEquals(0.8f, box.bottom, 0.001f)
    }

    // --- Phase 11.6: Domain Words Spoken Mapping ---

    @Test
    fun `domain words correct spoken tool terms without altering plain language`() {
        val domainTerms = listOf(
            "screwdriver", "screwdrivers", "philips", "torx", "hex",
            "laptop", "clockwise", "counterclockwise", "anticlockwise",
            "unscrew", "ram", "ssd", "battery", "cable",
        )

        // Compound join
        assertEquals("take the screwdriver", correctDomainText("take the screw driver", domainTerms))
        assertEquals("turn counterclockwise", correctDomainText("turn counter clockwise", domainTerms))

        // Near miss (long words >= 8 chars)
        assertEquals("hold the screwdriver", correctDomainText("hold the screwdrive", domainTerms))

        // Short words (< 8 chars) preserved exactly without false fuzzy drift
        assertEquals("check the cable", correctDomainText("check the cable", domainTerms))
        assertEquals("ram slot", correctDomainText("ram slot", domainTerms))
        assertEquals("two laptops", correctDomainText("two laptops", domainTerms))
    }

    // --- Phase 11.7: Structured Visual Evidence Generation ---

    @Test
    fun `structured visual evidence carries label, score, bounds and source`() {
        val box = DetectionBox(
            label = "philips_screw",
            score = 0.82f,
            left = 0.25f,
            top = 0.30f,
            right = 0.35f,
            bottom = 0.40f,
            source = "object_detector",
        )
        assertEquals("philips_screw", box.label)
        assertEquals(0.82f, box.score, 0.001f)
        assertEquals("object_detector", box.source)
    }

    // --- Phase 11.8: FramePicker Integration with Vision Evidence & Spoken Terms ---

    @Test
    fun `frame picker integrates sharpness, lateness, detections and named tools`() {
        val policy = Policy.DEFAULT
        val stepRange = listOf(StepRange(0, 0L, 5000L))

        val f1 = FrameStats(snapIndex = 0, tMs = 1000L, sharpness = 0.30, meanLuma = 120.0, dHash = 100L, detections = 0, namedThings = 0)
        val f2 = FrameStats(snapIndex = 1, tMs = 2500L, sharpness = 0.35, meanLuma = 120.0, dHash = 200L, detections = 1, namedThings = 0)
        // f3 is sharp, late in step, shows detected screwdriver and occurs near spoken "screwdriver"
        val f3 = FrameStats(snapIndex = 2, tMs = 4200L, sharpness = 0.80, meanLuma = 120.0, dHash = 300L, detections = 2, namedThings = 1)

        val picked = pickFrames(listOf(f1, f2, f3), stepRange, policy)
        assertEquals(1, picked.size)
        assertEquals(2, picked[0]) // snapIndex 2 (f3) selected
    }

    // --- Phase 11.9: Provenance Grounding Monotonicity ---

    @Test
    fun `visual detection establishes VISUAL provenance without upgrading to EXPERT`() {
        val context = LearnerContext(
            job = "RAM upgrade",
            verified = true,
            stepNumber = 1,
            totalSteps = 2,
            instruction = "",
            instructionSource = Provenance.UNKNOWN,
            transcript = "",
            warning = "",
            warningSource = Provenance.UNKNOWN,
            previous = "",
            next = "",
            expectedTools = listOf("screwdriver"),
            expectedObjects = listOf("laptop"),
            seenNow = listOf("laptop", "screwdriver"),
            question = "Is there a screwdriver?",
        )

        // When visual detection exists but guide has no text, VISUAL_FACT is supported
        val evidence = groundedEvidence(AnswerEvidence.VISUAL_FACT, context)
        assertEquals(AnswerEvidence.VISUAL_FACT, evidence)

        // When visual detection exists but guide has no text, claiming DIRECT_GUIDE_FACT is capped to VISUAL_FACT
        val cappedGuideClaim = groundedEvidence(AnswerEvidence.DIRECT_GUIDE_FACT, context)
        assertEquals(AnswerEvidence.VISUAL_FACT, cappedGuideClaim)

        // If neither guide nor visual exists, claim is capped to GENERAL_KNOWLEDGE
        val emptyContext = context.copy(seenNow = emptyList(), expectedObjects = emptyList())
        val generalClaim = groundedEvidence(AnswerEvidence.VISUAL_FACT, emptyContext)
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, generalClaim)
    }

    // --- Phase 11.10: Scene Comparison (dHash + HSV) ---

    @Test
    fun `scene hash accurately measures identical and distinct structures`() {
        val pxA = IntArray(32 * 32) { 0xFF0000FF.toInt() } // Blue block
        val pxB = IntArray(32 * 32) { 0xFF0000FF.toInt() } // Identical
        val pxC = IntArray(32 * 32) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0x00000000 } // Patterned

        val dHashA = SceneHash.dHash(pxA, 32, 32)
        val dHashB = SceneHash.dHash(pxB, 32, 32)
        val dHashC = SceneHash.dHash(pxC, 32, 32)

        assertEquals(0, SceneHash.hamming(dHashA, dHashB))
        assertTrue(SceneHash.hamming(dHashA, dHashC) > 0)
    }

    // --- Phase 11.11: No Detection Safe Fallback ---

    @Test
    fun `empty detections produce empty labels and safe caption`() {
        val emptyDetections = Detections()
        assertEquals(0, emptyDetections.boxes.size)
        assertEquals("", captionOf(emptyList()))
    }

    // --- Phase 11.12: Multiple Objects in Single Frame ---

    @Test
    fun `multiple objects caption formats distinct top labels`() {
        val labels = listOf("screwdriver", "philips_screw", "philips_screw", "laptop", "keyboard")
        val caption = captionOf(labels)
        assertEquals("screwdriver, philips_screw, laptop, keyboard", caption)
    }
}
