# ASTRA STORY MOTION runtime assets

This directory is the APK asset location expected by `AstraStoryMotionEngine`.

Required files (not committed yet):
- `rife-ncnn-vulkan` — Android ARM64 PIE executable, built for API 24 or newer.
- `model/flownet.bin`
- `model/flownet.param`

The app copies these assets into its private `files/story_motion_runtime/` directory on first use and marks the executable as runnable. It uses Android's system `libvulkan.so`; do not copy Termux's Vulkan loader into this directory.

The engine currently expects the exact RIFE CLI options `-0`, `-1`, `-o`, `-s`, `-m`, and `-g`. It makes three independent intermediate frames per adjacent keyframe pair at time steps 0.25, 0.50, and 0.75. Vulkan is attempted first; a failed pair is retried on CPU with `-g -1`.

Do not put the binary or model in `jniLibs`: the RIFE executable is a PIE program, not a shared library. Keep it as an asset and launch it as a separate process.

The binary and model are intentionally absent from this source-only commit. Until all three files are added here, the STORY MOTION button will report that the runtime assets are missing. No cloud service or Termux installation is used by the app at runtime.
