#!/usr/bin/env bash
set -euo pipefail

# Build a self-contained ARM64 FFmpeg CLI packaged in Android nativeLibraryDir.
# LGPL build: no external GPL codecs such as x264 are included.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFMPEG_SRC="${ROOT}/.build/ffmpeg"
OUT="${ROOT}/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
API="${ANDROID_API_LEVEL:-26}"

if [[ -z "${NDK}" || ! -d "${NDK}/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2
  exit 1
fi

HOST_TAG=linux-x86_64
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
CC="${TOOLCHAIN}/bin/aarch64-linux-android${API}-clang"
CXX="${TOOLCHAIN}/bin/aarch64-linux-android${API}-clang++"
AR="${TOOLCHAIN}/bin/llvm-ar"
RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
STRIP="${TOOLCHAIN}/bin/llvm-strip"

if [[ ! -d "${FFMPEG_SRC}/.git" ]]; then
  mkdir -p "${ROOT}/.build"
  git clone --depth 1 --branch n7.1.3 https://git.ffmpeg.org/ffmpeg.git "${FFMPEG_SRC}"
fi

cd "${FFMPEG_SRC}"
make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="${ROOT}/.build/ffmpeg-install" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --sysroot="${TOOLCHAIN}/sysroot" \
  --cc="${CC}" \
  --cxx="${CXX}" \
  --ar="${AR}" \
  --ranlib="${RANLIB}" \
  --strip="${STRIP}" \
  --enable-pic \
  --enable-static \
  --disable-shared \
  --disable-everything \
  --enable-ffmpeg \
  --disable-ffplay \
  --disable-ffprobe \
  --enable-avcodec \
  --enable-avformat \
  --enable-avfilter \
  --enable-avutil \
  --enable-swscale \
  --enable-protocol=file \
  --enable-demuxer=image2 \
  --enable-decoder=png \
  --enable-encoder=mpeg4 \
  --enable-muxer=mp4 \
  --enable-filter=minterpolate \
  --enable-filter=zoompan \
  --enable-filter=tpad \
  --enable-filter=trim \
  --enable-filter=setpts \
  --enable-filter=pad \
  --enable-filter=scale \
  --enable-filter=format \
  --disable-network \
  --disable-autodetect \
  --disable-doc \
  --disable-debug \
  --enable-small \
  --enable-zlib \
  --extra-cflags="-O2 -fPIC" \
  --extra-ldflags="-Wl,-z,max-page-size=16384"

make -j"$(nproc)"
mkdir -p "${OUT}"
install -m 0755 ffmpeg "${OUT}/libffmpeg.so"
"${STRIP}" "${OUT}/libffmpeg.so"
# This is a cross-compiled ARM64 Android executable; the x86_64 GitHub runner
# cannot execute it. Verify the generated FFmpeg configuration instead.
grep -qF -- '#define CONFIG_ZOOMPAN_FILTER 1' config_components.h
grep -qF -- '#define CONFIG_MINTERPOLATE_FILTER 1' config_components.h
grep -qF -- '#define CONFIG_PAD_FILTER 1' config_components.h
file "${OUT}/libffmpeg.so"
echo "Verified FFmpeg filters in config_components.h: zoompan, minterpolate, pad"
echo "ASTRA FFmpeg ARM64 ready: ${OUT}/libffmpeg.so"
