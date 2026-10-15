# NaatBook — Product Requirements Document

> Living document. Update it whenever scope, priorities, or shipped status change.
> Last updated: 2026-10-08. Status reflects `main` at v1.0.0 (DB schema v4).

## 1. Product overview

**NaatBook** ("My Naat Notebook") is an **offline-first, privacy-focused Android notebook**
for writing, reading, recording, and organizing **Naat, Hamd, and other devotional poetry**.

- **Platform:** Android only (minSdk 24, targetSdk/compileSdk 36)
- **Language/stack:** Kotlin, Jetpack Compose, Hilt (DI), Room (persistence),
  Preferences DataStore (settings), Media3 (audio playback)
- **Package:** `com.aaqib.naatbook` (applicationId — Play Store identity; renamed from
  `com.aistudio.mynaatnotebook.ptwyv` on 2026-10-15 at the owner's order)

## 2. Goals

1. A beautiful, dependable place to keep devotional poetry — text and voice.
2. **Zero network dependency.** The app must be fully usable offline, forever.
3. **Privacy by construction:** no analytics, no ads, no cloud sync, no accounts.
   The user's notebook and voice recordings never leave the device unless the user
   explicitly exports them.

## 3. Non-goals

- No cloud sync, no multi-device support, no sharing/social features.
- No user accounts or sign-in of any kind.
- No server-side components, no push notifications for content.
- `android:allowBackup="false"` — Android Auto Backup is deliberately disabled so the
  OS never silently uploads the database or recordings to Google Drive.

## 4. Feature requirements

### 4.1 Library
- Browse entries grouped by category taxonomy:
  `Naat, Hamd, Manqabat, Salam, Qasida, Nasheed, My Kalam, Others`
- Favorites filter, full-text search, "Recent" ordering by last-edit time.
- Category counts and empty-state guidance.

### 4.2 Editor
- Create/edit entries: title, poet, category, lyrics.
- **Draft autosave** with unsaved-changes detection and a safe discard policy —
  closing the editor must never silently lose work.
- Attach up to **two audio files** per entry (primary + secondary): record in-app
  or import a local file.

### 4.3 Audio recording
- In-app recording (mic) with pause/resume, live recording meter, and elapsed-time
  accounting that survives pauses.
- Recordings stored in app-private storage; orphaned files are cleaned up by the
  audio lifecycle coordinator (no storage leaks).

### 4.4 Playback
- Media3-based playback: in-app preview, background playback, lock-screen controls,
  global mini-player.
- Playback must survive back-navigation, minimize, and screen lock.
- Pause automatically when audio output is disconnected (wired/Bluetooth).

### 4.5 Reader
- Immersive reading mode for lyrics.
- Proper **Nastaliq/RTL** rendering for Urdu text.
- Adjustable font size; light/dark/system theme.

### 4.6 Settings
- Theme mode (system/light/dark), global font size.
- Settings persist via Preferences DataStore (migrated from legacy SharedPreferences).

### 4.7 Backup & restore (user-controlled)
- Export the whole notebook (entries + audio) as a **ZIP archive** to a
  user-chosen location; import it back from a user-chosen file.
- Format v2: JSON manifest + content-hashed audio paths (`audio/<sha256>.<ext>`).
- Import must be **safe against malicious archives**: reject Zip-Slip paths, enforce
  entry-count (10k), JSON-size (16 MB), and total-size (2 GB) budgets.
- Export must be **atomic**: stage and fsync the complete archive before touching
  the destination, so a failure can never truncate a previously valid backup.

## 5. Quality requirements

- **Correctness first:** every DB schema change ships an explicit Room migration;
  `fallbackToDestructiveMigration` is forbidden — a missing migration must fail
  loudly, never erase the notebook.
- **Resource safety:** native resources (MediaRecorder, ExoPlayer) must be released
  on every path, including failure paths.
- **Tests:** focused JVM/Robolectric unit tests cover migrations, DAO correctness,
  backup atomicity, draft policies, and playback policies. CI runs
  `testDebugUnitTest` before every `assembleDebug`.
- **Release builds:** R8 code shrinking + resource shrinking on `debug` (release-like
  test APK) and `release`; narrow, deliberate ProGuard rules.
- **Verification gate:** `./scripts/verify-p4.sh` runs the test suite, builds both
  optimized APKs, checks patch whitespace, and fails if the branch changes
  `.github/workflows/` (immutable CI contract).

## 6. Build & release

- Variants: `dev` (unminified, `.dev` suffix, for Android Studio), `debug`
  (R8-optimized, debug-signed, **not** debuggable — the CI-published test artifact),
  `release` (R8-optimized, signed only when a real upload key is supplied).
- **Signing:** release keystore is provided exclusively via environment variables
  (`ANDROID_SIGNING_KEYSTORE_PATH`, `ANDROID_SIGNING_STORE_PASSWORD`,
  `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`) or GitHub Secrets in
  CI. **Never commit a keystore, password, or R8 mapping file** (enforced by
  `.gitignore`: `*.jks`, `*.keystore`).
- CI (`.github/workflows/`):
  - `build.yml` — builds the optimized debug APK on every push to `main`, on PRs,
    and on manual dispatch; uploads `naatbook-debug-apk`.
  - `release.yml` — manual; decodes the base64 keystore from secrets into
    `RUNNER_TEMP` and publishes a signed release.
  - `strix.yml` — manual AI-assisted security pentest (Strix).

## 7. Shipped status (as of 2026-10-08)

All PRD features above are implemented on `main`:

| Phase | Scope | Status |
|---|---|---|
| P2 | Taxonomy restructure, Nastaliq/RTL + dictation, DataStore settings, MediaSession lock-screen playback, playback-survives-background hotfix | ✅ shipped (PR #1) |
| P3 | Dependency purge, Hilt DI, Navigation Compose, centralized playback, backup format v2, migration tests | ✅ shipped (PR #2) |
| P4a–P4g | Correctness hardening, atomic backup restore, verification tooling, Media3 migration, R8 release-like validation, editor/reader polish, branding | ✅ shipped (PR #3 + follow-ups) |
| Post-P4 | Release signing workflow, Strix pentest workflow, Room schema v4 (recents ordering) | ✅ shipped |

## 8. Known gaps / suggested next steps

1. **Play Store release:** `versionCode` is still 1 / `versionName` 1.0.0 while the DB
   schema is at v4 — bump versions deliberately before the first store upload.
2. **Maintainability:** `NaatViewModel` (~1000 lines) is a god class; consider
   splitting recording/playback/draft responsibilities into dedicated ViewModels.
3. **Package hygiene:** Kotlin namespace is now `com.aistudio.mynaatnotebook`
   (renamed from the `com.example` template leftover, 2026-10-08).
4. **Git history** contains a personal Gmail address in commit metadata (public repo).
   Future commits should use a private/noreply address; rewriting history is
   optional and disruptive — decide explicitly before doing it.
5. Confirm the Strix workflow's LLM API key is sourced from GitHub Secrets, not
   hardcoded.

## 9. Glossary

- **P2/P3/P4** — development phases used in this repo's history (see `AGENTS.md`).
  Lettered sub-phases (P4a…P4g) are incremental hardening/polish milestones.
- **P4 contract** — the immutable CI + verification discipline: `assembleDebug`
  artifact, `verify-p4.sh` gate, no workflow changes without explicit process.
