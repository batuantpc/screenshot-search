# Contributing to Screenshot Search

Thanks for helping improve Screenshot Search.

## Before opening a pull request

1. Create a branch from `main`.
2. Keep screenshot content, OCR exports, device databases, signing files, and participant
   data out of Git.
3. Run the local verification suite from `android/`:

   ```powershell
   ./gradlew.bat testDebugUnitTest lintDebug assembleDebug
   ```

4. Explain the user-facing change and the evidence used to validate it.
5. Add or update tests for classification, search parsing, or persistence changes.

## Product principles

- Search must work independently of automatic categories.
- Prefer abstaining over showing an unsupported topic.
- Do not infer why a screenshot was saved unless the user explicitly labels it.
- Screenshot and OCR processing should remain on-device by default.
- New models must be measured on native/mobile holdouts before entering the product APK.

## Privacy rules

Never commit:

- `.env` files, API keys, tokens, or credentials;
- Android keystores or `keystore.properties`;
- raw screenshots, OCR dumps, device databases, or UI dumps;
- participant exports or derived private datasets;
- built APK/AAB artifacts.

Use synthetic fixtures for tests. Report security issues through the process in
[SECURITY.md](SECURITY.md), not a public issue.
