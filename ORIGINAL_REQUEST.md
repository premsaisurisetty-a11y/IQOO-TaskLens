# Original User Request

## 2026-09-18T14:32:12Z

This is a single self-contained fix; keep it small and focused. Diagnose and resolve the on-device Large Language Model (Coach) initialization failure in the Task Lens Android app, ensuring the offline LLM engine successfully loads and initializes on the device hardware without throwing "Unable to open zip archive".

Working directory: c:\Users\sri charan\Desktop\iqoo
Integrity mode: development

## Requirements

### R1. Resolve Model Loading Failure in Task Lens Coach
Investigate the exact root cause of `Unable to open zip archive` occurring in MediaPipe Tasks GenAI (`third_party/odml/litert_lm/runtime/util/zip_utils.cc` / `model_asset_bundle_resources.cc`) when loading the model in `files/models/coach.task`. Determine whether the model bundle needs repackaging with the proper metadata/tokenizer, or if the runtime loader configuration/version in `Coach.kt` needs adjustment, and implement the necessary fix.

### R2. Verify Successful Engine Initialization on Device
Ensure that when `warmUp()` or `askCoach()` is called, the Coach engine initializes on the device backend (GPU or CPU) and reports `Ready (<backend>)` rather than `Initialization failed`.

### R3. Maintain Core Architectural Invariants
Ensure zero `INTERNET` permission survives in AndroidManifest, offline-only operation is strictly maintained, `core/` stays free of `android.*` imports, and existing unit tests pass cleanly.

## Acceptance Criteria

### Engine Initialization & Build
- [ ] `./gradlew test` passes all unit tests.
- [ ] `./gradlew assembleDebug` builds successfully.
- [ ] On connected device (`48f4d1da`), Task Lens launches and `Coach.status` transitions to `Ready (GPU)` or `Ready (CPU)` without throwing `Unable to open zip archive`.
- [ ] `DebugScreen` confirms active backend delegate.
