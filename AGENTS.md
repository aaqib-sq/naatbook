# AGENTS.md — Working guide for AI agents on NaatBook

> Read this first. It tells you what this repo is, how work is done here,
> where things live, and how far the project has come. Keep it updated when
> you change how work is done.

## 1. What this is

NaatBook ("My Naat Notebook") — an **offline-first, privacy-focused Android app**
for writing, reading, recording, and organizing Naat, Hamd, and devotional poetry.
See `PRD.md` for product requirements and shipped status.

**Non-negotiables:**
- Fully offline-capable; no analytics, no accounts. `INTERNET` exists only for user-opted Drive backup/sync — the notebook itself never needs a network.
- `android:allowBackup="false"` — the OS must never auto-upload user data.
- Never commit secrets: no keystores, no passwords, no API keys, no R8 mapping files.

## 2. Tech stack

Kotlin · Jetpack Compose (Material3) · Hilt (DI) · Room (DB, schema v9) ·
Preferences DataStore (settings) · Media3 (playback) · Coroutines/Flow ·
Robolectric + JUnit (tests) · Gradle Kotlin DSL · AGP, compileSdk/targetSdk 36, minSdk 24.

- **applicationId:** `com.aaqib.naatbook` — the Play Store identity (renamed from
  `com.aistudio.mynaatnotebook.ptwyv` on 2026-10-15 at the owner's order; the old
  `aistudio` fragment was an AI Studio scaffold artifact).
- **Kotlin namespace:** `com.aistudio.mynaatnotebook` (renamed from the `com.example`
  template leftover on 2026-10-08).

## 3. Repo map

```
app/src/main/java/com/aistudio/mynaatnotebook/
  MainActivity.kt, NaatBookApplication.kt
  ui/        Compose screens: NaatApp.kt (nav), library/, editor/, reader/, settings/, theme/, components/
  viewmodel/ NaatViewModel.kt (library+editor+recording orchestration),
             EditorDraft.kt (draft data classes), EditorDraftStore.kt,
             EditorDraftDiskStore.kt, DraftFileCleanup.kt, OperationGate.kt,
             SettingsViewModel.kt (theme/font size), BackupViewModel.kt (export/import),
             StatusReporter.kt (shared @Singleton status/toast bus)
  data/      Room: NaatDatabase.kt (migrations 1→9), NaatDao.kt, NaatEntity.kt,
             NaatRepository.kt, BackupManager.kt (ZIP export/import, Zip-Slip guards),
             AudioFileLifecycleCoordinator.kt, SettingsStore.kt, NaatCategories.kt
  audio/     Media3PlaybackEngine.kt, MediaPlaybackService.kt (foreground service),
             PlaybackController.kt, AudioRecorder.kt, NativeResourceSafety.kt, …
  di/        DatabaseModule.kt (Hilt; explicit migrations, NO destructive fallback)
app/src/test/          30 Robolectric/JVM test files (migrations, DAO, backup, drafts, playback)
app/schemas/           Exported Room schemas (v1–v4 checked in; v5–v9 export skipped — needs a local JDK build, runtime-harmless)
app/src/main/AndroidManifest.xml   Minimal permissions; exported components only where required
.github/workflows/    build.yml (CI APK), release.yml (signed release), strix.yml (pentest)
scripts/verify-p4.sh  Verification gate: tests + both APKs + whitespace + workflow-immutability check
assets/branding/      App logo
metadata.json         App metadata (name, description)
PRD.md                Product requirements, quality bars, shipped status, known gaps
```

## 4. How work gets done here

