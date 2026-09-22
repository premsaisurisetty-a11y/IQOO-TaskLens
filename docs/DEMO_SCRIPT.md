# TaskLens — 3–5 Minute Hackathon Demo Script (iQOO City Battles 2026)

This document provides the exact sequence for presenting TaskLens to judges in 3 to 5 minutes.

---

## 1. The Core Pitch (30 seconds)

> *"TaskLens transforms expert physical demonstrations into structured, offline, verifiable guides on-device. An expert performs a repair once while talking. TaskLens turns that take into step-by-step instructions with verified photos and audio. A learner can then follow the guide hands-free. Everything runs directly on the device with zero cloud dependency and no internet permission."*

---

## 2. Live Demonstration Sequence (3 minutes)

### Step 1: Prove Offline Architecture (15 seconds)
1. Open TaskLens on the iQOO demo phone.
2. Point to the top pill: **`✈ No internet`**.
3. *Judge Check*: Swipe down to Android App Info $\to$ Permissions $\to$ Point out that **`INTERNET`** permission is not requested or declared.

### Step 2: Expert Demonstration (45 seconds)
1. Tap **`+ Show a new job`**.
2. Select Language: **Hindi** or **English**.
3. Tap **Record** and narrate the 6-step laptop disassembly:
   - *"Pehle laptop complete shutdown karo aur charger nikal lo."* (Pause 1.5s)
   - *"Ab Philips screwdriver lo aur corner screws kholo."* (Pause 1.5s)
   - *"Sare screws counter clockwise ghuma ke nikal lo."* (Pause 1.5s)
   - *"Bottom cover ke edges ko dhyan se pry tool se alag karo."* (Pause 1.5s)
   - *"Yeh battery cable hai, isko motherboard se disconnect karna hai."* (Pause 1.5s)
   - *"Connector ko safely pull karke disconnect kar do."*
4. Tap **Done**.
5. Show the **ProcessingScreen**: Point out the live pipeline stages executing on-device (*Writing down speech $\to$ Cutting steps $\to$ Picking photos $\to$ Coaching*).

### Step 3: Review Screen & Provenance Trust (45 seconds)
1. In **ReviewScreen**, show the **Guide Trust Summary**:
   - `6 steps`, `✓ 6 expert`, `▣ 6 visual`.
2. Point to the step badge: **`✓ From expert demonstration`**.
3. **Show Verification Revocation**:
   - Edit Step 1 title or text.
   - Point out the status change: **`⚠ Verification revoked — changes require review`**.
   - Explain to the judge: *"TaskLens never silently marks an edited draft as verified."*
4. Tap **`This is right - verify`** to stamp the immutable **`guide.verified.json`** snapshot.

### Step 4: Learner Mode & Hands-Free Interaction (45 seconds)
1. Open the verified guide in **PlayerScreen**.
2. Point to the **Demo HUD**: **`[✈ OFFLINE] [✓ VERIFIED SNAPSHOT] [STEP 1/6] [MODE: TAP]`**.
3. **Demonstrate ModeEngine Hysteresis**:
   - Hold phone close: **`TAP — user is close to phone in quiet room`**.
   - Place phone on bench and step back 2 meters: Mode automatically switches to **`TALK — user is far from device (>1.5m)`** or **`HANDS-FREE`** after 500 ms dwell.
4. **Ask the Gemma Coach a Question**:
   - Tap **Ask** and ask: *"Which screwdriver size should I use?"*
   - Show the grounded answer badge: **`✓ From expert transcript in this guide`** or **`✦ General repair knowledge (not verified by expert)`**.
   - Ask an ungrounded adversarial question: *"What is the warranty period of this SSD?"*
   - Point out honest refusal: **`◦ I don't have enough verified information to answer that.`**

### Step 5: Diagnostics & Live Telemetry (30 seconds)
1. Open **Settings $\to$ Debug Screen**.
2. Show live measured values:
   - Available RAM & Thermal status
   - Gemma Coach model: `555 MB`, backend: `GPU / CPU`, latency: `~480 ms`
   - COCO Object Detector delegate & inference latency: `~38 ms`
   - Person distance tracking & Schmitt trigger thresholds (`80px / 100px`)
   - Adaptive gate noise floor tracking (`dBFS`)
3. Tap **`Run Full Pipeline Benchmark`** to display live microsecond timings.

---

## 3. Safe Demo Backup Options

If network/recording conditions on the demo stage are hostile:
- Go to **Settings $\to$ Seed Demo Guide** to instantly populate the deterministic 6-step verified guide.
- Tap **Reset Demo Session** to clear ephemeral state back to a pristine demo start without losing model files.
