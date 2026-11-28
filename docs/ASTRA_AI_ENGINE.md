# ASTRA AI Engine — architecture and commercial-use policy

Status: architecture proposal / implementation not yet enabled.

## Product goal

ASTRA AI Engine is the private, reusable processing layer for ASTRA VIDEO. It powers simple creative actions (animate, add camera movement, apply a look, prepare a clip) while hiding model names, tensor settings, masks, depth maps, and other technical controls from the user.

The engine must remain usable in a proprietary commercial Android application. No component is approved merely because its source repository has a permissive license: code, model weights, training-derived assets, detectors, codecs, fonts, sound assets, and transitive dependencies are reviewed separately.

## Non-negotiable rules

1. No NC / non-commercial-only component in a commercial release.
2. Verify the exact version, artifact, model checkpoint, and dependency—not just the repository's headline license.
3. Keep third-party notices and required license texts in the app's OSS notices.
4. Record provenance, source URL, version or commit, license, modifications, redistribution conditions, and review date for every shipped component.
5. If a component fails review, replace it or implement the required feature independently. Do not silently ship it.
6. Prefer local/offline processing and graceful fallbacks. Cloud APIs and paid quotas are not required for core editing.
7. Do not bundle large models until device memory, speed, thermal behavior, and output quality have been measured on supported phones.

## Candidate components and current disposition

| Component | Intended role | License / condition | Disposition |
|---|---|---|---|
| Depth Anything V2 Small | Automatic depth estimation for subtle parallax and camera movement | Small checkpoint: Apache-2.0. Do not use Base, Large, or Giant checkpoints identified as CC-BY-NC-4.0. Recheck the exact model card and downloaded artifact before bundling. | Candidate; commercial gate passed provisionally |
| LivePortrait | Optional portrait animation | Project code: MIT. Do not ship InsightFace pretrained detector/model assets under their non-commercial research terms. Replace the detector with an approved option such as a specific MediaPipe model, then audit every remaining weight and asset. | Hold; weights and pipeline not cleared |
| MediaPipe | Candidate face detector / landmarks for a LivePortrait adaptation | Framework and relevant model cards commonly use Apache-2.0; verify the exact model card and artifact used. | Candidate; per-model review required |
| OpenCV | Image/video processing helpers | Core project license depends on version; use a reviewed Apache-2.0 release (4.5.0+) and audit enabled third-party modules/build options. | Candidate |
| ONNX Runtime | Local inference runtime | MIT for the runtime; model files and execution-provider dependencies have their own licenses. | Candidate |
| FFmpeg | Media operations, filters, audio mix and export | Build configuration matters: LGPL/GPL options and linked libraries change obligations. Maintain an explicitly reviewed configuration and notices. | Candidate; build-specific gate required |
| AndroidX Media3 Transformer | Android-native editing and export foundation | Apache-2.0; review transitive dependencies and notices. | Candidate |

## Internal architecture

- **AstraMediaProject** — project metadata, source references, edit decisions, effect parameters, audio tracks, and output versions. Preserve source media.
- **AstraEditEngine** — trim, split, join, speed, transitions, color looks, text, audio mix, export.
- **AstraAiRuntime** — model loading, inference scheduling, memory limits, device capability checks, cancellation, and fallback.
- **AstraMotionEngine** — existing motion workflows remain separate and are not replaced without regression tests.
- **AstraLicenseRegistry** — machine-readable inventory of third-party code, models, weights, assets, notices, and approval status.

AI components are optional processors behind stable interfaces. The editor must still open, edit, and export when an AI model is absent or unsupported.

## User-facing experience

Expose creative choices, not technical model controls:
- Auto Style / “Сделай красиво”
- Camera Motion
- Portrait Motion (only after the full pipeline is commercially cleared)
- Cinema, Anime, Dreamy, Horror, Neon and Music Video looks
- Speed, transitions, music and simple timeline editing

Depth maps, face landmarks, inference providers, masks, and model selection remain internal implementation details.

## Implementation sequence

1. Add and maintain the license registry and third-party notices.
2. Stabilize the current player and media project foundation.
3. Build basic editing/export using Android-native media APIs; add FFmpeg only through a reviewed build.
4. Prototype Depth Anything V2 Small as an optional, low-resolution/offline processor; benchmark before integration.
5. Evaluate a MediaPipe-based detector and audit every LivePortrait weight. Keep portrait animation disabled until the full pipeline is cleared.
6. Add automatic style orchestration only after deterministic editing operations and fallbacks are reliable.

## Device constraints

Target phones include Samsung Galaxy A12 (Exynos 850, 4 GB RAM) and Redmi Note 7 (Snapdragon 660 / Adreno 512, 4 GB RAM). These are performance targets, not proof of compatibility. Benchmark CPU/GPU paths separately, cap inference resolution, release intermediate buffers, and offer a non-AI fallback.

## Approval record template

For each shipped item record:
- Name and purpose
- Exact source URL and version/commit
- Exact artifact/checkpoint filename and checksum
- Code license and model/asset license
- Required notices, attribution, source-offer or redistribution obligations
- Transitive dependencies and build flags
- Modifications made
- Commercial approval status: APPROVED / HOLD / REJECTED
- Reviewer and review date

## References to re-check at integration time

- Depth Anything V2: https://github.com/DepthAnything/Depth-Anything-V2
- LivePortrait: https://github.com/KwaiVGI/LivePortrait
- InsightFace model licensing: https://github.com/deepinsight/insightface
- MediaPipe: https://github.com/google-ai-edge/mediapipe
- OpenCV: https://github.com/opencv/opencv
- ONNX Runtime: https://github.com/microsoft/onnxruntime
- FFmpeg licensing: https://ffmpeg.org/legal.html
- AndroidX Media3: https://developer.android.com/media/media3/transformer

This document is an engineering policy and preliminary review, not legal advice or blanket approval of all versions, checkpoints, or build configurations.
