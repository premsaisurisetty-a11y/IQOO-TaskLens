/**
 * Task Lens — iQOO City Battles 2026 Qualifier Interactive Prototype
 * State Engine & Reactive Simulation Controller
 */

// --- Global Simulator State ---
const State = {
  activeTab: 'show', // 'show', 'player', 'tuner', 'robot', 'manifest'
  
  // Show Mode State
  isRecording: false,
  recordTimer: 0,
  recordInterval: null,
  currentDb: -52.0,
  noiseFloor: -53.0,
  pauseTimerMs: 0,
  recordedSteps: [],
  speechIndex: 0,
  reviewMode: false,
  verified: false,
  
  // Guide / Player State
  currentStepIndex: 0,
  playerMode: 'TALK',
  playerReason: 'TALK <- phone is flat (var 0.01)',
  benchSimilarity: 86,
  isPlayingVoice: false,
  
  // Sensor Inputs (7 Signals)
  sensors: {
    easyMode: false,
    accelVariance: 0.012, // < 0.05 is flat
    dbfs: -48.0,
    speechUnclear: false,
    userFar: false,
    gestureDetected: 'NONE',
    sceneMatchPct: 86
  },
  
  // Policy Tunables (Loaded from Policy.kt / policy.json)
  policy: {
    pauseMs: 1200,
    minUtteranceMs: 2500,
    speechMarginDb: 9.0,
    dwellMs: 400,
    inHandEnterVar: 0.09,
    inHandExitVar: 0.05,
    roomLoudEnterDb: -26.0,
    roomLoudExitDb: -32.0,
    detectMinScore: 0.3
  }
};

// --- Repair Guide Dataset (Realistic Demo: Open Laptop & Swap RAM) ---
const GUIDE_DATA = [
  {
    index: 1,
    title: "Neeche ke do screw khol do",
    instruction: "Undo the two screws at the bottom of the back panel using a Philips screwdriver.",
    transcript: "sabse pehle laptop ko ulta karo aur neeche ke do screw khol do",
    source: "EXPERT",
    photo: "assets/08-player-step1.jpg",
    voiceTime: "0:00 - 0:04",
    boxes: [
      { label: "screwdriver", score: "0.94", top: "25%", left: "30%", width: "22%", height: "35%" },
      { label: "philips_screw", score: "0.78", top: "65%", left: "62%", width: "12%", height: "14%" }
    ],
    audioDuration: 3200
  },
  {
    index: 2,
    title: "Back panel ko dheere se uthao",
    instruction: "Lift the back panel gently. Do not pull by the ribbon cable.",
    transcript: "phir uske baad back panel ko dheere se uthao, dhyaan se ribbon cable mat kheench dena",
    source: "EXPERT",
    warning: "Caution: Do not yank the ribbon cable connecting to the chassis.",
    photo: "assets/09-player-step2.jpg",
    voiceTime: "0:04 - 0:08",
    boxes: [
      { label: "laptop", score: "0.96", top: "15%", left: "12%", width: "75%", height: "70%" }
    ],
    audioDuration: 3800
  },
  {
    index: 3,
    title: "RAM module ko release karo",
    instruction: "Push the two side metal clips outward to pop up the old RAM module.",
    transcript: "ab dono side ke metal clips ko bahar dabao, ram module upar uth jayega",
    source: "EXPERT",
    photo: "assets/04-show-step2.jpg",
    voiceTime: "0:08 - 0:13",
    boxes: [
      { label: "ram_module", score: "0.89", top: "35%", left: "40%", width: "24%", height: "28%" }
    ],
    audioDuration: 4100
  },
  {
    index: 4,
    title: "Naya RAM module angle par lagao",
    instruction: "Slide the new RAM module firmly into the slot at a 30-degree angle.",
    transcript: "iske baad naya ram module tees degree ke angle par slot mein fit karo",
    source: "EXPERT",
    photo: "assets/10-player-live.jpg",
    voiceTime: "0:13 - 0:17",
    boxes: [
      { label: "ram_module", score: "0.92", top: "30%", left: "38%", width: "26%", height: "30%" }
    ],
    audioDuration: 3500
  },
  {
    index: 5,
    title: "Panel wapas lagao aur band karo",
    instruction: "Snap down until clips click, refit the back panel and tighten clockwise.",
    transcript: "aakhir mein clips click hone tak dabao aur panel band karke screw tighten kar do",
    source: "EXPERT",
    photo: "assets/06-review.jpg",
    voiceTime: "0:17 - 0:21",
    boxes: [
      { label: "laptop", score: "0.98", top: "20%", left: "15%", width: "70%", height: "65%" }
    ],
    audioDuration: 3900
  }
];

