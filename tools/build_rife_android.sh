#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the installed Android NDK}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/.build/story-motion-rife"
SOURCE="$WORK/source"
BUILD="$WORK/build"
ASSETS="$ROOT/app/src/main/assets/story_motion"
RIFE_COMMIT="4302d30349c3fe79ff7eb28069aebd155c8b45a8"

rm -rf "$WORK"
mkdir -p "$WORK" "$ASSETS/rife-v4.6"
git clone https://github.com/zyhector/fork-rife-ncnn-vulkan.git "$SOURCE"
git -C "$SOURCE" checkout --detach "$RIFE_COMMIT"
git -C "$SOURCE" submodule update --init --recursive

python3 - "$SOURCE/src/main.cpp" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = "if (ret0 != 0 || ret1 != 1)\n        {\n            v.outimage = ncnn::Mat(v.in0image.w, v.in0image.h, (size_t)3, 3);\n            toproc.put(v);\n        }"
new = "if (ret0 == 0 && ret1 == 0)\n        {\n            toproc.put(v);\n        }\n        else\n        {\n            fprintf(stderr, \"Skipping pair: both input images must decode successfully\\n\");\n        }"
if old not in s:
    raise SystemExit("Expected upstream image-loader block not found; refusing an unsafe patch")
p.write_text(s.replace(old, new, 1))
PY

SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
VULKAN_LIB="$SYSROOT/usr/lib/aarch64-linux-android/24/libvulkan.so"
test -f "$VULKAN_LIB" || { echo "Android NDK Vulkan stub not found: $VULKAN_LIB" >&2; exit 1; }

cmake -S "$SOURCE/src" -B "$BUILD" \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DVulkan_INCLUDE_DIR="$SYSROOT/usr/include" \
  -DVulkan_LIBRARY="$VULKAN_LIB" \
  -DNCNN_VULKAN=ON \
  -DNCNN_VULKAN_ONLINE_SPIRV=ON \
  -DNCNN_BUILD_BENCHMARK=OFF \
  -DNCNN_BUILD_TESTS=OFF \
  -DNCNN_BUILD_TOOLS=OFF \
  -DNCNN_BUILD_EXAMPLES=OFF \
  -DWEBP_BUILD_ANIM_UTILS=OFF \
  -DWEBP_BUILD_CWEBP=OFF \
  -DWEBP_BUILD_DWEBP=OFF \
  -DWEBP_BUILD_GIF2WEBP=OFF \
  -DWEBP_BUILD_IMG2WEBP=OFF \
  -DWEBP_BUILD_VWEBP=OFF \
  -DWEBP_BUILD_WEBPINFO=OFF \
  -DWEBP_BUILD_WEBPMUX=OFF \
  -DWEBP_BUILD_EXTRAS=OFF

cmake --build "$BUILD" --target rife-ncnn-vulkan --parallel "$(nproc)"
BIN="$BUILD/rife-ncnn-vulkan"
test -x "$BIN"
file "$BIN" | grep -E 'ELF 64-bit.*ARM aarch64'
install -m 0755 "$BIN" "$ASSETS/rife-ncnn-vulkan"
install -m 0644 "$SOURCE/models/rife-v4.6/flownet.bin" "$ASSETS/rife-v4.6/flownet.bin"
install -m 0644 "$SOURCE/models/rife-v4.6/flownet.param" "$ASSETS/rife-v4.6/flownet.param"
test -s "$ASSETS/rife-v4.6/flownet.bin"
test -s "$ASSETS/rife-v4.6/flownet.param"
