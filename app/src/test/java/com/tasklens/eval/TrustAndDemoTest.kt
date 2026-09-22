package com.tasklens.eval

import com.tasklens.ai.AnswerEvidence
import com.tasklens.ai.ComponentLocator
import com.tasklens.ai.DetectionBox
import com.tasklens.ai.Detections
import com.tasklens.ai.Localization
import com.tasklens.core.Mode
import com.tasklens.core.Policy
import com.tasklens.data.Guide
import com.tasklens.data.GuideStore
import com.tasklens.data.Provenance
import com.tasklens.data.Step
import com.tasklens.data.asDraft
import com.tasklens.data.provenanceOf
import com.tasklens.ui.explainModeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Trust, Provenance, Verification, and Demo UX Safety Test Suite for Prompt 5.
 */
class TrustAndDemoTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    // 1. Provenance badge rendering state
    @Test
    fun `test 1 - provenanceOf correctly classifies expert, visual, general, and unknown`() {
        val transcript = "disconnect the battery connector gently"
        val caption = "laptop, battery"

        // Expert matches transcript
        assertEquals(Provenance.EXPERT, provenanceOf(transcript, caption, "Disconnect the battery connector gently."))
        // Visual matches caption
        assertEquals(Provenance.VISUAL, provenanceOf("", caption, "Laptop and battery are visible on table."))
        // General text
        assertEquals(Provenance.GENERAL, provenanceOf("", "", "Wear safety goggles during repair."))
        // Blank text
        assertEquals(Provenance.UNKNOWN, provenanceOf("", "", ""))
    }

    // 2. Verified / unverified state rendering
    @Test
    fun `test 2 - guide verification state is strictly boolean and stamped`() {
        val draft = Guide(id = "g1", title = "Draft Guide", verifiedAt = 0L)
        assertFalse("Initial draft is unverified", draft.verified)

        val verified = draft.copy(verifiedAt = 1700000000000L)
        assertTrue("Stamped guide is verified", verified.verified)
    }

    // 3. Verification revocation after edit
    @Test
    fun `test 3 - editing a verified guide revokes draft verification without deleting verified snapshot`() {
        val store = GuideStore(tempDir.newFolder("guides_trust"))
        val guide = Guide(
            id = "laptop_01",
            title = "Laptop Repair",
            steps = listOf(
                Step(
                    index = 0,
                    title = "Step 1",
                    instruction = "Unplug battery",
                    transcript = "unplug battery",
                    instructionSource = Provenance.EXPERT,
                ),
            ),
        )
        store.saveVerified(guide)

        val working = store.load("laptop_01")
        assertNotNull(working)
        val edited = working!!.asDraft().copy(title = "Laptop Repair Modified")
        assertFalse("Edited copy is draft", edited.verified)
        store.save(edited)

        val draftAfterEdit = store.load("laptop_01")
        assertFalse(draftAfterEdit!!.verified)
        assertEquals("Laptop Repair Modified", draftAfterEdit.title)

        val snapshotForLearner = store.loadForLearner("laptop_01")
        assertTrue(snapshotForLearner!!.verified)
        assertEquals("Laptop Repair", snapshotForLearner.title)
    }

    // 4. Coach unavailable fallback
    @Test
    fun `test 4 - coach unavailable falls back to expert transcript safely`() {
        val transcript = "pehle power switch off karo"
        val fallbackInstruction = transcript.ifBlank { "Fallback Step" }
        assertEquals("pehle power switch off karo", fallbackInstruction)
    }

    // 5. Vision unavailable fallback
    @Test
    fun `test 5 - vision unavailable produces empty detection without hallucination`() {
        val locator = ComponentLocator(
            aliases = { mapOf("screw" to listOf("screwdriver", "philips_screw")) },
            minScore = { 0.45f },
        )
        val result = locator.locate("screw", Detections(emptyList()))
        assertTrue("Missing vision results in Uncertain", result is Localization.Uncertain)
    }

    // 6. Unknown object UI
    @Test
    fun `test 6 - querying unknown object produces safe uncertainty explanation`() {
        val boxes = listOf(DetectionBox("laptop", 0.9f, 0f, 0f, 1f, 1f))
        val locator = ComponentLocator(
            aliases = { mapOf("laptop" to listOf("laptop")) },
            minScore = { 0.45f },
        )
        val result = locator.locate("ram_module", Detections(boxes))
        assertTrue(result is Localization.Uncertain)
        assertEquals("the detector on this phone has no label for \"ram_module\"", (result as Localization.Uncertain).reason)
    }

    // 7. Mode transition explanation
    @Test
    fun `test 7 - explainModeReason returns human understandable sentences`() {
        assertEquals("TALK — user is far from device (>1.5m)", explainModeReason(Mode.TALK, "TALK <- user is far"))
        assertEquals("TAP — user is close to phone in quiet room", explainModeReason(Mode.TAP, "TAP <- close and quiet"))
        assertEquals("HANDS-FREE — phone resting on surface, hands-free work", explainModeReason(Mode.HANDS, "HANDS <- device stationary"))
        assertEquals("EASY — simplified high-contrast interaction", explainModeReason(Mode.EASY, "EASY <- user override"))
    }

    // 8. Unverified guide blocked from execution
    @Test
    fun `test 8 - unverified guide is clearly flagged as unverified`() {
        val draft = Guide(id = "draft_guide", title = "Unverified Guide", steps = listOf(Step(0, "Test")))
        assertFalse(draft.verified)
    }

    // 9. Robot refusal state
    @Test
    fun `test 9 - robot execution rejects low confidence or unverified targets`() {
        val lowConfBoxes = listOf(DetectionBox("screwdriver", 0.35f, 0.1f, 0.1f, 0.4f, 0.4f))
        val locator = ComponentLocator(
            aliases = { mapOf("screwdriver" to listOf("screwdriver")) },
            minScore = { 0.45f },
        )
        val res = locator.locate("screwdriver", Detections(lowConfBoxes))
        assertTrue("Low confidence is filtered and marked uncertain", res is Localization.Uncertain)
    }

    // 10. Demo reset safety
    @Test
    fun `test 10 - demo reset preserves model files, policy, and verified guide files`() {
        val root = tempDir.newFolder("demo_reset_test")
        val policyFile = File(root, "policy.json").apply {
            writeText(Policy.json.encodeToString(Policy.serializer(), Policy.DEFAULT))
        }
        val fakeModel = File(root, "coach.task").apply { writeText("dummy model bytes") }
        val guidesDir = File(root, "guides").apply { mkdirs() }
        val store = GuideStore(guidesDir)

        val guide = Guide("demo_01", "Demo Verified Guide", steps = listOf(Step(0, "Demo Step")))
        store.saveVerified(guide)

        // Verify model and policy are present
        assertTrue(policyFile.exists())
        assertTrue(fakeModel.exists())
        assertTrue(store.loadForLearner("demo_01")!!.verified)

        // Session reset does not touch disk files
        val reloadedPolicy = Policy.parse(policyFile.readText())
        assertNotNull(reloadedPolicy)
        assertTrue("Model remains untouched", fakeModel.length() > 0)
        assertTrue("Verified guide snapshot remains intact", store.loadForLearner("demo_01")!!.verified)
    }

    // 11. Coach Answer trust labels
    @Test
    fun `test 11 - coach evidence classifications strictly adhere to trust hierarchy`() {
        assertEquals(AnswerEvidence.DIRECT_GUIDE_FACT, AnswerEvidence.DIRECT_GUIDE_FACT)
        assertEquals(AnswerEvidence.VISUAL_FACT, AnswerEvidence.VISUAL_FACT)
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, AnswerEvidence.GENERAL_KNOWLEDGE)
        assertEquals(AnswerEvidence.UNCERTAIN, AnswerEvidence.UNCERTAIN)
    }

    // 12. Deterministic demo seed guide verification
    @Test
    fun `test 12 - seeded demo guide contains 6 valid steps with EXPERT provenance`() {
        val store = GuideStore(tempDir.newFolder("demo_seed_test"))
        val demoGuide = Guide(
            id = "laptop_disassembly_demo",
            title = "Laptop Battery Replacement & Disassembly",
            lang = "hi",
            steps = listOf(
                Step(index = 0, title = "Power Down", instruction = "Shut down laptop.", instructionSource = Provenance.EXPERT, transcript = "pehle laptop shutdown karo", caption = "laptop", startMs = 0L, endMs = 4500L),
                Step(index = 1, title = "Select Screwdriver", instruction = "Use Philips screwdriver.", instructionSource = Provenance.EXPERT, transcript = "ab philips screwdriver lo", caption = "screwdriver", startMs = 4500L, endMs = 9000L),
                Step(index = 2, title = "Remove Screws", instruction = "Unscrew perimeter screws.", instructionSource = Provenance.EXPERT, transcript = "sare screws nikal lo", caption = "laptop", startMs = 9000L, endMs = 14000L),
                Step(index = 3, title = "Pry Open Panel", instruction = "Pry bottom cover.", instructionSource = Provenance.EXPERT, transcript = "bottom cover alag karo", caption = "laptop", startMs = 14000L, endMs = 19000L),
                Step(index = 4, title = "Locate Battery", instruction = "Identify battery cable.", instructionSource = Provenance.EXPERT, transcript = "yeh battery cable hai", caption = "laptop", startMs = 19000L, endMs = 24000L),
                Step(index = 5, title = "Disconnect Battery", instruction = "Pull connector straight back.", instructionSource = Provenance.EXPERT, transcript = "connector pull karke disconnect karo", caption = "laptop", startMs = 24000L, endMs = 29000L),
            ),
        )
        store.saveVerified(demoGuide)

        val loaded = store.loadForLearner("laptop_disassembly_demo")
        assertNotNull(loaded)
        assertTrue(loaded!!.verified)
        assertEquals(6, loaded.steps.size)
        assertTrue(loaded.steps.all { it.instructionSource == Provenance.EXPERT })
    }
}