// --- Simulated Take Script for Show Mode ---
const SIM_SPEECH_STREAM = [
  { text: "sabse pehle", isLink: true, pauseAfter: 300 },
  { text: "laptop ko ulta karo", isLink: false, pauseAfter: 400 },
  { text: "aur neeche ke", isLink: false, pauseAfter: 200 },
  { text: "do screw khol do", isLink: false, pauseAfter: 1400, triggerCut: true, stepTitle: "Undo back screws" },
  
  { text: "phir", isLink: true, pauseAfter: 350 },
  { text: "uske baad", isLink: true, pauseAfter: 400 },
  { text: "back panel ko", isLink: false, pauseAfter: 250 },
  { text: "dheere se uthao", isLink: false, pauseAfter: 1350, triggerCut: true, stepTitle: "Lift back panel" },
  
  { text: "ab", isLink: true, pauseAfter: 300 },
  { text: "dono side ke clips", isLink: false, pauseAfter: 400 },
  { text: "bahar dabao", isLink: false, pauseAfter: 1300, triggerCut: true, stepTitle: "Release RAM clips" },
  
  { text: "iske baad", isLink: true, pauseAfter: 350 },
  { text: "naya ram module", isLink: false, pauseAfter: 300 },
  { text: "angle par lagao", isLink: false, pauseAfter: 1400, triggerCut: true, stepTitle: "Insert new RAM module" },
  
  { text: "aakhir mein", isLink: true, pauseAfter: 400 },
  { text: "panel band karke", isLink: false, pauseAfter: 300 },
  { text: "screws tighten karo", isLink: false, pauseAfter: 1500, triggerCut: true, stepTitle: "Refit panel and screws" }
];

// --- Web Audio Synthesizer for Audio Feedback ---
let audioCtx = null;
function getAudioContext() {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  }
  return audioCtx;
}

function playTone(freq = 440, type = 'sine', duration = 0.15) {
  try {
    const ctx = getAudioContext();
    if (ctx.state === 'suspended') ctx.resume();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, ctx.currentTime);
    gain.gain.setValueAtTime(0.12, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + duration);
  } catch (e) {
    // Audio synthesis fallback
  }
}

// ==========================================================================
// Mode Engine (Port of core/ModeEngine.kt)
// ==========================================================================
class SchmittTrigger {
  constructor(enter, exit) {
    this.enter = enter;
    this.exit = exit;
    this.state = false;
  }
  update(v) {
    if (this.enter >= this.exit) {
      this.state = this.state ? (v >= this.exit) : (v >= this.enter);
    } else {
      this.state = this.state ? (v <= this.exit) : (v <= this.enter);
    }
    return this.state;
  }
}

class ModeEngineSimulator {
  constructor(policy) {
    this.policy = policy;
    this.inHand = new SchmittTrigger(policy.inHandEnterVar, policy.inHandExitVar);
    this.roomLoud = new SchmittTrigger(policy.roomLoudEnterDb, policy.roomLoudExitDb);
    this.mode = 'TAP';
    this.reason = 'TAP <- start';
    this.pending = null;
    this.pendingSince = 0;
  }

  update(nowMs, inputs) {
    const held = this.inHand.update(inputs.accelVariance);
    const loud = this.roomLoud.update(inputs.dbfs);
    const candidate = this.decide(inputs, held, loud);

    if (candidate.mode === this.mode) {
      this.pending = null;
      return false;
    }

    if (!this.pending || this.pending.mode !== candidate.mode) {
      this.pending = candidate;
      this.pendingSince = nowMs;
      return false;
    }

    if (nowMs - this.pendingSince < this.policy.dwellMs) {
      return false;
    }

    this.mode = candidate.mode;
    this.reason = candidate.reason;
    this.pending = null;
    return true;
  }

