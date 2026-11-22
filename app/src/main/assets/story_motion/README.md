# ASTRA STORY MOTION runtime assets

This directory is the APK asset location expected by `AstraStoryMotionEngine`.

Required files:
- `rife-ncnn-vulkan` — Android ARM64 PIE executable, built for API 24 or newer.
- `model/flownet.bin`
- `model/flownet.param`

## Supplying the runtime to CI

The binary ZIP is intentionally not stored in the source tree. For a manual Story Motion APK build:

1. Upload `ASTRA_STORY_MOTION_RIFE.zip` as a downloadable asset to a trusted GitHub Release (or another HTTPS host that provides a direct download URL).
2. Open **Actions → Build ASTRA APK (ARM64 + local Qwen) → Run workflow** on branch `feature/astra-story-motion`.
3. Set `rife_archive_url` to the direct ZIP download URL. The default `rife_archive_sha256` is the SHA-256 of the inspected archive:
   `9477555bcfb6dec654ce99a2fe326a8c7c11bcd69ac274504c5789bf440bd3f3`.
4. The workflow verifies the archive checksum and ZIP integrity, extracts only the three expected files, checks that the executable is ELF64/AArch64, and then builds the APK.

The runtime ZIP must contain these exact paths at its root:
- `rife-ncnn-vulkan`
- `model/flownet.bin`
- `model/flownet.param`

The app copies these assets into its private `files/story_motion_runtime/` directory on first use and marks the executable as runnable. It uses Android's system `libvulkan.so`; do not copy Termux's Vulkan loader into this directory.

The engine expects the RIFE CLI options `-0`, `-1`, `-o`, `-s`, `-m`, and `-g`. It makes three independent intermediate frames per adjacent keyframe pair at time steps 0.25, 0.50, and 0.75. Vulkan is attempted first; a failed pair is retried on CPU with `-g -1`.

Do not put the binary or model in `jniLibs`: the RIFE executable is a PIE program, not a shared library. Keep it as an asset and launch it as a separate process.

The app uses local RIFE and FFmpeg at runtime. No cloud service, Termux installation, or separate server is required on the phone. The RIFE archive is a build-time input; it is not fetched by the app.
