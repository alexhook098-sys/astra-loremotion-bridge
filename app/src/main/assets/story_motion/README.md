# ASTRA STORY MOTION runtime assets

The RIFE executable and RIFE v4.6 model are built from pinned source during the Android CI build.

Expected APK assets:
- `rife-ncnn-vulkan` — Android ARM64 PIE executable (Android API 24+).
- `rife-v4.6/flownet.bin`
- `rife-v4.6/flownet.param`

The build pins `zyhector/fork-rife-ncnn-vulkan` at commit
`4302d30349c3fe79ff7eb28069aebd155c8b45a8` and its recorded submodule revisions.
A small source patch corrects the pair-image loader so it queues work only when both
images decode successfully. The model directory name deliberately contains
`rife-v4.6`: this RIFE version selects the v4 architecture from that path name.

At runtime ASTRA copies the executable and model to its private app storage,
then invokes RIFE independently for each adjacent keyframe pair. It generates
time steps 0.25, 0.50, and 0.75. Vulkan is attempted first; on failure the pair
is retried on CPU with `-g -1`.

FFmpeg assembles the resulting scene-separated frames locally. No cloud service,
Termux installation, or separate server is required on the phone. The executable
is an asset, not a shared library, and must not be placed in `jniLibs`.