  decide(inputs, held, loud) {
    if (inputs.easyMode) {
      return { mode: 'EASY', reason: 'EASY <- user setting' };
    }
    if (loud) {
      return { mode: 'HANDS', reason: `HANDS <- room is loud (${inputs.dbfs.toFixed(1)} dBFS)` };
    }
    if (inputs.speechUnclear) {
      return { mode: 'HANDS', reason: 'HANDS <- speech was unclear' };
    }
    if (!held) {
      return { mode: 'TALK', reason: `TALK <- phone is flat (var ${inputs.accelVariance.toFixed(3)})` };
    }
    if (inputs.userFar) {
      return { mode: 'TALK', reason: 'TALK <- user is far' };
    }
    return { mode: 'TAP', reason: 'TAP <- held, quiet, close' };
  }
}

const engine = new ModeEngineSimulator(State.policy);

// ==========================================================================
// DOM Initialization & Event Listeners
// ==========================================================================
document.addEventListener('DOMContentLoaded', () => {
  initTabs();
  initWaveformCanvas();
  initShowMode();
  initPlayerMode();
  initSensorsAndTriggers();
  initPolicyTuner();
  initCoach();
  initGalleryModal();
  startSensorTickLoop();
});

// --- Tabs Switching ---
function initTabs() {
  const tabs = document.querySelectorAll('.sim-tab-btn');
  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      tabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      const target = tab.dataset.tab;
      State.activeTab = target;
      renderSimulatorView(target);
    });
  });
}

function renderSimulatorView(tabName) {
  const showView = document.getElementById('showModeView');
  const playerView = document.getElementById('playerModeView');
  const appModeTag = document.getElementById('appModeTag');
  
  if (tabName === 'show') {
    showView.style.display = 'flex';
    playerView.style.display = 'none';
    appModeTag.textContent = 'SHOW MODE';
    appModeTag.className = 'app-mode-tag';
  } else if (tabName === 'player') {
    showView.style.display = 'none';
    playerView.style.display = 'flex';
    appModeTag.textContent = 'GUIDE MODE';
    appModeTag.className = 'app-mode-tag';
    renderPlayerStep(State.currentStepIndex);
  } else if (tabName === 'tuner') {
    document.getElementById('policyTunerSection')?.scrollIntoView({ behavior: 'smooth' });
  } else if (tabName === 'robot') {
    document.getElementById('robotSection')?.scrollIntoView({ behavior: 'smooth' });
  }
}

// ==========================================================================
// Waveform Canvas Visualizer
// ==========================================================================
let waveCanvas, waveCtx;
function initWaveformCanvas() {
  waveCanvas = document.getElementById('waveformCanvas');
  if (!waveCanvas) return;
  waveCtx = waveCanvas.getContext('2d');
  drawWaveform();
}

function drawWaveform() {
  if (!waveCanvas || !waveCtx) return;
  const width = waveCanvas.width;
  const height = waveCanvas.height;
  waveCtx.clearRect(0, 0, width, height);

  waveCtx.lineWidth = 2;
  waveCtx.strokeStyle = State.isRecording ? '#ff7a00' : '#2a2e40';
  waveCtx.beginPath();

  const sliceWidth = width / 40;
  let x = 0;

  for (let i = 0; i < 40; i++) {
    let amplitude = 2;
    if (State.isRecording) {
      const normalizedDb = Math.max(0, (State.currentDb + 60) / 60);
      amplitude = Math.sin((Date.now() / 80) + i) * (normalizedDb * 18) + (Math.random() * 4);
    }
    const y = (height / 2) + amplitude;
    if (i === 0) waveCtx.moveTo(x, y);
    else waveCtx.lineTo(x, y);
    x += sliceWidth;
  }
  waveCtx.stroke();
  requestAnimationFrame(drawWaveform);
}

// ==========================================================================
// SHOW MODE: Expert Recording & Step Cutter Simulation
// ==========================================================================
function initShowMode() {
  const recordBtn = document.getElementById('recordTakeBtn');
  if (!recordBtn) return;

  recordBtn.addEventListener('click', () => {
    if (!State.isRecording) {
      startRecording();
    } else {
      stopRecording();
    }
  });
}