### 4.1 Phase discipline (history)
Work landed in phases with lettered milestones, each as a focused commit series:
- **P2** — taxonomy restructure, Nastaliq/RTL + voice dictation, DataStore settings,
  MediaSession lock-screen playback, background-playback hotfix (PR #1).
- **P3** — dependency purge, Hilt DI, Navigation Compose, centralized playback,
  backup format v2, migration tests (PR #2).
- **P4a–P4g** — correctness hardening, atomic backup restore, verification tooling,
  Media3 migration, R8 release-like validation, editor/reader polish, branding
  (PR #3 + follow-up commits).
- **Post-P4** — release signing workflow, Strix pentest workflow, Room v4 (recents).

Phases were built on `arena/<id>-naatbook` branches and merged via pull requests.
Keep this habit: **one focused branch + PR per unit of work**, descriptive commit
messages (`P4d: migrate playback to Media3` style is fine; also plain summaries).

### 4.2 The P4 contract (still in force)
1. **CI is immutable.** `scripts/verify-p4.sh` fails the build if a branch changes
   anything under `.github/workflows/`. To change CI, say so explicitly and update
   the script's contract deliberately — never sneak it in.
2. **CI publishes `app-debug.apk`** on every push to `main`. That artifact is
   debug-signed but **not debuggable**, R8-optimized: a release-like test build.
   Keep it that way.
3. **Verify before handing off:** run `./scripts/verify-p4.sh` (tests + debug APK +
   release APK + whitespace check). CI also gates `assembleDebug` on
   `testDebugUnitTest`.
4. **Build variants:** `dev` (unminified, `.dev` suffix) for day-to-day Studio work;
   `debug` for the CI test artifact; `release` for production.

### 4.3 Code conventions
- **Database:** every schema change needs an explicit `Migration`. Never add
  `fallbackToDestructiveMigration()` — a missing migration must fail, not erase.
- **Backup format:** current version is **v2** (`FORMAT_VERSION = 2` in
  `BackupManager`). Keep backward-compat handling for older formats when bumping.
- **I/O discipline:** disk work on `Dispatchers.IO`, never on main; file cleanup
  goes through the shared lifecycle coordinator, not ad-hoc deletes.
- **Native resources:** MediaRecorder/ExoPlayer must be released on *every* path,
  including failure paths (see `AudioRecorder`, `NativeResourceSafety`).
- **R8:** keep ProGuard rules narrow and deliberate; the project intentionally
  avoids broad `-keep` rules that hide shrinker regressions.
- **Secrets:** signing config reads env vars (`ANDROID_SIGNING_*`, with
  `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` fallbacks). Local fallback path
  is `<root>/my-upload-key.jks` — `.gitignore` covers `*.jks`/`*.keystore`.

### 4.4 Tests
Add/extend focused tests with behavior changes: migration tests for schema work,
`BackupAtomicityTest`-style tests for backup work, policy tests for playback.
CI runs the full unit suite; keep it green.

## 5. Current status (2026-10-08)

- `main` is at **v1.0.0 (versionCode 1)**, DB schema v9. All PRD features shipped.
- Last feature activity: 2026-09-03 (Strix workflow tuning). The app is in
  maintenance/polish state; next milestone is the **first Play Store release**
  (see PRD §8 for the pre-release checklist).
- 2026-10-08 maintenance: `.gitignore` hardened (`*.jks`, `*.keystore`); added
  `PRD.md` and this guide. (Kotlin namespace rename `com.example` →
  `com.aistudio.mynaatnotebook` prepared as patch, not yet applied.)
- 2026-10-08 refactor: split NaatViewModel — `SettingsViewModel` (theme/font size),
  `BackupViewModel` (export/import), `StatusReporter` (shared status bus), and
  `EditorDraft.kt` (draft data classes) extracted; NaatViewModel keeps the same
  public API so screens are unaffected. Recording/editor remain in NaatViewModel
  deliberately: they share one draft/recording state machine.

## 6. Gotchas for new agents

- **Git history contains the owner's personal Gmail** in commit metadata (public
  repo). Don't "fix" this by rewriting history unless explicitly asked — it's
  disruptive. Use a noreply address for new commits if the owner asks.
- `NaatViewModel.kt` is ~1000 lines and owns too much (library + editor +
  recording). Treat it as legacy-to-split; don't make it bigger without reason.
- `verify-p4.sh` compares against `origin/main` — run it on a branch with `main`
  fetched, or the workflow-immutability check misbehaves.
- The `debug` build type is intentionally `debuggable = false`. If you need a
  debugger, use the `dev` variant.
- `strix.yml` is a manual AI pentest workflow; it needs an LLM API key at runtime
  (expected from Secrets). Don't commit one.
- No `INTERNET` permission exists on purpose. If a feature needs network, that's
  a product decision requiring the owner's explicit approval — flag it, don't just add it.

## 7. Definition of done for a change

1. `verify-p4.sh` passes (or you document exactly why a step was skipped).
2. New behavior has focused tests; full suite green.
3. No secrets/keystores committed; `git diff --check` clean.
4. `PRD.md` / this file updated if scope, status, or workflow changed.
5. Open a PR against `main` with a clear description — don't push to `main` directly.
