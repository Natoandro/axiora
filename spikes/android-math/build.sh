#!/usr/bin/env bash
set -euo pipefail

out="$(realpath -m "${1:-$(dirname -- "${BASH_SOURCE[0]}")/../../build/android-math-spike}")"
spike_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
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
tools="$sdk/build-tools/36.1.0"
platform="$sdk/platforms/android-33/android.jar"
font="${TEXLET_MATH_FONT:-}"
if [[ -z "$font" ]] && command -v fc-match >/dev/null; then
    match=$(fc-match -f '%{family[0]}\n%{file}' 'Latin Modern Math')
    if [[ "$match" == "Latin Modern Math"$'\n'* ]]; then
        font=${match#*$'\n'}
    fi
fi
if [[ ! -r "$font" ]]; then
    echo "Install Latin Modern Math or set TEXLET_MATH_FONT to a local OpenType font." >&2
    exit 2
fi
if ! command -v cargo >/dev/null && [[ -x "$HOME/.cargo/bin/cargo" ]]; then
    export PATH="$HOME/.cargo/bin:$PATH"
fi
mkdir -p "$out"
work="$(mktemp -d "$out/.build-XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/assets" "$work/classes"
cp -- "$font" "$work/assets/math.otf"
if [[ -n "${TEXLET_MATH_FONT_NOTICE:-}" ]]; then
    cp -- "$TEXLET_MATH_FONT_NOTICE" "$work/assets/math-font-notice.txt"
elif [[ -z "${TEXLET_MATH_FONT:-}" ]]; then
    notice=/usr/share/texmf/doc/fonts/lm-math
    for file in GUST-FONT-LICENSE.txt README-Latin-Modern-Math.txt; do
        if [[ -r "$notice/$file" ]]; then
            cp -- "$notice/$file" "$work/assets/"
        fi
    done
fi

cd -- "$spike_dir"
min_api=26
native_entries=()
read -r -a abis <<< "${TEXLET_ANDROID_ABIS:-x86 x86_64 arm64-v8a}"
if (( ${#abis[@]} == 0 )); then
    echo "Select at least one Android ABI." >&2
    exit 2
fi
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
        --manifest-path native/Cargo.toml
    entry="lib/$abi/libtexlet_canvas.so"
    mkdir -p "$work/lib/$abi"
    "$clang" -shared -O2 -Wall -Wextra -Werror -fvisibility=hidden \
        -Wl,--exclude-libs,ALL -Wl,--no-undefined -Wl,-soname,libtexlet_canvas.so \
        native/jni.c "$out/cargo/$target/release/libtexlet_android_spike.a" \
        -ldl -lm -llog -o "$work/$entry"
    "$toolchain/llvm-strip" --strip-unneeded "$work/$entry"
    native_entries+=("$entry")
done

javac --release 8 -encoding UTF-8 -classpath "$platform" -d "$work/classes" \
    MathActivity.java MathCanvas.java
jar cf "$work/classes.jar" -C "$work/classes" .
"$tools/d8" --lib "$platform" --min-api "$min_api" --output "$work" "$work/classes.jar"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml \
    -A "$work/assets" -o "$work/unsigned.apk"
(cd "$work" && "$tools/aapt" add unsigned.apk classes.dex "${native_entries[@]}")
"$tools/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"

key="$out/debug.p12"
if [[ ! -f "$key" ]]; then
    keytool -genkeypair -keystore "$work/debug.p12" -storepass android -alias spike \
        -dname 'CN=Axiora local spike' -keyalg RSA -validity 3650
    ln "$work/debug.p12" "$key" 2>/dev/null || test -f "$key"
fi
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android \
    --out "$work/signed.apk" "$work/aligned.apk"
"$tools/apksigner" verify "$work/signed.apk"
mv -- "$work/signed.apk" "$out/android-math-spike.apk"