function startRecording() {
  State.isRecording = true;
  State.speechIndex = 0;
  State.recordedSteps = [];
  State.recordTimer = 0;
  State.noiseFloor = -53.0; // live adaptive floor
  
  const recordBtn = document.getElementById('recordTakeBtn');
  recordBtn.innerHTML = '<span>⏹</span> Stop Take (Process)';
  recordBtn.classList.add('recording');
  
  const streamEl = document.getElementById('transcriptStream');
  streamEl.innerHTML = '<span class="pause-indicator">Listening...</span>';
  
  playTone(520, 'sine', 0.1);

  // Play through the speech script
  playNextSpeechChunk();
}

function playNextSpeechChunk() {
  if (!State.isRecording) return;
  
  if (State.speechIndex >= SIM_SPEECH_STREAM.length) {
    // Take completed
    stopRecording();
    return;
  }

  const chunk = SIM_SPEECH_STREAM[State.speechIndex];
  State.speechIndex++;

  // Update live dBFS to speech level
  State.currentDb = -22.0 + (Math.random() * 8 - 4);
  updateNoiseMeter();

  // Append word to transcript stream
  const streamEl = document.getElementById('transcriptStream');
  const span = document.createElement('span');
  if (chunk.isLink) {
    span.className = 'highlight-word';
    span.textContent = ' ' + chunk.text + ' ';
    playTone(680, 'triangle', 0.08); // subtle cue for linking word
  } else {
    span.textContent = ' ' + chunk.text + ' ';
  }
  streamEl.appendChild(span);
  streamEl.scrollTop = streamEl.scrollHeight;

  // Schedule pause & cut
  setTimeout(() => {
    if (!State.isRecording) return;

    // During pause, drop dBFS to ambient noise floor
    State.currentDb = State.noiseFloor + (Math.random() * 2);
    updateNoiseMeter();

    if (chunk.triggerCut) {
      // Pause exceeded 1200ms -> CUT TRIGGERED!
      const pauseBadge = document.createElement('span');
      pauseBadge.className = 'pause-indicator';
      pauseBadge.textContent = `[CUT @ ${chunk.pauseAfter}ms: ${chunk.stepTitle}]`;
      streamEl.appendChild(pauseBadge);
      
      // Flash photo capture indicator
      flashCameraShutter();
      playTone(880, 'sine', 0.15); // camera snap tone
      
      // Record step into memory
      State.recordedSteps.push({
        title: chunk.stepTitle,
        timestamp: (State.speechIndex * 1.5).toFixed(1) + 's'
      });
      
      document.getElementById('hudStepsCount').textContent = `${State.recordedSteps.length} steps`;
    }

    // Schedule next chunk
    setTimeout(playNextSpeechChunk, chunk.pauseAfter || 400);
  }, 400);
}

function flashCameraShutter() {
  const box = document.getElementById('cameraPreviewBox');
  if (!box) return;
  const flash = document.createElement('div');
  flash.style.position = 'absolute';
  flash.style.inset = '0';
  flash.style.background = '#fff';
  flash.style.zIndex = '30';
  flash.style.opacity = '0.7';
  flash.style.transition = 'opacity 0.25s ease';
  box.appendChild(flash);
  setTimeout(() => {
    flash.style.opacity = '0';
    setTimeout(() => flash.remove(), 250);
  }, 50);
}

function updateNoiseMeter() {
  const fill = document.getElementById('dbBarFill');
  const val = document.getElementById('liveDbVal');
  const floorVal = document.getElementById('noiseFloorVal');
  if (!fill || !val) return;

  const pct = Math.min(100, Math.max(0, ((State.currentDb + 60) / 60) * 100));
  fill.style.width = pct + '%';
  val.textContent = `${State.currentDb.toFixed(1)} dBFS`;
  if (floorVal) floorVal.textContent = `Floor: ${State.noiseFloor.toFixed(1)} dBFS`;
}

function stopRecording() {
  State.isRecording = false;
  const recordBtn = document.getElementById('recordTakeBtn');
  recordBtn.innerHTML = '<span>🎙️</span> Start One-Take Recording';
  recordBtn.classList.remove('recording');
  
  State.currentDb = -52.0;
  updateNoiseMeter();
  playTone(360, 'sine', 0.2);

  // Show processing overlay
  showProcessingModal();
}

