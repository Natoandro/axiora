#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$(realpath -m "${1:-$root/build/android-app/package-native}")"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Set ANDROID_HOME to your Android SDK}"
sdk="$(realpath -m "$sdk")"
ndk="$(realpath -m "${ANDROID_NDK_HOME:-$sdk/ndk/29.0.14206865}")"
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Use a Linux or macOS NDK host." >&2; exit 2 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
min_api=26
read -r -a abis <<< "${TEXLET_ANDROID_ABIS:-x86 x86_64 arm64-v8a}"
if (( ${#abis[@]} == 0 )); then
    echo "Select at least one Android ABI." >&2
    exit 2
fi
mkdir -p "$out/lib"
for abi in "${abis[@]}"; do
    case "$abi" in
        x86) target=i686-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
        arm64-v8a) target=aarch64-linux-android ;;
        *) echo "Unsupported Android ABI: $abi" >&2; exit 2 ;;
    esac
    clang="$toolchain/$target$min_api-clang"
    if [[ ! -x "$clang" ]]; then
        printf 'NDK compiler not found: %s\n' "$clang" >&2
        exit 2
    fi
    cargo build --locked --release --target "$target" --target-dir "$out/cargo" \
        --manifest-path "$root/crates/axiora-package-android/Cargo.toml"
    mkdir -p "$out/lib/$abi"
    "$clang" -shared -O2 -Wall -Wextra -Werror -fvisibility=hidden \
        -Wl,--exclude-libs,ALL -Wl,--no-undefined -Wl,-soname,libaxiora_package.so \
        "$root/android-app/native/jni.c" \
        "$out/cargo/$target/release/libaxiora_package_android.a" \
        -ldl -lm -llog -o "$out/lib/$abi/libaxiora_package.so"
    "$toolchain/llvm-strip" --strip-unneeded "$out/lib/$abi/libaxiora_package.so"
done
