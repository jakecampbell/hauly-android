<img src="docs/icon.png" alt="Hauly icon" width="96" height="96" />

# Hauly

A fast, dark, offline-first Android app for managing shopping lists and recipes
backed by **Notion** as the database. Notion's mobile app is cumbersome for
quick tasks; Hauly is built for one-handed check-offs in the store aisle, with
edits queued locally and synced to your Notion workspace in the background.

## Tech stack

| Concern              | Choice                                                    |
| -------------------- | --------------------------------------------------------- |
| Language / UI        | Kotlin + Jetpack Compose (Material 3, dark-only theme)    |
| Architecture         | MVVM + Clean Architecture (`di` / `data` / `domain` / `presentation`) |
| Local cache          | Room (every entity carries a `sync_status` column)        |
| Background sync      | WorkManager (`@HiltWorker`, network-constrained)          |
| Networking           | Retrofit + OkHttp + kotlinx.serialization                 |
| DI                   | Hilt                                                      |
| Config storage       | Preferences DataStore (PAT, database IDs, select options) |

## Notion setup

1. Create an [internal integration](https://www.notion.so/my-integrations) and copy its secret.
2. Create two databases and **share both with the integration**:

   **Shopping List** — `Name` (Title), `Store` (Multi-select), `Tags` (Multi-select),
   `Qty` (Number), `Shopped` (Checkbox), `Recipes` (Relation → Recipe DB)

   **Recipe** — `Name` (Title), `Ingredients` (Relation → Shopping List DB),
   `Planned` (Checkbox — flags recipes you intend to make; they surface in a
   "Planned" section above the recipe list). Instructions live as normal page
   content (blocks).

3. On first launch the app validates the token and both schemas and names any
   missing/mismatched property exactly, so you can fix your template.

## How syncing works

- **Reads** are offline-first: the active (unshopped) list and recipe list render
  from Room instantly; pull-to-refresh re-pulls remote truth.
- **Writes** land in Room as `PENDING_CREATE` / `PENDING_UPDATE` and a
  network-constrained WorkManager job flushes them — immediately when online,
  or as soon as the connection returns.
- **Conflict handling**: remote refreshes never overwrite rows with queued
  edits; rows whose push failed permanently are marked `ERROR` and rolled back
  to the remote state on the next refresh.
- **Duplicate prevention**: item names are unique (case-insensitive) locally,
  and the create-sync first checks Notion by name — an existing shopped row is
  reactivated (quantity untouched) instead of duplicated.
- **Cache policy**: only active items are cached. Searching past/shopped items
  queries the Notion API directly and therefore needs a connection.
- **Rate limits**: an OkHttp interceptor injects the `Notion-Version` header on
  every request, and a retry interceptor backs off exponentially (honoring
  `Retry-After`) on `429` and transient `5xx` responses.

## Building

```bash
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # R8-minified release APK (needs your signing config)
```

Requires JDK 17+ and Android SDK 37 (`local.properties` points at the SDK).

## Module map

```
app/src/main/java/com/jakecampbell/hauly/
├── di/            Hilt modules (network, database, repositories, datastore)
├── data/
│   ├── local/     Room entities, DAOs, database (sync_status on every row)
│   ├── remote/    Retrofit API, interceptors, Notion JSON mappers & builders
│   ├── repository/Repository implementations + entity↔domain mappers
│   ├── settings/  DataStore-backed configuration
│   └── sync/      SyncEngine, SyncWorker, SyncScheduler, ConnectivityObserver
├── domain/        Models, repository interfaces, use cases (business rules)
└── presentation/  Compose screens, ViewModels, navigation, theme
```