function showProcessingModal() {
  const streamEl = document.getElementById('transcriptStream');
  streamEl.innerHTML = `
    <div style="padding: 10px; background: rgba(0, 240, 255, 0.08); border-radius: 8px; border: 1px solid rgba(0, 240, 255, 0.3);">
      <div style="font-weight: 700; color: var(--cyber-cyan); margin-bottom: 6px;">⚡ On-Device 6-Stage Processing:</div>
      <div style="font-size: 0.76rem; color: #a5b4fc; line-height: 1.5;">
        ✔ 1. PCM16 WAV split at 5 pause boundaries<br>
        ✔ 2. Vosk ASR word-timing alignment<br>
        ✔ 3. Gemma 3 1B on-device coach summary & instruction rewrite<br>
        ✔ 4. dHash keyframe sharpness & HSV extraction<br>
        ✔ 5. MediaPipe screw/screwdriver detection<br>
        ✔ 6. Guide assembled into files/guides/sample-backpanel/
      </div>
      <button id="viewReviewBtn" class="btn btn-primary" style="margin-top: 10px; width: 100%; font-size: 0.78rem; padding: 6px 10px;">
        Review & Verify Guide (5 Steps) →
      </button>
    </div>
  `;

  document.getElementById('viewReviewBtn')?.addEventListener('click', () => {
    // Switch to player mode
    document.querySelector('.sim-tab-btn[data-tab="player"]').click();
  });
}

// ==========================================================================
// GUIDE MODE / PLAYER: Live Hands-Free Player Simulation
// ==========================================================================
function initPlayerMode() {
  renderPlayerStep(0);

  // Voice Play Button
  document.getElementById('playVoiceBtn')?.addEventListener('click', () => {
    playStepVoiceAudio();
  });

  // Next / Prev Step Handlers
  document.getElementById('stepPrevBtn')?.addEventListener('click', () => {
    navigateStep(-1);
  });
  document.getElementById('stepNextBtn')?.addEventListener('click', () => {
    navigateStep(1);
  });

  // Simulate Palm & Fist Triggers
  document.getElementById('simPalmBtn')?.addEventListener('click', () => {
    triggerGesture('PALM');
  });
  document.getElementById('simFistBtn')?.addEventListener('click', () => {
    triggerGesture('FIST');
  });

  // Keyboard Shortcuts (Space for next, Backspace for prev)
  window.addEventListener('keydown', (e) => {
    if (State.activeTab === 'player') {
      if (e.code === 'Space') {
        e.preventDefault();
        triggerGesture('PALM');
      } else if (e.key === 'ArrowLeft' || e.code === 'Backspace') {
        e.preventDefault();
        triggerGesture('FIST');
      }
    }
  });
}

function renderPlayerStep(index) {
  if (index < 0) index = 0;
  if (index >= GUIDE_DATA.length) index = GUIDE_DATA.length - 1;
  State.currentStepIndex = index;

  const step = GUIDE_DATA[index];

  // Header and indicators
  document.getElementById('playerStepIndicator').textContent = `STEP ${index + 1} OF ${GUIDE_DATA.length}`;
  document.getElementById('playerInstruction').textContent = step.instruction;
  document.getElementById('playerTranscript').textContent = `"${step.transcript}"`;
  document.getElementById('playerVoiceMeta').textContent = `${step.title} (${step.voiceTime})`;

  // Inset photo
  const insetImg = document.getElementById('insetGuidePhoto');
  if (insetImg) insetImg.src = step.photo;

  // Bounding Boxes Rendering
  renderBoundingBoxes(step.boxes);

  // Update Scene Check Similarity
  State.benchSimilarity = 78 + Math.floor(Math.random() * 16);
  document.getElementById('sceneSimilarityVal').textContent = `${State.benchSimilarity}% MATCH`;
  document.getElementById('signalSceneMatch').textContent = `${State.benchSimilarity}%`;
}

function renderBoundingBoxes(boxes) {
  const container = document.getElementById('bboxContainer');
  if (!container) return;
  container.innerHTML = '';

  if (!boxes || boxes.length === 0) return;

  boxes.forEach(box => {
    const el = document.createElement('div');
    el.className = 'bbox-overlay';
    el.style.top = box.top;
    el.style.left = box.left;
    el.style.width = box.width;
    el.style.height = box.height;

    const label = document.createElement('div');
    label.className = 'bbox-label';
    label.textContent = `${box.label} ${box.score}`;
    el.appendChild(label);

    container.appendChild(el);
  });
}

