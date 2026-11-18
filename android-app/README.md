# Axiora Android study app

This local debug APK reuses the package reader, study engine, learner repository and framework SQLite driver. It uses native Android views, the system document picker and the shared Texlet Canvas renderer. The manifest declares no internet or shared-storage permissions, and Android automatic backup is disabled. Learner backup and restore are explicit document-picker operations.

## Build and install

Use JDK 17 or newer, Android SDK platform 33, Build Tools 36.1.0, and the NDK and Rust targets listed in the [math renderer instructions](../spikes/android-math/README.md). The build also needs unzip and a local math font with its notice. On a machine with populated dependency caches, Gradle and Cargo can run offline.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
bash android-app/build.sh
./gradlew :compiler-cli:installDist
python3 android-app/prepare-demo.py
adb install -r build/android-app/axiora.apk
adb push build/android-demo/axiora-study-demo.axp build/android-demo/axiora-study-update.axp /sdcard/Download/
```

The minimum declared version is Android 8.0 (API 26). The signed build includes x86, x86_64 and ARM64 libraries. See the [verification record](../IMPLEMENTATION_STATUS.md#phase-5--android-rendering-and-storage-compatibility) for tested devices and open checks. The debug signature is for local installation.

## Reproduce the study loop

1. Use a disposable emulator profile. Enable airplane mode and turn off Wi-Fi. In Axiora, choose **Import course**, open Downloads and select `axiora-study-demo.axp`. Confirm the draft review claim and course version 0.2.0, then install.
2. Open **Prime versus maximal ideals** and submit **No** on the first try to schedule a review. Continue through the counterexample to the proof. Type a distinctive draft and wait for **Draft saved on this device**. Run `adb shell am force-stop mg.abela.axiora.app`, then reopen Axiora and select **Continue learning**. Verify that the draft returns.
3. Optionally use **Save and leave** to return to the library, import `axiora-study-update.axp`, then reopen the original work in Study history. The in-progress session displays version 0.2.0 there. The new course version 0.2.1 offers its own unit starts.
4. Resume the proof, open a hint, compare with the solution and mark the rubric. Save the self-review, optionally write a reflection, then finish the unit. The proof history shows an unassessed self-review. To check the first-result branch separately, start a new session, submit **Yes**, retry **No** and continue. The initial error still opens the polynomial check and remediation.
5. On a later local date, use the original revision’s **Start fresh check** action. For a short manual test, advance the date on the disposable emulator after the first session and restore its clock afterward. The polynomial quotient question is available as a fresh family if it has yet to be attempted in that revision. An eligible correct response schedules the next review in three local calendar days.
6. Export a learner backup through the file picker. For empty-profile testing, use a separate emulator profile. Restore the JSON before importing any courses. Confirm that attempt history remains readable and that the app asks for missing revisions. Reimport the original package and resume any unfinished draft. Select the same backup in the populated profile to check refusal.

If an emulator automation tap does not select a picker row, use Tab to enter the file list, arrow keys to focus the file row, and Enter or the D-pad center button to select it.

The demo generator adds a polynomial quotient question to an unpublished derivative of the design fixture. The question allows a first-pass learner who answered **No** to encounter a different assessment family on return. The generated packages use distinct package versions and preserve draft review status.
