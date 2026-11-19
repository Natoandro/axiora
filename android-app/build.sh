#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Set ANDROID_HOME to your Android SDK}"
sdk="$(realpath "$sdk")"
out="$root/build/android-app"
tools="$sdk/build-tools/36.1.0"
platform="$sdk/platforms/android-33/android.jar"
mode=debug
if [[ "${1:-}" == "--release" ]]; then
    mode=release
    shift
fi

cd -- "$root"
./gradlew -PandroidSdk="$sdk" :android-app:stageAndroidRuntime "$@"
mkdir -p "$out"
work="$(mktemp -d "$out/.build-XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT

"$tools/d8" --lib "$platform" --min-api 26 --output "$work" android-app/build/android-runtime/*.jar
bash "$root/android-app/native/build.sh" "$out/package-native"
bash "$root/spikes/android-math/build.sh"
unzip -q "$root/build/android-math-spike/android-math-spike.apk" 'assets/*' 'lib/*' -d "$work"
cp -R "$out/package-native/lib/." "$work/lib/"
"$tools/aapt2" compile -o "$work/resources.zip" \
    "$root/android-app/src/main/res/drawable/ic_axiora.xml" \
    "$root/android-app/src/main/res/drawable/ic_chevron_right.xml"
link_mode=()
[[ "$mode" == debug ]] && link_mode+=(--debug-mode)
"$tools/aapt2" link "${link_mode[@]}" -I "$platform" --manifest android-app/src/main/AndroidManifest.xml \
    -A "$work/assets" -R "$work/resources.zip" -o "$work/unsigned.apk"
(cd "$work" && "$tools/aapt" add unsigned.apk classes*.dex lib/*/*.so)
"$tools/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"

if [[ "$mode" == release ]]; then
    : "${AXIORA_RELEASE_KEYSTORE:?Set AXIORA_RELEASE_KEYSTORE to your local keystore path}"
    : "${AXIORA_RELEASE_STORE_PASSWORD:?Set AXIORA_RELEASE_STORE_PASSWORD}"
    : "${AXIORA_RELEASE_KEY_ALIAS:?Set AXIORA_RELEASE_KEY_ALIAS}"
    : "${AXIORA_RELEASE_KEY_PASSWORD:?Set AXIORA_RELEASE_KEY_PASSWORD}"
    key="$AXIORA_RELEASE_KEYSTORE"
    ks_pass="$AXIORA_RELEASE_STORE_PASSWORD"
    alias="$AXIORA_RELEASE_KEY_ALIAS"
    key_pass="$AXIORA_RELEASE_KEY_PASSWORD"
    artifact="$out/axiora-release.apk"
else
    key="$out/debug.p12"
    if [[ ! -f "$key" ]]; then
        keytool -genkeypair -keystore "$work/debug.p12" -storepass android -alias axiora \
            -dname 'CN=Axiora local study' -keyalg RSA -validity 3650
        ln "$work/debug.p12" "$key" 2>/dev/null || test -f "$key"
    fi
    ks_pass=android
    alias=axiora
    key_pass=android
    artifact="$out/axiora.apk"
fi
"$tools/apksigner" sign --ks "$key" --ks-pass "pass:$ks_pass" --ks-key-alias "$alias" \
    --key-pass "pass:$key_pass" --out "$work/axiora.apk" "$work/aligned.apk"
"$tools/apksigner" verify "$work/axiora.apk"
mv -- "$work/axiora.apk" "$artifact"
printf 'Built %s APK: %s\n' "$mode" "$artifact"
