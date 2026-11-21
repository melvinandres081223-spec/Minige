# Minige (`com.melvin.minige`) — Project Rulebook

This document defines mandatory architectural, business, UI/UX, performance, and CI/CD constraints for the **Minige** Android app. Every AI session working in this repository MUST read and obey these rules without exception.

---

## 1. Locked Core & CI/CD Files (NEVER Delete, Break, or Accidentally Refactor)

Do **not** delete, break, or regress any of the following files or their locked configurations:

- **`.github/workflows/build-apk.yml`**
  - Keep `assembleDebug` build steps, keystore/Google Services setup, artifact upload, and automatic GitHub Release publishing (`permissions: contents: write`, `softprops/action-gh-release@v2` on `push` to `main`, `v1.0.${{ github.run_number }}` with `make_latest: true` and `app/build/outputs/apk/debug/app-debug.apk` attached) completely intact.
- **`app/build.gradle.kts`**
  - Keep dynamic CI versioning (`versionCode` and `versionName` driven by `GITHUB_RUN_NUMBER`), `buildFeatures { buildConfig = true }`, existing `signingConfigs`, and the locked GitHub Releases `BuildConfig` fields (`GITHUB_RELEASE_API`, `GITHUB_RELEASE_FALLBACK_API`, `GITHUB_APK_URL`) intact.
- **`app/src/main/java/com/melvin/minige/sync/GitHubAppUpdater.kt`**
  - Preserve version tag comparison (`v1.0.<run_number>` against `BuildConfig.VERSION_CODE` and `BuildConfig.VERSION_NAME`), `/releases/latest` + fallback resolution, required GitHub headers (`User-Agent: Minige-Android-Updater`, `Accept: application/vnd.github+json`), and in-app APK download/install flow (`REQUEST_INSTALL_PACKAGES` + `FileProvider`).
- **`app/src/main/java/com/melvin/minige/auth/GoogleAuthManager.kt`**
  - Preserve Firebase Google Sign-In and user session handling.
- **`app/src/main/java/com/melvin/minige/sync/CalendarSyncManager.kt`**
  - Preserve Google Calendar API OAuth consent and daily event sync.
- **`app/src/main/java/com/melvin/minige/data/PricingRepository.kt`**
  - Preserve per-customer custom `lastUnitPrice` lookup and persistence.
- **`app/google-services.json` & Firebase schema/rules (`firebase-blueprint.json`, `firestore.rules`)**
  - Preserve existing Firebase and Room offline-first sync schemas intact.

---

## 2. Locked Business & Workflow Rules

- **Customer Classification:** Maintain explicit classification between **`Outlet`** (regular canteen resellers) and **`Residential`** (all other customers).
- **Variable Commission Calculation:**
  - Commission uses chronological rate history from `CommissionRateHistory.kt` (currently ₱8/gal default) so past dates keep their historical rates unchanged when future rates are scheduled.
  - Commission applies strictly to **paid** gallons across **Cash**, **Bank Transfer**, and **Bank Deposit** channels (plus settled/partial-paid `Pautang` gallons on the payment/settlement date).
  - Unpaid `Pautang` earns **₱0** commission until paid; `Na-pick up` (`PICK_UP`) entries always earn **₱0** commission and ₱0 remittance.
- **Boss-Facing 1-Tap Messenger `Daily Hakot` Message:**
  - Must contain **ONLY** picked-up gallon totals (`PICK_UP` entries) in clean monospace alignment.
  - Strictly **exclude** commission figures, cash remittance totals, sukli, bank totals, pautang balances, and route expense deductions.
- **Container, Sukli & Pricing Policy:**
  - **No gallon lending** policy.
  - Customer change (**sukli**) is retained personally and tracked separately from Boss remittance calculations.
  - Preserve **per-customer custom pricing** auto-fill and updates via `PricingRepository.kt` and `CustomerDirectoryHelper.kt`.

---

## 3. Locked UI, Ergonomics & Performance Standards

- **Anti-Clutter Modular UI:**
  - Keep `TodayScreen.kt` (and all screens) clean, spacious, compact, and scannable with one hand.
  - Put secondary breakdowns, guides, or heavy details into **modal bottom sheets**, **slim progress/status bars**, or **secondary tabs** rather than bulky permanent cards.
  - Put new logic and UI components in dedicated modular helper/component files (`util/`, `ui/components/`) with minimal hooks in screen composables.
- **Right-Thumb Ergonomics & Safety:**
  - Keep primary actions, FABs, filter chips, suggestion bars, and action pills easily reachable by the right thumb, with at least `48.dp` touch targets and clear `testTag` / `contentDescription` values.
  - Preserve the 5-second `UndoActionBanner`, compact `?` Taglish `ScreenGuideSheet` + long-press tooltips, and top-bar `SyncStatusPill`.
- **Keyboard Safety & Zero-Lag Performance:**
  - Keep search/filter controls (`DeliveryFilterBar`) keyboard-safe (`Modifier.imePadding()`, `BringIntoViewRequester`, focus-triggered scrolling, and `stickyHeader` pinning on `TodayScreen.kt` and `PautangScreen.kt`).
  - Maintain locked maximum display refresh rate (`DisplayRefreshRateHelper.kt` hooked in `MainActivity.kt`), `@Immutable` state/domain models, stable `key` + `contentType` on all `LazyColumn`/`LazyRow` blocks, `remember` + `derivedStateOf` memoization, debounced search/autocomplete, and off-main-thread indexing/computations (`Dispatchers.Default`).
