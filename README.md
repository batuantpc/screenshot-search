# Project Memento

Open-source Android-first MVP that turns a local screenshot library into searchable memory.

> `Project Memento` is an internal codename, not an approved store name or final package
> identity.

## Product contract

Memento discovers Android screenshots, runs on-device OCR, and lets the user find an old
screenshot with any remembered word or a date phrase.

The product is deliberately search-first:

1. Discover screenshots through Android MediaStore.
2. Run bundled on-device OCR and build a local index from title, source, and OCR text.
3. Search with free text or Turkish date phrases.
4. Suggest a topic only when explicit evidence supports it; otherwise abstain.
5. Let the user add or correct a topic and an optional reason-for-saving label.

Automatic topic suggestions are helper metadata, not the retrieval boundary. Memento does
not infer why a screenshot was saved from its contents.

## Current status — 0.2.0

- Signed debug and R8-shrunk release APKs build successfully.
- 92/92 JVM tests pass; Android Lint reports 0 errors.
- The 0.2.0 release was installed over 0.1.2 on a Galaxy A23 / Android 14 with all 19
  indexed screenshots preserved.
- Direct `ayarlar` search and combined `bugün ayarlar` date/text search returned the one
  correct screenshot on-device.
- Natural-language topic words no longer become hidden exact-category filters.
- Instagram, Reddit, YouTube, and generic visual labels do not create collections by
  themselves.
- Automatic reason-for-saving inference is disabled; manual user choices remain persistent.
- The failed n-gram model and correction propagation were removed from the runtime path.
  Benchmark model artifacts are generated locally, ignored by Git, and never packaged in
  the APK.
- On the fixed 300-screen native benchmark, automatic suggestion coverage is intentionally
  low at 6.3%; suggestion precision is 68.4% and the `Other` false-positive rate is 1.0%.
  A 19-item device set reached 9/9 suggestion precision, but it is too small for a general
  quality claim.

## Privacy

- The app does not request `INTERNET`.
- Screenshots, OCR text, the search index, and labels stay on-device.
- Android backup is disabled.
- Raw participant/device data, signing material, APKs, and private evaluation data are
  excluded from Git.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before sending
a change and use [SECURITY.md](SECURITY.md) for vulnerability reports.

## Repository map

- `android/` — Kotlin/Jetpack Compose Android application
- `outputs/` — product decisions, research, and release evidence
- `tools/` — data-minimization and offline benchmark utilities
- `.private-data/` — ignored local device/evaluation material
- `work/` — early prototype material

See [PROJE_DURUMU.md](PROJE_DURUMU.md) for the canonical status, evidence, risks, and next
exit criteria. Android build and verification instructions are in
[android/README.md](android/README.md).

Released under the [MIT License](LICENSE).
