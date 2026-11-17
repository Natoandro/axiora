# Android math and accessibility spike

This one-screen prototype uses fixed Tier 0 examples, including formulas from the [illustrative course](../../examples/prime-and-maximal/README.md) and a fraction to exercise fallback. The samples cover membership, blackboard-bold number sets, arithmetic and comparisons, closed intervals, and a long expression. The build is independent of the JVM Gradle modules.

The preview uses Texlet through JNI to lay out the fixture's flat expressions, including `\in` and `\mathbb{Z}`. The native adapter translates font glyph IDs into outlines and returns drawing coordinates. Android draws those with Canvas at the user's scaled text size. Long expressions can scroll horizontally. Each example has a plain-language reading and selectable LaTeX source. Unsupported syntax, a missing glyph, or a renderer startup failure leaves the source available. The APK bundles its font and runs offline. [Android Canvas API](https://developer.android.com/reference/android/graphics/Canvas)

## Build and launch

Use JDK 17 or newer, Android SDK Platform 33, Build Tools 36.1.0, and NDK 29.0.14206865. Set `ANDROID_HOME` to the SDK path. The build uses the NDK's API 26 Clang targets to link Rust and the JNI shim, then packages one shared library per ABI. To use a different NDK installation, set `ANDROID_NDK_HOME`. [Android NDK toolchain guide](https://developer.android.com/ndk/guides/other_build_systems) · [Rust Android targets](https://doc.rust-lang.org/rustc/platform-support/android.html)

```sh
rustup target add i686-linux-android x86_64-linux-android aarch64-linux-android
bash spikes/android-math/build.sh
adb install -r build/android-math-spike/android-math-spike.apk
adb shell am start -W -n mg.abela.axiora.mathspike/.MathActivity
```

For an x86-only build, set `TEXLET_ANDROID_ABIS=x86` and install just the corresponding Rust target. The default includes x86, x86_64, and ARM64. The script finds locally installed Latin Modern Math via Fontconfig. Set `TEXLET_MATH_FONT` to use an explicit font file and `TEXLET_MATH_FONT_NOTICE` to include its accompanying notice. The Debian Latin Modern Math notices are included when the default font is found and those notices are installed.

Rebuilds reuse the debug key in the build output directory. An APK signed with the old spike's temporary key cannot be updated in place with the new key. Use a fresh emulator for the first install if you want to keep the older APK installed.

## Device checks

| Target | Check |
| --- | --- |
| API 26 narrow phone or emulator | Verify the candidate minimum. Check default and maximum font sizes, wrapping, scrolling, and source selection. |
| API 30 Pixel 3a emulator | Compare the symbols, spoken descriptions, and source against the example. Check that the fraction has a visible fallback. |
| Recent Android phone with TalkBack | Read by heading and line, check pronunciations, and confirm preview, explanation, and source occur in order. Test keyboard focus and source selection separately. |
| Recent Android tablet | Check portrait and landscape at large text sizes and repeat the fallback check in airplane mode. |

Record Android version, viewport, font scale, assistive technology, and the observed result for each run. On API 26–27 the spike relies on text order because the heading API starts at 28. Verify heading navigation separately when building the Compose screen.

## Storage follow-up

Use API 26 as the candidate minimum because the installer already uses the Android API 26 `java.nio.file` surface. Audit individual calls before adopting it. For example, the current installer and validator use `Path.of`, which needs an Android-compatible replacement or verified desugaring. [Android file APIs](https://developer.android.com/reference/java/nio/file/Files)

Evaluate framework SQLite via `SQLiteOpenHelper` as the Android driver. Reuse the Phase 4 schema and record codecs where possible. Confirm SQL support on the minimum API and verify foreign-key enforcement, atomic command receipts and checkpoints, migration, rollback, and backup interoperability before wiring the app. Then test a debounced edit and explicit save across force-stop and reopen. [Android SQLiteOpenHelper API](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)

## Results

APK compilation, packaging, and signature verification passed. The original Pixel 3a API 30 AVD stalled before boot. A fresh Pixel 3a AVD using the same API 30 x86 Play Store image completed boot with SwiftShader. The APK installed and launched, and the UI hierarchy contained both expected previews, the fraction fallback diagnostic, and its LaTeX source. TalkBack and Android SQLite behavior remain unverified.

On the visible `Axiora_Pixel_3a_API_30` emulator, the long expression wrapped at a 320 dp viewport and 2.0 font scale. Vertical scrolling reached the fraction fallback and its full source. The first two Tab presses focused the membership preview and then its source. The emulator's display and font settings were restored after the check. TalkBack was unavailable on that image.

The Canvas revision was built and signed for x86, x86_64, and ARM64. A side-by-side x86 test APK launched on the API 30 Pixel 3a emulator. The screenshot showed the membership and quotient-ring formulas, the long expression, and readable source under the fraction fallback. The UI hierarchy exposed the readings and source. API 26, ARM64 device rendering, TalkBack, and tablet behavior remain to be checked on this revision.

## Notation follow-up

Extend Texlet with the structural notation listed in the [notation roadmap](../../crates/texlet/ROADMAP.md). Fractions currently exercise the readable source fallback. Before using the adapter with imported lessons, check the initial allowlist, bounded input, and fallback against the renderer contract. [Renderer architecture](../../docs/markdown/architecture.md)
