#!/usr/bin/env bash
set -euo pipefail

out="$(realpath -m "${1:-$(dirname -- "${BASH_SOURCE[0]}")/../../build/android-math-spike}")"
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Set ANDROID_HOME to your Android SDK}"
tools="$sdk/build-tools/36.1.0"
platform="$sdk/platforms/android-33/android.jar"
mkdir -p "$out"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

javac --release 8 -encoding UTF-8 -classpath "$platform" -d "$work" MathActivity.java
"$tools/d8" --lib "$platform" --min-api 26 --output "$work" \
    "$work/mg/abela/axiora/mathspike/MathActivity.class"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml -o "$work/unsigned.apk"
(cd "$work" && "$tools/aapt" add unsigned.apk classes.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/debug.p12" -storepass android -alias spike \
    -dname 'CN=Axiora local spike' -keyalg RSA -validity 30
"$tools/apksigner" sign --ks "$work/debug.p12" --ks-pass pass:android \
    --out "$out/android-math-spike.apk" "$work/aligned.apk"
"$tools/apksigner" verify "$out/android-math-spike.apk"
