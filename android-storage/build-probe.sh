#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Set ANDROID_HOME to your Android SDK}"
sdk="$(realpath "$sdk")"
out="$root/build/android-storage-probe"
tools="$sdk/build-tools/36.1.0"
platform="$sdk/platforms/android-33/android.jar"

cd -- "$root"
./gradlew -PandroidSdk="$sdk" :android-storage:stageAndroidRuntime "$@"
mkdir -p "$out"
work="$(mktemp -d "$out/.build-XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT

"$tools/d8" --lib "$platform" --min-api 26 --output "$work" android-storage/build/android-runtime/*.jar
mkdir "$work/assets"
cp storage/src/test/resources/schema-v1.sql "$work/assets/"
"$tools/aapt2" link --debug-mode -I "$platform" --manifest android-storage/src/probe/AndroidManifest.xml \
    -A "$work/assets" -o "$work/unsigned.apk"
(cd "$work" && "$tools/aapt" add unsigned.apk classes*.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

key="$out/debug.p12"
if [[ ! -f "$key" ]]; then
    keytool -genkeypair -keystore "$work/debug.p12" -storepass android -alias probe \
        -dname 'CN=Axiora local storage probe' -keyalg RSA -validity 3650
    ln "$work/debug.p12" "$key" 2>/dev/null || test -f "$key"
fi
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android --out "$work/probe.apk" "$work/aligned.apk"
"$tools/apksigner" verify "$work/probe.apk"
mv -- "$work/probe.apk" "$out/probe.apk"