function navigateStep(direction) {
  const newIndex = State.currentStepIndex + direction;
  if (newIndex >= 0 && newIndex < GUIDE_DATA.length) {
    playTone(direction > 0 ? 640 : 420, 'triangle', 0.12);
    renderPlayerStep(newIndex);
  }
}

function triggerGesture(gesture) {
  State.sensors.gestureDetected = gesture;
  const tag = document.getElementById('signalGesture');
  if (tag) {
    tag.textContent = gesture;
    tag.className = 'signal-val orange';
  }

  // Visual gesture notification banner
  showFloatingAlert(`Hand Gesture: ${gesture} detected → ${gesture === 'PALM' ? 'NEXT STEP' : 'PREV STEP'}`);

  if (gesture === 'PALM') {
    navigateStep(1);
  } else if (gesture === 'FIST') {
    navigateStep(-1);
  }

  setTimeout(() => {
    State.sensors.gestureDetected = 'NONE';
    if (tag) {
      tag.textContent = 'NONE';
      tag.className = 'signal-val';
    }
  }, 1200);
}

function playStepVoiceAudio() {
  const step = GUIDE_DATA[State.currentStepIndex];
  const btn = document.getElementById('playVoiceBtn');
  if (State.isPlayingVoice) return;

  State.isPlayingVoice = true;
  btn.innerHTML = '⏸';
  btn.style.background = 'var(--status-green)';

  // Synthesize Hindi tone cadence
  playTone(330, 'sawtooth', 0.18);
  setTimeout(() => playTone(392, 'sawtooth', 0.22), 220);
  setTimeout(() => playTone(440, 'sawtooth', 0.25), 500);

  setTimeout(() => {
    State.isPlayingVoice = false;
    btn.innerHTML = '▶';
    btn.style.background = 'var(--iqoo-orange)';
  }, step.audioDuration || 3000);
}

// ==========================================================================
// 7 Sensors & Mode Engine Tick Loop
// ==========================================================================
function initSensorsAndTriggers() {
  // Toggle: Pick up phone (In Hand)
  document.getElementById('toggleHeldBtn')?.addEventListener('click', (e) => {
    const isFlat = State.sensors.accelVariance < 0.05;
    State.sensors.accelVariance = isFlat ? 0.14 : 0.012; // toggle between held variance and flat
    e.target.classList.toggle('active', !isFlat);
    e.target.textContent = !isFlat ? '📱 Picked Up (Held)' : '📱 Flat on Table';
  });

  // Toggle: Workshop Loud Noise
  document.getElementById('toggleLoudBtn')?.addEventListener('click', (e) => {
    const isLoud = State.sensors.dbfs > -30;
    State.sensors.dbfs = isLoud ? -48.0 : -18.0;
    e.target.classList.toggle('active', !isLoud);
    e.target.textContent = !isLoud ? '🔊 Workshop Loud (-18 dBFS)' : '🔇 Room Quiet (-48 dBFS)';
  });

  // Toggle: User Far
  document.getElementById('toggleFarBtn')?.addEventListener('click', (e) => {
    State.sensors.userFar = !State.sensors.userFar;
    e.target.classList.toggle('active', State.sensors.userFar);
    e.target.textContent = State.sensors.userFar ? '🚶 User Far' : '👤 User Near';
  });

  // Toggle: Easy Mode
  document.getElementById('toggleEasyBtn')?.addEventListener('click', (e) => {
    State.sensors.easyMode = !State.sensors.easyMode;
    e.target.classList.toggle('active', State.sensors.easyMode);
    e.target.textContent = State.sensors.easyMode ? '⚙️ Easy Mode (Active)' : '⚙️ Easy Mode (Off)';
  });
}

