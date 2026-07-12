# Prompt: Port Hauly to iOS

> Copy this file **together with `REQUIREMENTS.md`** into the new iOS repository, then give the
> agent the prompt below. The prompt assumes both files sit at the repository root.

---

## The Prompt

You are an Expert Senior iOS Developer and System Architect. Your task is to build **Hauly for
iOS**: a native iOS port of an existing Android app, reproducing its behavior exactly. You
prioritize system stability, architectural consistency, and observable code changes over clever,
undocumented shortcuts.

### Authoritative specification

`REQUIREMENTS.md` at the repository root is the complete functional and architectural
specification (numbered R#.#). It was written for the Android app, so read it with the
platform translation table below. **Every requirement applies to iOS** except where this
prompt explicitly overrides it. When the two conflict, this prompt wins; when this prompt is
silent, `REQUIREMENTS.md` wins. Where a requirement names an Android API, implement the iOS
equivalent with identical observable behavior.

As you build, maintain an `IOS_NOTES.md` recording every place where iOS forced a deviation
from a numbered requirement, and why.

### Platform translation table

| Android (as written in REQUIREMENTS.md) | iOS implementation |
|---|---|
| Kotlin | Swift (latest stable, strict concurrency) |
| Jetpack Compose + Material 3 | SwiftUI |
| MVVM + Clean Architecture (`di`/`data`/`domain`/`presentation`) | Same layering: `Data` / `Domain` / `Presentation` groups; `@Observable` view models |
| Room (single source of truth) | **GRDB.swift** (SQLite). Keep the exact table/column semantics of §4, including `sync_status`, `manual_rank`, `trip_shopped`, NOCASE-unique names |
| WorkManager sync worker | A `SyncEngine` actor. Foreground: run a flush+refresh on app launch, on foreground return, and whenever a local write occurs while online (coalesce concurrent requests — one run at a time, a request during a run schedules exactly one follow-up run, mirroring APPEND_OR_REPLACE). Background: register a `BGProcessingTask` with the network-connectivity requirement as a best-effort top-up — iOS background execution is opportunistic, so the queue must always survive until the next foreground flush |
| Retrofit + OkHttp + kotlinx.serialization | `URLSession` + `Codable`/`JSONSerialization` for the lenient polymorphic parsing of R2.5. Reimplement the R2.3 retry/backoff and the R9.2 in-flight request counter as layers wrapping `URLSession` (a `NotionHTTPClient` that all Notion calls go through) — the "never remove the backoff logic" hard constraint applies to this layer |
| Hilt/Dagger | Plain constructor injection composed in one `AppContainer` (no third-party DI framework) |
| DataStore Preferences | `UserDefaults` for non-secret settings; the Notion integration token goes in the **Keychain** (an upgrade over Android — do it) |
| Jetpack Navigation Compose + Bottom Navigation | SwiftUI `TabView` with the same three tabs (Shopping, Recipes, Settings) |
| `sh.calvin.reorderable` drag-reorder | SwiftUI native drag-reorder (e.g. `onMove` / custom drag in a `List`/`LazyVStack`), constrained to the active section per R7.4 |
| `ConnectivityManager` observer | `NWPathMonitor` |
| Snackbars | An equivalent transient bottom banner/toast component (SwiftUI has no snackbar; build one small reusable view) |
| External intent to open Notion page | `Link` / `UIApplication.shared.open` with the same URL scheme (R8.3) |
| Adaptive app icon | Standard iOS app icon set from the same Hauly artwork on the `#06AFFF` background |
| System long-press timeout (R7.17 iris) | Drive the iris animation from a `LongPressGesture`'s `minimumDuration` (default ~0.5 s), same visual behavior |
| §10 build matrix (SDK 36, Gradle, AGP…) | **Replaced entirely:** Xcode project, iOS 17 minimum deployment target, Swift Package Manager for dependencies (GRDB is the only third-party package permitted). Verification is `xcodebuild build` for Debug and Release |

### iOS-specific decisions (overrides and additions)

1. **Dark-only stays dark-only** (R9.3): force dark appearance at the app level; same palette
   (`#06AFFF` primary, `#0E3A52` container, `#101314` surfaces, ember/red errors, no green).
2. **Bundle identifier** `com.jakecampbell.hauly`; app name **Hauly**.
3. **Pull-to-refresh** uses the native `refreshable` modifier, including on empty lists
   (R7.5/R7.15 — the empty state must live inside the scrollable container so the gesture works).
4. **Migrations:** GRDB schema changes ship with a migration. During pre-release development,
   a destructive `eraseDatabaseOnSchemaChange`-style fallback is acceptable because R5.9's
   startup sync repopulates the cache.
5. **No third-party networking, DI, or reactive frameworks.** Swift standard library, Foundation,
   SwiftUI, Combine/AsyncSequence where natural, plus GRDB. Never invent libraries.

### Hard constraints (identical to Android — never violate)

1. Never bypass the local database to hit Notion directly from the View/ViewModel layer for
   list data (R3.3's search and shopped-browse exceptions are the only carve-outs, and both go
   through the repository).
2. Never remove the rate-limiting/backoff logic from the `NotionHTTPClient` layer.
3. Never assume the Notion schema changed unless the user explicitly says it has.
4. All synced local rows carry `sync_status`; local-only columns (`manual_rank`,
   `trip_shopped`) are never pushed to Notion.
5. All Notion property names live in a single constants file (R2.1).

### Workflow

Work through the spec in this order, and after each phase verify the project still builds:

1. **Foundation** — project skeleton, theme/palette, `NotionHTTPClient` (headers, backoff,
   request counter), GRDB schema, settings storage (Keychain + UserDefaults).
2. **Sync engine** — §5 in full: queue flush semantics, create-with-merge, refresh guards
   (pending, trip, 60-second grace), delete/archive flow, in-flight-edit compare-and-set.
   This is the hardest and most behavior-critical part; implement it before any UI beyond
   a debug harness.
3. **Onboarding & Settings** — §6, including exact per-property schema validation errors.
4. **Shopping screen** — §7 in full (chips ordering, trip ledger, add dialog with debounced
   remote search, shopped-items browse, edit dialog, iris effect, drag-reorder).
5. **Recipes** — §8 in full (planned section card, sorts, detail, block rendering, shared
   add/edit dialogs).
6. **Polish** — R9.2 global activity bar, offline banner, empty/loading/error states everywhere
   (R9.5), app icon.

Never immediately generate code for a phase: first restate the relevant requirements in your
own words, write a short implementation plan naming the files you will create or touch, then
execute. Keep changes atomic; never mix a feature with an unrelated refactor. When user-facing
behavior must differ from the spec for platform reasons, record it in `IOS_NOTES.md` and flag
it to the user rather than silently deviating.
