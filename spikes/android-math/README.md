# Android math and accessibility spike

This one-screen prototype uses fixed examples from the [illustrative course](../../examples/prime-and-maximal/README.md) and a fraction to exercise fallback. The build is independent of the JVM Gradle modules.

The candidate preview supports the fixture's flat expressions, including `\in` and `\mathbb{Z}`, with native Unicode text. Each example includes a plain-language reading and selectable LaTeX source. Unsupported syntax or a missing symbol glyph produces a diagnostic and leaves the source available. The prototype requires no WebView, package parser, or network permission. [Android selectable text API](https://developer.android.com/reference/android/widget/TextView#setTextIsSelectable(boolean))

## Build and launch

Use JDK 17 or newer, Android SDK Platform 33, and Build Tools 36.1.0. Set `ANDROID_HOME` to the SDK path. A build signs the APK with a temporary key. Use a fresh emulator or uninstall the earlier spike build before installing a newly signed copy.

```sh
bash spikes/android-math/build.sh
adb install build/android-math-spike/android-math-spike.apk
adb shell am start -W -n mg.abela.axiora.mathspike/.MathActivity
```

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

## Candidate for typeset mathematics

Evaluate bundled KaTeX for fractions, superscripts, and subscripts alongside the fixture's membership and integer symbols. KaTeX can emit HTML for visual layout and MathML for assistive technology. Verify speech and focus in Android WebView before relying on that output. Keep the native plain-language reading and selectable source available. [KaTeX rendering options](https://katex.org/docs/options.html)

Run the candidate against a small command allowlist with bounded input and macro expansion. Use only app-bundled assets, disable external navigation and file access, and expose no native JavaScript bridge. Treat imported TeX as text and return to the native fallback if parsing, rendering, or asset loading fails. These are acceptance checks for a future adapter. [Renderer architecture](../../docs/markdown/architecture.md)