function startSensorTickLoop() {
  setInterval(() => {
    const nowMs = Date.now();
    
    // Update Mode Engine
    const changed = engine.update(nowMs, State.sensors);
    
    // Update Mode Badge in DOM
    const modeChip = document.getElementById('playerModeChip');
    const reasonBox = document.getElementById('engineReasonText');
    const signalMode = document.getElementById('signalCurrentMode');
    
    if (modeChip) {
      modeChip.textContent = engine.mode;
      modeChip.className = `mode-chip mode-${engine.mode.toLowerCase()}`;
    }
    if (reasonBox) {
      reasonBox.textContent = engine.reason;
    }
    if (signalMode) {
      signalMode.textContent = engine.mode;
    }

    // Update Signals Dashboard
    const sigDb = document.getElementById('signalDb');
    if (sigDb) sigDb.textContent = `${State.sensors.dbfs.toFixed(1)} dBFS`;

    const sigHeld = document.getElementById('signalHeld');
    if (sigHeld) {
      sigHeld.textContent = engine.isInHand ? 'HELD' : 'FLAT';
      sigHeld.className = engine.isInHand ? 'signal-val orange' : 'signal-val green';
    }

    const sigLoud = document.getElementById('signalLoud');
    if (sigLoud) {
      sigLoud.textContent = engine.isRoomLoud ? 'LOUD' : 'QUIET';
      sigLoud.className = engine.isRoomLoud ? 'signal-val orange' : 'signal-val green';
    }

    const sigVar = document.getElementById('signalVariance');
    if (sigVar) sigVar.textContent = State.sensors.accelVariance.toFixed(3);
  }, 100);
}

// ==========================================================================
// COACH LLM ASSISTANT (With Provenance Grounding)
// ==========================================================================
const COACH_KNOWLEDGE = [
  {
    keywords: ["screwdriver", "tool", "pechkas", "screw"],
    response: "Use a standard Philips PH0 or PH00 screwdriver. The back panel screws are standard M2×4mm thread.",
    source: "EXPERT"
  },
  {
    keywords: ["cable", "ribbon", "careful", "warning"],
    response: "Do not pull the back panel quickly. The chassis ribbon cable has very little slack and tear easily.",
    source: "EXPERT"
  },
  {
    keywords: ["ram", "clip", "clips", "angle"],
    response: "Insert the new RAM module at approximately 30 degrees until gold pins are seated, then press downward until both metal clips click.",
    source: "EXPERT"
  },
  {
    keywords: ["battery", "charge", "power"],
    response: "Ensure the laptop is unplugged from the AC adapter before touching internal components.",
    source: "GENERAL"
  },
  {
    keywords: ["coffee", "water", "lunch", "weather"],
    response: "I can only advise on steps shown in this laptop repair guide. I do not guess answers outside the recording.",
    source: "UNKNOWN"
  }
];

function initCoach() {
  const askBtn = document.getElementById('coachAskBtn');
  const input = document.getElementById('coachQuestionInput');
  if (!askBtn || !input) return;

  const handleAsk = () => {
    const q = input.value.trim();
    if (!q) return;
    askCoach(q);
  };

  askBtn.addEventListener('click', handleAsk);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') handleAsk();
  });
}

function askCoach(question) {
  const responseBox = document.getElementById('coachResponseBox');
  responseBox.style.display = 'block';
  responseBox.innerHTML = '<span style="color: var(--cyber-cyan);">Thinking (on-device Gemma 3 1B int4)...</span>';

  playTone(550, 'sine', 0.1);

  setTimeout(() => {
    const qLower = question.toLowerCase();
    let match = COACH_KNOWLEDGE.find(k => k.keywords.some(kw => qLower.includes(kw)));
    if (!match) {
      match = {
        response: `The expert did not specifically mention "${question}" in her take. Always verify screw tightness before powering on.`,
        source: "GENERAL"
      };
    }

    let provClass = 'prov-expert';
    if (match.source === 'VISUAL') provClass = 'prov-visual';
    if (match.source === 'GENERAL') provClass = 'prov-general';
    if (match.source === 'UNKNOWN') provClass = 'prov-unknown';

    responseBox.innerHTML = `
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
        <strong style="color: #fff; font-size: 0.85rem;">Coach (Gemma 3 1B int4)</strong>
        <span class="provenance-tag ${provClass}">Provenance: ${match.source}</span>
      </div>
      <div style="color: #d1d5db; font-size: 0.84rem; line-height: 1.5;">${match.response}</div>
    `;
  }, 350);
}

