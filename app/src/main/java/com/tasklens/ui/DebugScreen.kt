package com.tasklens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The screen I will be staring at all weekend.
 *
 * Everything that decides anything is on here as a number, plus the reason
 * string for the last committed mode switch. If the app misbehaves on the
 * demo floor, the answer is on this screen.
 */
@Composable
fun DebugScreen(vm: TaskLensViewModel) {
    val d by vm.debug.collectAsStateWithLifecycle()
    val p by vm.policy.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Debug", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { vm.go(Screen.Library) }) { Text("Back") }
        }

        Meter("level / gate", d.levelDb, d.gateDb)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "level %.1f   gate %.1f   floor %.1f dBFS".format(d.levelDb, d.gateDb, d.floorDb),
                fontFamily = FontFamily.Monospace,
            )
        }
        Kv("speech now", if (d.levelDb > d.gateDb) "YES" else "no")
        Kv("accel variance", "%.4f".format(d.accelVariance))
        Kv("recording", if (d.recording) "${d.elapsedMs / 1000}s, ${d.samples} samples" else "no")
        Kv("live cuts", d.liveCuts.toString())
        Kv("photos snapped", d.snaps.toString())

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("models on this phone", style = MaterialTheme.typography.titleSmall)
        for ((name, there) in vm.modelsPresent()) {
            Kv(name, if (there) "present" else "MISSING (falls back, never fakes)")
        }
        Kv("detector delegate", vm.detectorDelegate)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("gemma ai coach telemetry", style = MaterialTheme.typography.titleSmall)
        Kv("coach model on phone", if (vm.coachPresent) "yes" else "NO -- coach is off")
        Kv("model path", vm.coachModelPath)
        Kv("model size", if (vm.coachModelSizeBytes > 0) "${vm.coachModelSizeBytes / (1024 * 1024)} MB" else "--")
        Kv("avail ram before load", if (vm.coachAvailMemBeforeLoad > 0) "${vm.coachAvailMemBeforeLoad / (1024 * 1024)} MB" else "--")
        Kv("coach status", vm.coachStatus)
        Kv("coach backend", vm.coachDelegate)
        Kv("init time", if (vm.coachInitTimeMs > 0) "${vm.coachInitTimeMs} ms" else "--")
        Kv("last inference", if (vm.coachLastInferenceMs > 0) "${vm.coachLastInferenceMs} ms" else "--")
        Kv("avg inference", if (vm.coachAvgInferenceMs > 0) "${vm.coachAvgInferenceMs} ms" else "--")
        Kv("inferences (ok / fail)", "${vm.coachSuccessCount} / ${vm.coachFailCount}")
        vm.coachLastError?.let { Kv("last error", it) }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.warmUpCoach() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Warm Up / Retry")
            }
            Button(
                onClick = { vm.resetCoach() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset Coach")
            }
        }

        Text("live gemma inference tests", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.testCoachTitle() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Title (A)")
            }
            Button(
                onClick = { vm.testCoachRewrite() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Rewrite (B)")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.testCoachAnswer() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Q&A (C)")
            }
            Button(
                onClick = { vm.testCoachTranslate() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Translate (D)")
            }
        }

        Button(
            onClick = { vm.runPipelineBenchmark() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text("Run Full Pipeline Benchmark")
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.seedDemoGuide() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Seed Demo Guide")
            }
            Button(
                onClick = { vm.resetDemoSession() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset Demo Session")
            }
        }

        val diagOutput by vm.coachDiagnosticOutput.collectAsStateWithLifecycle()
        diagOutput?.let { out ->
            Box(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).glass(GlassShapeSmall, tone = 1.1f).padding(8.dp),
            ) {
                Text(out, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Kv("mode", d.mode.name)
        Kv("last switch", d.reason)
        Kv("switches", d.switches.toString())

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("User Distance & Mode Telemetry", style = MaterialTheme.typography.titleSmall)
        Kv("Person detected", if (d.personDetected) "YES" else "NO")
        Kv("Normalized height", "${"%.3f".format(d.personNormalizedHeight)} (${d.personHeightPx.toInt()} px)")
        Kv("Normalized area", "%.3f".format(d.personNormalizedArea))
        Kv("Distance state", d.distanceState)
        Kv("Near/Far thresholds", "${p.userFarEnterPx.toInt()} px (enter FAR) / ${p.userFarExitPx.toInt()} px (exit FAR)")
        Kv("Dwell time", "${p.dwellMs} ms")

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Multi-Source Evidence Fusion Telemetry", style = MaterialTheme.typography.titleSmall)
        Kv("Fused state", d.fusedState)
        Kv("Learner readiness", d.learnerReadiness)
        Kv("Strongest source", d.strongestSource)
        Kv("Supporting / Conflicts", "${d.supportingEvidenceCount} / ${d.conflictingEvidenceCount}")
        Kv("Observations / Age", "${d.totalObservations} / ${d.evidenceAgeMs} ms")
        Kv("Manual required", if (d.manualConfirmationRequired) "YES" else "NO")
        Kv("Explanation", d.fusionExplanation)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Adaptive Interaction Telemetry", style = MaterialTheme.typography.titleSmall)
        Kv("Interaction Action", d.interactionAction)
        Kv("Audio Priority", d.interactionPriority)
        Kv("Visual Guidance", d.interactionVisualGuidance.ifBlank { "None" })
        Kv("Spoken Text", d.interactionSpokenText.ifBlank { "(Silent)" })
        Kv("Action Reason", d.interactionReason)
        Kv("Cooldown Remaining", "${d.speechCooldownRemainingMs} ms")
        Kv("Stuck Duration", "${d.stuckTimeMs} ms")
        Kv("Camera Stability", d.cameraStability)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("policy.json (live)", style = MaterialTheme.typography.titleSmall)
        d.policyError?.let { Text("ERROR: $it (keeping last good values)") }
        Kv("gate fall / rise", "${p.gateFallCoef} / ${p.gateRiseCoef}")
        Kv("speech margin", "${p.speechMarginDb} dB")
        Kv("gate clamp", "${p.gateMinDb} .. ${p.gateMaxDb} dBFS")
        Kv("pause / merge", "${p.pauseMs} / ${p.minUtteranceMs} ms")
        Kv("max steps", p.maxSteps.toString())
        Kv("inHand enter/exit", "${p.inHandEnterVar} / ${p.inHandExitVar}")
        Kv("roomLoud enter/exit", "${p.roomLoudEnterDb} / ${p.roomLoudExitDb} dBFS")
        Kv("dwell", "${p.dwellMs} ms")
        Kv("speech unclear below", "${p.speechUnclearConfThreshold} over ${p.speechUnclearWindowWords} words")
        Kv("user far below", "${p.userFarFaceHeightPx} px face")
        Kv("scene advise below", p.sceneAdviseMinSimilarity.toString())
        Kv("gesture dwell / floor", "${p.gestureDwellMs} ms / ${p.gestureMinConfidence}")
        Kv("cut confirm", "${p.confirmMinLinkWords} words within ${p.confirmWindowMs} ms")
        Kv("link words hi", p.linkWordsHi.joinToString(", "))
        Kv("link words mr", p.linkWordsMr.joinToString(", "))

        Text(
            "\nadb push policy.json /data/local/tmp/ && " +
                "adb shell run-as com.tasklens cp /data/local/tmp/policy.json files/policy.json",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall)
        Text(v, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Crude bar: level filled, gate marked. Enough to see the gate track a room. */
@Composable
private fun Meter(label: String, level: Double, gate: Double) {
    val lo = -60f
    val hi = 0f
    fun frac(v: Double) = ((v.toFloat() - lo) / (hi - lo)).coerceIn(0f, 1f)

    Text(label, style = MaterialTheme.typography.labelSmall)
    Box(
        Modifier.fillMaxWidth().height(24.dp).glass(GlassShapeSmall, tone = 1.2f),
    ) {
        Box(
            Modifier.fillMaxWidth(frac(level)).height(24.dp).background(Ink.green),
        )
        Box(
            Modifier.fillMaxWidth(frac(gate)).height(24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.width(2.dp).height(24.dp).background(Ink.red))
        }
    }
}
