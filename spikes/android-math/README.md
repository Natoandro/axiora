# Android math and accessibility spike

This one-screen prototype uses fixed Tier 1 examples alongside formulas from the [illustrative course](../../examples/prime-and-maximal/README.md). The samples include nested fractions and roots, primes and scripts, math alphabets, sized delimiters, inline and display sums, integrals, and logical arrows. The Tier 2 binomial command exercises fallback. The build is independent of the JVM Gradle modules.

Texlet lays out the expressions through JNI. The native adapter translates font glyph IDs into outlines and forwards rules as rectangles. Android draws both with Canvas at the user's scaled text size. Long expressions can scroll horizontally. Each example has a plain-language reading and selectable LaTeX source. Unsupported syntax, a missing glyph, or a renderer startup failure leaves the source available. The APK bundles its font and runs offline. [Android Canvas API](https://developer.android.com/reference/android/graphics/Canvas)

## Build and launch

Use JDK 17 or newer, Android SDK Platform 33, Build Tools 36.1.0, and NDK 29.0.14206865. Set `ANDROID_HOME` to the SDK path. The build uses the NDK's API 26 Clang targets to link Rust and the JNI shim, then packages one shared library per ABI. To use a different NDK installation, set `ANDROID_NDK_HOME`. [Android NDK toolchain guide](https://developer.android.com/ndk/guides/other_build_systems) · [Rust Android targets](https://doc.rust-lang.org/rustc/platform-support/android.html)

```sh
rustup target add i686-linux-android x86_64-linux-android aarch64-linux-android
bash spikes/android-math/build.sh
adb install -r build/android-math-spike/android-math-spike.apk
adb shell am start -W -n mg.abela.axiora.mathspike/.MathActivity
```

For an x86-only build, set `TEXLET_ANDROID_ABIS=x86` and install just the corresponding Rust target. The default includes x86, x86_64, and ARM64. The script looks up TeX Gyre Termes Math via Fontconfig for its serif blackboard-bold glyphs, with Latin Modern Math as its fallback. Set `TEXLET_MATH_FONT` to use an explicit font file and `TEXLET_MATH_FONT_NOTICE` to include its accompanying notice. The script copies the selected default font’s Debian license and README when installed.

Rebuilds reuse the debug key in the build output directory. An APK signed with the old spike's temporary key cannot be updated in place with the new key. Use a fresh emulator for the first install if you want to keep the older APK installed.

## Device checks

| Target | Check |
| --- | --- |
| API 26 narrow phone or emulator | Verify the candidate minimum. Check default and maximum font sizes, wrapping, scrolling, and source selection. |
| API 30 Pixel 3a emulator | Compare the symbols, spoken descriptions, and source against the example. Compare the two sum styles and check that the binomial has a visible fallback. |
| Recent Android phone with TalkBack | Read by heading and line, check pronunciations, and confirm preview, explanation, and source occur in order. Test keyboard focus and source selection separately. |
| Recent Android tablet | Check portrait and landscape at large text sizes and repeat the fallback check in airplane mode. |

Record Android version, viewport, font scale, assistive technology, and the observed result for each run. On API 26–27 the spike relies on text order because the heading API starts at 28. Verify heading navigation separately when building the Compose screen.

## Storage follow-up

Use API 26 as the candidate minimum because the installer uses the Android API 26 `java.nio.file` surface. Path parsing in the installer, validator, and restore now uses `Paths.get`. Audit the remaining calls and dependencies on the candidate minimum before adopting it. [Android file APIs](https://developer.android.com/reference/java/nio/file/Files)

Evaluate framework SQLite via `SQLiteOpenHelper` as the Android driver. Reuse the Phase 4 schema and record codecs where possible. Confirm SQL support on the minimum API and verify foreign-key enforcement, atomic command receipts and checkpoints, migration, rollback, and backup interoperability before wiring the app. Then test a debounced edit and explicit save across force-stop and reopen. [Android SQLiteOpenHelper API](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)

## Results

APK compilation, packaging, and signature verification passed. The original Pixel 3a API 30 AVD stalled before boot. A fresh Pixel 3a AVD using the same API 30 x86 Play Store image completed boot with SwiftShader. The APK installed and launched, and the UI hierarchy contained both expected previews, the fraction fallback diagnostic, and its LaTeX source. See the [implementation record](../../IMPLEMENTATION_STATUS.md#phase-5--android-rendering-and-storage-compatibility) for the separate framework SQLite probe.

On the visible `Axiora_Pixel_3a_API_30` emulator, the long expression wrapped at a 320 dp viewport and 2.0 font scale. Vertical scrolling reached the fraction fallback and its full source. The first two Tab presses focused the membership preview and then its source. The emulator's display and font settings were restored after the check. TalkBack was unavailable on that image.

The Canvas revision was built and signed for x86, x86_64, and ARM64. A side-by-side x86 test APK launched on the API 30 Pixel 3a emulator. The screenshot showed the membership and quotient-ring formulas, the long expression, and readable source under the fraction fallback. The UI hierarchy exposed the readings and source. API 26, ARM64 device rendering, TalkBack, and tablet behavior remain to be checked on this revision.

The Tier 1 APK was built and signature-verified for all three configured ABIs. On the 1080 × 2220 API 30 x86 emulator at font scale 1.0, the side-by-side APK displayed nested structures, glyph styles, delimiters, and different sum limit placements. The UI hierarchy contained the binomial fallback and its source. Large text, TalkBack, API 26, ARM64 device rendering, and tablet layouts remain to be checked on the Tier 1 revision.

## Notation follow-up

See the [notation roadmap](../../crates/texlet/ROADMAP.md) for supported syntax and future tiers. Before using the adapter with imported lessons, check the initial allowlist, bounded input, and fallback against the renderer contract. [Renderer architecture](../../docs/markdown/architecture.md)
