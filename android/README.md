# Screenshot Search Android

Android-first, local screenshot search MVP.

## Implemented

- Full/partial photo access handling for Android 13+ and Android 8–12 compatibility.
- OEM-friendly MediaStore screenshot discovery and best-effort ContentObserver refresh.
- SQLite-backed local metadata and search index.
- Bundled on-device ML Kit OCR with line-level layout.
- Search across extracted title, source hint, OCR text, and Turkish date phrases.
- High-precision topic suggestions with explicit abstention (`Kategorisiz`).
- Optional, persistent user topic and reason-for-saving labels.
- Sensitive-content masking, status actions, fullscreen zoom, and undo.
- 60-item keyset pagination and opt-in local weekly resurfacing.
- Compose onboarding, home, search, collection, detail, loading, and empty states.

## Product boundaries

- Topic suggestions do not control free-text retrieval.
- Source/format names such as Instagram, Reddit, and YouTube are not semantic collections.
- Generic image labels alone cannot open a collection.
- The app does not automatically infer why a screenshot was saved.
- No account, backend, cloud sync, iOS client, or runtime embedding model is included.
- The app has no `INTERNET` permission.

## Toolchain

- Android Gradle Plugin 9.2.1
- Gradle 9.4.1
- compileSdk 37 / targetSdk 37 / minSdk 26
- Kotlin 2.3.21 with Compose
- Compose BOM 2026.06.00
- ML Kit bundled Text Recognition 16.0.1
- WorkManager 2.11.2

Android SDK Platform 37 and a JDK 21 are expected locally.

Run the JVM, build, and lint checks:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Do not run `connectedDebugAndroidTest` against the development phone while a signed release
with valuable local data is installed: the debug signing certificate is different. Use an
AVD for instrumented database tests.

## Latest verification — 28 July 2026

- Version 0.2.0 / versionCode 4.
- 92/92 JVM tests.
- Lint: 0 errors, 3 dependency/tool version warnings.
- Signed, shrunk release APK: approximately 44.64 MB.
- Galaxy A23 / Android 14 upgrade from 0.1.2 preserved 19/19 records.
- Cold launch: 1,753 ms.
- `ayarlar` and `bugün ayarlar`: one correct result.
- Film collection: one tagged record.
- Earlier AVD tests cover schema creation, v8→v10 migration, OCR retry lifecycle, and
  complete/unique traversal of 1,205 records.

The canonical product and technical status is [../PROJE_DURUMU.md](../PROJE_DURUMU.md).

Contributions are welcome under the repository's MIT license. Never commit local
`keystore.properties`, signing keys, raw screenshots, participant exports, APKs, or
`.private-data/`.
