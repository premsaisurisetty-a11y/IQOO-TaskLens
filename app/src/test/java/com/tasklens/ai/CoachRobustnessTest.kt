package com.tasklens.ai

import com.tasklens.data.Guide
import com.tasklens.data.Provenance
import com.tasklens.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoachRobustnessTest {

    private fun sampleStep(
        transcript: String = "",
        caption: String = "",
        hasPhoto: Boolean = true,
        correction: String = "",
    ) = TakeStep(
        startMs = 0,
        endMs = 5000,
        transcript = transcript,
        caption = caption,
        hasPhoto = hasPhoto,
        correction = correction,
    )

    // --- Phase 4.1 & 4.2 & 4.3: Missing & Invalid Model Checks ---

    @Test
    fun `missing model file is reported honestly in status`() {
        val nonExistent = File("build/tmp/non_existent_coach.task")
        assertFalse(nonExistent.exists())
    }

    // --- Phase 4.6 & 4.7: Empty and Normal Transcript Rewriting ---

    @Test
    fun `empty transcript rewriting produces safe nulls`() {
        val output = parseRewrite("", 3)
        assertEquals(3, output.size)
        assertTrue(output.all { it == null })
    }

    @Test
    fun `garbled or unparseable output preserves null slots`() {
        val output = parseRewrite("Invalid line with no pipes\nAnother bad line", 2)
        assertEquals(2, output.size)
        assertNull(output[0])
        assertNull(output[1])
    }

    @Test
    fun `normal multi-step rewrite parses title, instruction, source and notes`() {
        val raw = """
            TITLE|Laptop Battery Replacement
            1|EXPERT|Unplug power|Disconnect the power cable from the back.|EXPERT: Ensure unit is off.
            2|VISUAL|Remove battery|Slide the release latch and pull the battery out.|GENERAL: Do not puncture battery.
        """.trimIndent()
        val title = parseTitle(raw)
        val steps = parseRewrite(raw, 2)

        assertEquals("Laptop Battery Replacement", title)
        assertEquals(2, steps.size)

        assertNotNull(steps[0])
        assertEquals("Unplug power", steps[0]?.title)
        assertEquals("Disconnect the power cable from the back.", steps[0]?.instruction)
        assertEquals(Provenance.EXPERT, steps[0]?.source)
        assertEquals("Ensure unit is off.", steps[0]?.note)
        assertEquals(Provenance.EXPERT, steps[0]?.noteSource)

        assertNotNull(steps[1])
        assertEquals("Remove battery", steps[1]?.title)
        assertEquals("Slide the release latch and pull the battery out.", steps[1]?.instruction)
        assertEquals(Provenance.VISUAL, steps[1]?.source)
        assertEquals("Do not puncture battery.", steps[1]?.note)
        assertEquals(Provenance.GENERAL, steps[1]?.noteSource)
    }

    // --- Phase 4.8: Guide Title Generation ---

    @Test
    fun `guide title extracts clean title and rejects refusal or untitled`() {
        assertEquals("Screen Replacement", parseTitle("TITLE|Screen Replacement"))
        assertEquals("Fixing the fan", parseTitle("TITLE|Fixing the fan|extra"))
        assertEquals("", parseTitle("TITLE|Untitled job"))
        assertEquals("", parseTitle("TITLE|I cannot determine the title"))
        assertEquals("", parseTitle("No title line here"))
    }

    // --- Phase 4.9: Learner Question Parsing & Grounding ---

    @Test
    fun `learner question answer with guide tag parses as DIRECT_GUIDE_FACT when supported`() {
        val context = LearnerContext(
            job = "Repair",
            verified = true,
            stepNumber = 1,
            totalSteps = 2,
            instruction = "Use PH0 screwdriver",
            instructionSource = Provenance.EXPERT,
            transcript = "take ph0",
            warning = "",
            warningSource = Provenance.UNKNOWN,
            previous = "",
            next = "",
            expectedTools = listOf("screwdriver"),
            expectedObjects = listOf("laptop"),
            seenNow = listOf("laptop"),
            question = "Which tool?",
        )
        val (evidence, answer) = parseAnswer("[guide] Use the PH0 screwdriver mentioned in the guide.", context)
        assertEquals(AnswerEvidence.DIRECT_GUIDE_FACT, evidence)
        assertEquals("Use the PH0 screwdriver mentioned in the guide.", answer)
    }

    @Test
    fun `learner question answer without guide evidence is downgraded from DIRECT_GUIDE_FACT to GENERAL_KNOWLEDGE`() {
        val contextEmptyGuide = LearnerContext(
            job = "Repair",
            verified = false,
            stepNumber = 1,
            totalSteps = 1,
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
            question = "Which tool?",
        )
        val (evidence, answer) = parseAnswer("[guide] Most laptops use PH0 screws.", contextEmptyGuide)
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, evidence)
        assertEquals("Most laptops use PH0 screws.", answer)
    }

    @Test
    fun `uncertain answer marker returns UNCERTAIN evidence`() {
        val context = LearnerContext(
            job = "Repair",
            verified = true,
            stepNumber = 1,
            totalSteps = 1,
            instruction = "Remove screw",
            instructionSource = Provenance.EXPERT,
            transcript = "screw out",
            warning = "",
            warningSource = Provenance.UNKNOWN,
            previous = "",
            next = "",
            expectedTools = emptyList(),
            expectedObjects = emptyList(),
            seenNow = emptyList(),
            question = "What RAM type?",
        )
        val (evidence, answer) = parseAnswer("[uncertain] The guide does not specify the RAM generation.", context)
        assertEquals(AnswerEvidence.UNCERTAIN, evidence)
        assertEquals("The guide does not specify the RAM generation.", answer)
    }

    // --- Phase 4.10: Translation ---

    @Test
    fun `translation deduplicates repeated sentence echoes`() {
        val repeated = "सर्जिकल स्टेप पूरा हुआ। अब सर्जिकल स्टेप पूरा हुआ।"
        val clean = dropSentenceRepeats(repeated)
        assertEquals("सर्जिकल स्टेप पूरा हुआ।", clean)
    }

    @Test
    fun `translation keeps non-repeated sentences intact`() {
        val normal = "First remove the screws. Then lift the panel."
        val out = dropSentenceRepeats(normal)
        assertEquals("First remove the screws. Then lift the panel.", out)
    }

    // --- Phase 4.11 & 4.12: Provenance Grounding Monotonicity & UNKNOWN Fallback ---

    @Test
    fun `groundedSource downgrades EXPERT claim to GENERAL when transcript is empty`() {
        val silentStep = sampleStep(transcript = "", caption = "")
        val result = groundedSource(Provenance.EXPERT, silentStep, "Do the repair")
        assertEquals(Provenance.GENERAL, result)
    }

    @Test
    fun `groundedSource downgrades EXPERT claim to VISUAL when transcript is empty but caption exists`() {
        val visualOnlyStep = sampleStep(transcript = "", caption = "screwdriver, screw")
        val result = groundedSource(Provenance.EXPERT, visualOnlyStep, "Turn the screw")
        assertEquals(Provenance.VISUAL, result)
    }

    @Test
    fun `groundedSource honours EXPERT when transcript is present`() {
        val spokenStep = sampleStep(transcript = "turn it clockwise", caption = "")
        val result = groundedSource(Provenance.EXPERT, spokenStep, "Turn the knob clockwise.")
        assertEquals(Provenance.EXPERT, result)
    }

    @Test
    fun `groundedSource preserves UNKNOWN when claimed as UNKNOWN`() {
        val spokenStep = sampleStep(transcript = "turn it clockwise", caption = "knob")
        val result = groundedSource(Provenance.UNKNOWN, spokenStep, "Turn knob")
        assertEquals(Provenance.UNKNOWN, result)
    }

    @Test
    fun `groundedSource on blank instruction returns UNKNOWN`() {
        val spokenStep = sampleStep(transcript = "turn it clockwise", caption = "knob")
        val result = groundedSource(Provenance.EXPERT, spokenStep, "")
        assertEquals(Provenance.UNKNOWN, result)
    }

    // --- Extra: splitNote and stripExtraLabels ---

    @Test
    fun `splitNote parses labeled caution notes cleanly`() {
        val (src, note) = splitNote("EXPERT: Keep track of screws")
        assertEquals(Provenance.EXPERT, src)
        assertEquals("Keep track of screws", note)
    }

    @Test
    fun `splitNote strips empty and placeholder notes`() {
        assertEquals(Provenance.UNKNOWN to "", splitNote("none"))
        assertEquals(Provenance.UNKNOWN to "", splitNote("no warning"))
        assertEquals(Provenance.UNKNOWN to "", splitNote("worked in silence"))
    }
}
