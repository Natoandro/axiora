# Android learner storage

This module adapts the shared learner store to framework SQLite. The store owns the schema, migrations, command receipts, and backup format. The driver owns the database connection and immediate transactions. Open one driver per app-private learner database, use it on a background thread, and close it when the profile closes.

## Run the instrumentation probe

Use JDK 17 or newer, Android SDK Platform 33, and Build Tools 36.1.0. Set `ANDROID_HOME` to the SDK path and select an emulator or test device using `ANDROID_SERIAL` if more than one is connected.

```sh
bash android-storage/build-probe.sh
adb install -r build/android-storage-probe/probe.apk
probe=mg.abela.axiora.sqliteadapterprobe/mg.abela.axiora.storage.android.StorageProbe
adb shell am instrument -w -e mode commit "$probe"
adb shell am instrument -w -e mode interrupt "$probe"
adb shell am force-stop mg.abela.axiora.sqliteadapterprobe
adb shell am instrument -w -e mode verify "$probe"
```

The commit and verify runs must report `PASS`. The interrupt run deliberately kills its process inside an uncommitted draft transaction. Confirm the `AxioraStorageProbe` log says it reached that boundary. Verification compares the recovered backup with the last committed backup, replays the saved command, retries the interrupted command, and checks backup restore, foreign-key enforcement, and migration of the frozen schema-1 fixture.

The probe owns its test databases. Each commit run resets its seed database. The APK has no study screens and requests no network permission. API 26 and large-record reads through Android's cursor window still need coverage.