// ==========================================================================
// POLICY ENGINE LIVE TUNER (Red Light Simulator)
// ==========================================================================
function initPolicyTuner() {
  const pauseSlider = document.getElementById('tunerPauseMs');
  const dwellSlider = document.getElementById('tunerDwellMs');
  const marginSlider = document.getElementById('tunerMarginDb');
  const applyBtn = document.getElementById('applyPolicyBtn');

  if (pauseSlider) {
    pauseSlider.addEventListener('input', (e) => {
      document.getElementById('valPauseMs').textContent = `${e.target.value} ms`;
    });
  }
  if (dwellSlider) {
    dwellSlider.addEventListener('input', (e) => {
      document.getElementById('valDwellMs').textContent = `${e.target.value} ms`;
    });
  }
  if (marginSlider) {
    marginSlider.addEventListener('input', (e) => {
      document.getElementById('valMarginDb').textContent = `${e.target.value} dB`;
    });
  }

  applyBtn?.addEventListener('click', () => {
    State.policy.pauseMs = parseInt(pauseSlider.value, 10);
    State.policy.dwellMs = parseInt(dwellSlider.value, 10);
    State.policy.speechMarginDb = parseFloat(marginSlider.value);

    engine.policy.dwellMs = State.policy.dwellMs;

    playTone(880, 'triangle', 0.15);
    showFloatingAlert(`Pushed to files/policy.json via Office Kit! Retuned live with 0 compilation.`);
  });
}

// ==========================================================================
// Gallery Modal & Lightbox
// ==========================================================================
function initGalleryModal() {
  const cards = document.querySelectorAll('.gallery-card');
  cards.forEach(card => {
    card.addEventListener('click', () => {
      const img = card.querySelector('img');
      const title = card.querySelector('.gallery-screen-title')?.textContent || '';
      const desc = card.querySelector('.gallery-screen-desc')?.textContent || '';
      if (img) {
        openModal(img.src, title, desc);
      }
    });
  });
}

function openModal(src, title, desc) {
  let modal = document.getElementById('galleryModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'galleryModal';
    modal.style.cssText = `
      position: fixed; inset: 0; background: rgba(0,0,0,0.85); backdrop-filter: blur(10px);
      z-index: 1000; display: flex; align-items: center; justify-content: center; padding: 24px;
    `;
    modal.innerHTML = `
      <div style="background: #12141e; border: 1px solid var(--border-subtle); border-radius: 16px; max-width: 500px; width: 100%; overflow: hidden; position: relative;">
        <button id="closeModalBtn" style="position: absolute; top: 12px; right: 14px; background: rgba(0,0,0,0.6); border: none; color: #fff; font-size: 1.2rem; cursor: pointer; border-radius: 50%; width: 32px; height: 32px;">✕</button>
        <div style="max-height: 600px; overflow: hidden; background: #000; display: flex; justify-content: center;">
          <img id="modalImg" src="" style="max-height: 550px; width: auto; object-fit: contain;">
        </div>
        <div style="padding: 16px;">
          <h3 id="modalTitle" style="font-size: 1.1rem; margin-bottom: 6px;"></h3>
          <p id="modalDesc" style="font-size: 0.85rem; color: var(--text-muted);"></p>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
    modal.addEventListener('click', (e) => {
      if (e.target === modal || e.target.id === 'closeModalBtn') modal.style.display = 'none';
    });
  }
  document.getElementById('modalImg').src = src;
  document.getElementById('modalTitle').textContent = title;
  document.getElementById('modalDesc').textContent = desc;
  modal.style.display = 'flex';
}

function showFloatingAlert(msg) {
  const alert = document.createElement('div');
  alert.style.cssText = `
    position: fixed; bottom: 24px; right: 24px; background: #181c2b; color: #fff;
    border: 1px solid var(--iqoo-orange); border-radius: 8px; padding: 12px 18px;
    font-size: 0.85rem; font-weight: 600; box-shadow: 0 10px 30px rgba(0,0,0,0.6);
    z-index: 999; animation: slideUp 0.25s ease; display: flex; align-items: center; gap: 8px;
  `;
  alert.innerHTML = `<span>⚡</span> ${msg}`;
  document.body.appendChild(alert);
  setTimeout(() => {
    alert.style.opacity = '0';
    alert.style.transition = 'opacity 0.3s ease';
    setTimeout(() => alert.remove(), 300);
  }, 2800);
}
