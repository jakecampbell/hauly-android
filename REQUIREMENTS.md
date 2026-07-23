# Hauly — Requirements

Hauly is a native Android app that provides a fast, dark, offline-first mobile UI for
shopping lists and recipes, with **Notion as the backing database**. This document is the
complete functional and architectural specification: an implementation that satisfies every
requirement below reproduces the app's behavior exactly.

---

## 1. Product Overview

- **R1.1** The app manages a shopping list and a read-mostly recipe collection, both stored
  in the user's own Notion workspace (two Notion databases the user provides).
- **R1.2** The app must be fully usable offline for day-to-day shopping. All reads come from
  a local cache; all writes are queued locally and flushed to Notion when connectivity allows.
- **R1.3** The UI is minimalist, dark-only, and optimized for one-handed use (bottom
  navigation, large touch targets).
- **R1.4** Package name: `com.jakecampbell.hauly`. App name: **Hauly**.

---

## 2. Notion Backend Contract

### 2.1 Shopping List database (required properties)

| Property  | Notion type   | Purpose |
|-----------|---------------|---------|
| `Name`    | title         | Item name (unique, case-insensitive) |
| `Store`   | multi_select  | Stores where the item is bought |
| `Tags`    | multi_select  | Free-form categorization |
| `Qty`     | number        | Needed quantity (may be empty) |
| `Shopped` | checkbox      | Checked = bought / not on the active list |
| `Recipes` | relation      | Links to the Recipe database |

### 2.2 Recipe database (required properties)

| Property       | Notion type | Purpose |
|----------------|-------------|---------|
| `Name`         | title       | Recipe name |
| `Shopping`     | relation    | Links to Shopping List items |
| `Planned`      | checkbox    | Checked = the user plans to make this recipe |
| `Ingredients`  | rich_text   | Editable ingredient list text (one item per line) |
| `Instructions` | rich_text   | Editable instruction text (one step per line) |
| `URL`          | url         | Editable source link to the recipe |

### 2.3 API rules

- **R2.1** All property names must live in a single source-of-truth constants file (no string
  literals scattered through the code).
- **R2.2** Every Notion request must send the `Notion-Version: 2022-06-28` header and a
  `Bearer` token from the stored integration secret. Base URL: `https://api.notion.com/v1/`.
- **R2.3** HTTP 429 (and transient 5xx) responses must be retried with exponential backoff
  plus jitter, honoring a `Retry-After` header when present, up to 5 attempts. This retry
  logic lives in an OkHttp interceptor and must never be removed.
- **R2.4** Every paginated endpoint (database queries, search, block children) must follow
  `has_more` / `next_cursor` to exhaustion.
- **R2.5** Notion's polymorphic JSON is parsed leniently (generic JSON object navigation with
  null-safe mappers), not with a brittle full DTO tree. Unknown block types must not crash
  rendering.
- **R2.6** Debug builds may log HTTP traffic but must redact the `Authorization` header.
- **R2.7** The app must never assume the Notion schema changed unless the user says so;
  schema mismatches are reported, not "fixed".
- **R2.8** Known Notion quirk that must be handled: the query index lags behind patches it
  just accepted. A refresh immediately after a write can return pre-write state (see R5.8).

### 2.4 Hauly extraction backend (recipe extraction service)

A second, non-Notion remote: the `hauly-backend` web service extracts a structured recipe
from an arbitrary blob of pasted text (R8.15–R8.17). It gets its **own OkHttp client and
Retrofit instance** — the Notion interceptors (headers, activity tracking) must never apply
to it, and the Notion PAT must never be sent to it.

- **R2.9** The backend base URL is a compile-time `BuildConfig` constant
  (`HAULY_BACKEND_BASE_URL`). Every request sends `Authorization: Bearer <beta token>`,
  where the beta token is **user-entered** (R6.6) and stored in DataStore — never
  hard-coded. Transient 429/5xx responses reuse the R2.3 backoff interceptor (fewer
  attempts, so a poll tick fails fast). Debug HTTP logging redacts `Authorization` (R2.6).
  The global network-activity bar (R9.2) stays scoped to Notion traffic: backend polling has
  its own indicator (R8.16) and must not drive the bar.
- **R2.10** Endpoint contract: `POST /api/v1/recipes/extract` with body
  `{"content": "<text>"}` (1–100,000 chars) returns 202 with
  `{"extraction_id": "<uuid>", "status": "pending"}`. A second submit route,
  `POST /api/v1/recipes/extract/magic`, takes the **same** request body and returns
  the same 202+id shape; it builds a recipe from a typed free-text blob (R8.15)
  rather than pasted source. Both routes are polled through the same status
  endpoint below, and which route created a job is persisted (R4.8) so its Retry
  (R5.13) resubmits to the same route.
  `GET /api/v1/recipes/extractions/{id}` returns
  `{"status": "pending"|"processing"|"completed"|"no_recipe"|"failed", "recipe"?: {"title",
  "ingredients", "instructions"}, "error"?: "<reason>"}` with null fields omitted.
  `no_recipe` is terminal: the service judged the text not to be a recipe, with the
  explanation in `error` (no `recipe` payload). `ingredients`/`instructions` are
  newline-separated strings — the same format as R8.7.
  Errors: 401 invalid/missing beta token, 404 unknown extraction id, 422 malformed id.
  Recommended poll cadence is 1–2 seconds. **Forward compatibility:** a status value or
  response body this client doesn't recognize must resolve the extraction as `FAILED`
  (R5.13) — never leave it polling/pulsing forever.
- **R2.11** Unlike Notion's lenient JSON (R2.5 is Notion-specific), this API is a contract we
  own and is parsed with typed `@Serializable` DTOs (unknown keys ignored).

---

## 3. Architecture

- **R3.1** Kotlin, Jetpack Compose, MVVM + Clean Architecture with distinct `di`, `data`
  (local / remote / repository / settings / sync), `domain` (model / repository interfaces /
  usecase), and `presentation` (per-feature screens + ViewModels) packages.
- **R3.2** Libraries: Room, WorkManager, Retrofit + OkHttp, kotlinx.serialization, Hilt/Dagger,
  DataStore Preferences, Jetpack Navigation Compose, `sh.calvin.reorderable` for drag-reorder.
  No invented or non-mainstream libraries beyond these.
- **R3.3** **Room is the single source of truth.** The UI and ViewModels never call the Notion
  API directly for list data; data flows Remote → Room → UI. (Deliberate exceptions, both
  suggestion-only surfaces routed through the repository: the add-dialog's remote *search*
  leg (R7.9) and the shopped-items browse (R7.12) — acting on a suggestion still writes
  through Room.)
- **R3.4** Every synced Room entity carries a `sync_status` field with values
  `SYNCED`, `PENDING_CREATE`, `PENDING_UPDATE`, `PENDING_DELETE`, `ERROR`.
- **R3.5** Repository interfaces live in `domain`; implementations in `data`. Multi-step
  business rules (add item, add ingredient) are use-case classes.
- **R3.6** Settings (integration token, both database IDs, configured flag, cached
  store/tag select options, last-sync timestamp, per-store last-shopped map, manual store
  chip order, group-by-tag view flag, recipe list sort) are stored in DataStore Preferences.

---

## 4. Local Cache (Room)

- **R4.1** `shopping_items` table: local UUID primary key `local_id`; nullable unique
  `remote_id` (Notion page id); `name` unique with `NOCASE` collation (duplicate prevention);
  `stores` and `tags` as JSON-converted string lists; nullable `quantity` (Double);
  `shopped`; `sync_status`; `updated_at` (epoch millis); plus three **local-only** columns that
  bypass sync entirely: `manual_rank` (nullable drag position), `trip_shopped`
  (current-trip ledger flag), and `shopped_assumed`. The last means the row's `shopped` value
  is a placeholder it assumed, not one the user asserted — set only by a recipe add of an item
  that isn't cached and wasn't picked from a suggestion (R8.5); it is only ever read while the
  row is `PENDING_CREATE` (which implies `remote_id IS NULL`), the create-with-merge flush
  resolves it (R5.4), and any explicit shopped write clears it.
- **R4.2** `recipes` table: Notion page id as primary key, `name`, `ingredients` and
  `instructions` (newline-separated text from the `Ingredients`/`Instructions` rich_text
  properties), `url` (the `URL` source link), `planned`, `sync_status`, `updated_at`, and
  `last_edited_at` (Notion's
  `last_edited_time` as epoch millis, used by the "Recent" sort; locally approximated to "now"
  when Planned is toggled or content is edited so the sort reflects it before the next refresh).
- **R4.3** `recipe_blocks` table: cached page content per recipe (recipe id, order index,
  block type, text payload), replaced wholesale on each detail fetch. This is the recipe's
  legacy Notion page **body** and is shown read-only under an "Additional" heading (R8.4).
- **R4.4** `recipe_item_refs` cross-ref table linking recipes to shopping items (recipe id +
  item `local_id`).
- **R4.7** `recipe_line_marks` table: **local-only** per-line "where am I" strikes for a
  recipe's ingredient/instruction sections (recipe id + section + line index). Never synced to
  Notion (like `manual_rank` / `trip_shopped`), cascade-deleted with the recipe, and cleared
  for a section whenever that section's text is edited (line positions shift).
- **R4.5** The shopping cache holds **active (unshopped) items plus every item linked to a
  recipe** (shopped or not — ingredient lists must always show their items with shopped
  state), fetched with a single `or` filter (Shopped = false ∨ Recipes not empty). Rows
  checked off during the current trip (`trip_shopped = 1`) also stay cached until the trip
  ends; ending a trip evicts synced trip rows **except recipe-linked ones**. Shopped items
  with no recipe link live only in Notion and are reachable via remote search and the
  shopped-items browse.
- **R4.6** Active-list ordering: manually ranked rows first in rank order, then the rest
  alphabetically (case-insensitive). This is the ungrouped order; the group-by-tag view
  (R7.21) replaces it with alphabetical sections and ignores `manual_rank` entirely.
- **R4.8** `recipe_extractions` table: **device-local** extraction jobs on the hauly-backend
  (R8.15–R8.17), never pushed to Notion. Columns: extraction id (primary key — the server's
  uuid, except while `SUBMITTING`, when it is a client-generated `local-` placeholder id
  swapped for the server's once the POST returns),
  `source_text` (the full submitted text, kept so a failed extraction can be resubmitted),
  `status` (`SUBMITTING`/`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`), extracted `title`/
  `ingredients`/`instructions` ("" until completed), nullable `error`,
  `endpoint` (`extract` for pasted source / `magic` for free text — which route built the
  job, so Retry resubmits to the same one, R2.10), `created_at`,
  `updated_at`, and
  `sync_status` (constant `SYNCED` — present for entity uniformity only; the sync engine
  ignores this table like `recipe_line_marks`). Added in Room schema **version 9** with an
  explicit additive migration (`MIGRATION_8_9`); `endpoint` added in **version 10**
  (`MIGRATION_9_10`, additive with a `DEFAULT 'extract'` for pre-existing rows).

---

## 5. Sync Engine & Offline Queue

- **R5.1** Local writes mark rows `PENDING_CREATE` / `PENDING_UPDATE` and request a sync.
  Sync runs as a WorkManager worker (Hilt-injected) with a `CONNECTED` network constraint,
  enqueued as unique work (`APPEND_OR_REPLACE`) so requests coalesce.
- **R5.2** A sync pass = flush the pending queue (items, then recipe `Planned` toggles), then
  refresh both caches from Notion.
- **R5.3** Flush semantics per row: 4xx → mark the row `ERROR` (permanent failure; next
  refresh rolls it back to remote truth); IOException or exhausted retries → keep the queue
  intact and return retry so WorkManager backs off and retries.
- **R5.4** **Create-with-merge duplicate prevention:** before creating, search Notion for an
  existing page with the same name (case-insensitive). If found, update that page instead —
  union the stores, tags, and recipe relations; local quantity wins if set, otherwise keep
  remote — and link the local row to the existing page id. For `Shopped`: a `shopped_assumed`
  row (R4.1) **adopts the existing page's `Shopped`** rather than pushing its placeholder — so
  a recipe add of an uncached, unsuggested name never un-shops the page (R8.5); every other row
  pushes the value it asserted (as R7.11's reactivate un-shops a shopped page). The local row
  then converges on that resolved state (`shopped` written back, `shopped_assumed` cleared).
  The **merged recipe relation is also written back to the local refs** when it differs from the
  local set (and the row still exists) — completing R5.10 at the merge site, so the next
  `PENDING_UPDATE` push doesn't drop links this merge preserved.
- **R5.5** Refresh replaces the cache with the remote active-item snapshot, but must **never
  clobber**: rows with `PENDING_*` status, rows with `trip_shopped = 1` (trip rows are
  local-owned until "Done"), and rows updated locally within the last 60 seconds
  (`REMOTE_LAG_GRACE_MILLIS`, guarding against R2.8's stale reads). The same guards apply to
  eviction of rows missing from the snapshot. `manual_rank` is preserved across snapshot
  overwrites.
- **R5.6** Recipe refresh applies the same pending + 60-second-grace protection to `Planned`.
- **R5.7** `ERROR` rows outside those guards are rolled back to remote truth on refresh
  (conflict resolution: remote wins for failed writes).
- **R5.8** Refresh also re-reads the shopping database schema to update the cached Store/Tag
  select options, rebuilds recipe↔item relations for cached items, and records the sync
  timestamp.
- **R5.9** A sync must be scheduled on every app start (when configured) so the cache
  repopulates itself after a destructive database wipe or fresh install.
- **R5.10** **Relation-completeness invariant.** A relation push (both create and update)
  sends the local `recipe_item_refs` set for a row as that page's **complete** `Recipes`
  relation — an empty set clears every link. Therefore any row **materialized locally from a
  remote-only item** (a shopped, evicted item re-added via search or the shopped-items browse,
  or a recipe ingredient add) must **seed its refs from that item's known `recipeIds`** (unioned
  with the recipe being added, for the recipe path) at materialization time. Otherwise the next
  flush, treating the incomplete local set as authoritative, wipes links Notion already had.
  This only bites in the stale-cache window (R4.5 keeps recipe-linked items cached, so a cache
  miss normally means no links): a link added in Notion the cache hasn't refreshed yet. Rows
  that were **already cached** keep the refs a refresh gave them (authoritative — never union a
  remote item's links into them, or a link removed in Notion would be resurrected).
- **R5.10** Connectivity is observed via `ConnectivityManager`; the UI exposes online/offline
  state and the count of pending edits.
- **R5.11** **Deletion** is offline-safe: a deleted item is marked `PENDING_DELETE`, which
  hides it from every read surface (active list, trip ledger, type-ahead, ingredients,
  refresh-overwrite/eviction all skip it) while it waits in the queue. The flush archives the
  Notion page (`archived: true` — Notion has no hard delete; the page is recoverable from the
  Notion trash), then removes the local row and its recipe relations. An item that never
  reached Notion is removed locally with nothing to archive. Re-adding the same name before
  the flush cancels the pending delete (the item simply lives again). If the re-add races an
  in-flight archive call, the local row survives (R5.12) and its next push restores the page:
  item update payloads always send `archived: false` alongside the properties.
- **R5.12** **In-flight edits win over flush write-backs.** Every local write bumps
  `updated_at`; every flush write-back (marking a row `SYNCED` or `ERROR`, applying
  create-with-merge results, completing a delete) is a compare-and-set guarded by the
  `updated_at` the flush read before its network call. A row edited while its own request was
  in flight keeps its newer values and `PENDING_*` status, and the appended sync run (R5.1)
  pushes it. Two fallbacks when the guard fails after a successful **create**: the new page id
  is still attached to the row (flipping `PENDING_CREATE` → `PENDING_UPDATE`, so the retry
  updates instead of re-creating — Notion's lagging query index makes re-running
  create-with-merge unsafe), and if the row was hard-deleted mid-create, the just-created page
  is archived.
- **R5.13** **Extraction polling is not sync-engine work.** Recipe extractions (R8.16) are
  *not* flushed through the WorkManager queue — WorkManager cannot run at the 1–2 s cadence
  the backend expects. Instead an application-scoped in-process loop in the extraction
  repository polls every active `recipe_extractions` row (~2 s cadence) and writes each status
  transition into Room (Room stays the UI's only source, per R3.3). Transient failures
  (offline, 5xx) back the cadence off (max 30 s) and keep trying while the process lives;
  401/404/422, unrecognized status values, unparseable response bodies (which must not kill
  the poll loop), and extractions older than 5 minutes become terminal `FAILED` rows with a
  user-readable error; a `no_recipe` status becomes a terminal `NO_RECIPE` row carrying the
  service's explanation. **Status transitions are UPDATE-only** (never insert-or-replace):
  the user can cancel any in-flight row (R8.16), and a transition — including a submit POST
  landing after its placeholder was cancelled — must not resurrect a deleted row.
  The loop exits when no active rows remain, restarts on the next submit,
  and is **resumed on app start** (alongside R5.9's sync) so rows survive process death — the
  backend recovers its own crashed jobs server-side. **Submission is also app-scoped and
  row-first:** the submit POST runs in the application scope (leaving the screen must not
  cancel it) and writes a `SUBMITTING` placeholder row *before* the request goes out, because
  a cold-started backend can hold the POST for tens of seconds and the user needs immediate
  feedback; the placeholder upgrades to `PENDING` (server id) on success and to `FAILED` on
  error. A submit POST dies with the process, so any `SUBMITTING` row found at app start is
  marked `FAILED` ("interrupted") — its Retry resubmits the stored text.

---

## 6. Onboarding & Settings

- **R6.1** First launch shows an onboarding flow (with the Hauly logo) collecting three
  values: Notion integration token (PAT), Shopping List database ID, Recipe database ID.
- **R6.2** Before completing setup, the app validates both databases against the schemas in
  §2 and reports **each** missing or wrongly-typed property explicitly (database name,
  property name, expected type, actual type), so the user can fix their Notion template.
  Connection failures produce a distinct, human-readable error.
- **R6.3** Until setup succeeds, the main app is unreachable; while the configured flag is
  loading, show neither screen (no flash of onboarding).
- **R6.4** The Settings tab shows both database IDs, the last-synced time, and a "Sync now"
  button (with a snackbar confirming the sync is scheduled). Changing token/databases is done
  by clearing app data and re-onboarding — no in-app editing required.
- **R6.5** The Settings tab shows the app's semantic version (e.g. "Version 1.0.0"), read
  from the build's `versionName` via `BuildConfig` — not a separately maintained value.
- **R6.6** **Hauly beta token (optional).** Onboarding additionally offers an optional
  "Hauly beta token" field (password-masked like the PAT); leaving it blank completes setup
  normally and simply hides the clipboard-extraction flow (R8.15). Unlike the Notion values
  (R6.4), the token is also **editable any time in Settings** — a "Recipe extraction (beta)"
  section with a masked field and Save (saving empty clears it) — with no re-onboarding and
  no validation call: a wrong token surfaces as a clear "token rejected" error when an
  extraction is submitted or polled. Stored in DataStore (R3.6).

---

## 7. Shopping List — User Experience

### 7.1 Main list

- **R7.1** Screen title: **"Get your haul! :)"**.
- **R7.2** A row of store filter chips above the list, **sharing its line with the group-by-tag
  toggle** (R7.21): the chips scroll within the width left of that toggle rather than the full
  screen width, which keeps the controls to a single line. Chips are in a **manually chosen order**:
  long-pressing a chip drags it to a new position (a plain tap still selects it); the order is
  **local-only** (stored in DataStore Preferences, never synced to Notion) and persists across
  restarts. A store not yet manually placed (new from the Notion schema, or first seen on an
  item) is appended at the end of the manually ordered stores. The **"All" chip always sits at
  the end** of the whole row and is never draggable. Selecting a store filters items to those
  whose store list contains it (case-insensitive). "All" shows everything, including store-less
  items. Each store chip also carries a **count** of the items on its list (R7.24). The selected
  chip's label (including "All") renders in the primary blue. On opening the
  screen, the **leftmost store is selected by default** (i.e. the first store in the manual
  order) rather than "All"; the default never overrides a choice the user has already made in
  that session. The store a check-off happens in updates that store's last-shopped timestamp
  (still tracked, though it no longer drives chip order). The chip row's horizontal scroll
  position is never restored across navigation (e.g. switching tabs — by tap or swipe — and back) —
  it always opens scrolled to show the leftmost chip. Chips approaching the toggle **fade out
  into the background** over the row's last ~36 dp — a gradient to the background color, so they
  dissolve into the screen rather than sliding under a visible scrim. The fade must never cost
  the user a chip: the row carries that same width as **end padding**, so scrolling to the end
  brings the last chip ("All") to rest fully clear of the gradient, at full opacity and
  selectable. The gradient is **drawn over** the row, never laid out in it, so it intercepts
  neither taps nor the chip-reorder drag.
- **R7.3** Each item row shows the name on its first line and the needed amount as
  **"Need: X"** on the line below it, where X defaults to **1** when quantity is empty.
  Whole-number quantities render without decimals ("2", not "2.0"). A row awaiting sync
  appends "pending sync" to that second line. The item's **store list is not shown on the row**
  — the store view (R7.2) is the answer to "which store", and on "All" the row is deliberately
  quiet; stores remain visible and editable in the edit dialog (R7.16). Rows also expose a
  store-assignment affordance (chip-based picker dialog listing known store options) — but
  **only in the "All" view**: inside a store view the store is a given, so the icon is dropped
  as noise. Reassigning stores from a store view is still reachable through the edit dialog.
- **R7.4** Items can be **drag-reordered**. The order is local-only (never synced to Notion),
  persists across restarts and refreshes, and an item loses its manual position when shopped.
  Dragging must be constrained to the active section (cannot drag into the shopped section).
  Drag-reordering is **unavailable while the group-by-tag view is on** (R7.21) — the handle is
  gone and the sections sort alphabetically; turning the view off restores the manual order
  unchanged (grouping never writes `manual_rank`).
- **R7.5** Checking an item off marks it `Shopped` in Notion (via the queue) and moves it to
  the trip section (R7.6). Pull-to-refresh triggers a full refresh and must work even when
  the list is empty (the empty state must be scrollable so the gesture registers). A snackbar
  reports refresh failures. An offline banner shows when disconnected, including the pending
  edit count.

### 7.2 Trip ledger (shopped-this-trip section)

- **R7.6** As soon as the first item is checked off, a visually separate **"Shopped (n)"**
  section appears at the bottom of the list: items crossed out (strikethrough), grayed
  (~60% alpha), in the order they were shopped, each with a "tap to un-shop" hint.
- **R7.7** Tapping a shopped row un-shops it: it returns to the active list (and Notion's
  `Shopped` box is unchecked via the queue). This tracking is **purely local** — another
  device never sees the trip section.
- **R7.8** A **"Done"** button on the section header ends the trip: tracking is discarded,
  synced trip rows are evicted from the cache (they're shopped, so they leave the active
  cache), and rows still awaiting sync simply lose the trip flag. The empty state shows only
  when both the active and trip lists are empty.
- **R7.25** Tapping **Done** plays a **haul celebration** over the shopping area: a
  firework-style burst of randomly angled dots and short lines radiating from the centre of
  the screen — shooting out fast, easing off under a slight gravity droop, and twinkling out
  at their own pace — with **"Nice Haul!"** swelling in front of the burst before it fades.
  The dots/lines use the palette `#FFCA3A`, `#06AFFF`, `#FF06AF`, `#5D2E8C`, and a **short
  device vibration** fires with it (requires the `VIBRATE` permission). It replaces the
  trip-finished snackbar as the confirmation of a finished trip (R7.8). It is purely
  presentational — an overlay that draws nothing when idle and never intercepts touches.

### 7.3 Add-item dialog (type-ahead)

- **R7.9** There is **no search bar** on the shopping screen. A floating **plus** button opens
  an add dialog titled **"haul"**. As soon as the user types, a narrowing list of matching
  existing items appears, blending: instant substring matches from the local cache, and
  **debounced (~350 ms) remote Notion search** — this is how already-shopped, no-longer-cached
  items are found. Results are deduplicated by name (case-insensitive), sorted, capped at 8.
  Remote search is skipped offline; a subtle indicator shows while it runs; stale responses
  (user kept typing) are discarded.
- **R7.10** Tapping a suggestion fills the name and pre-populates the quantity field from the
  item; the suggestion list then **hides until the user edits the name again** (which also
  clears the selection). Suggestions are labeled ("Existing item selected" vs. "New item —
  will be created in Notion"); the dialog notes "Will be tagged for {store}" when a store
  filter is active.
- **R7.11** Confirming with **Add**:
  - *New name* → create the item (queued `PENDING_CREATE`) with the current store view as its
    store tag (no tag when viewing "All") and the entered quantity.
  - *Existing item* → reactivate it: un-check `Shopped`, add the current view's store to its
    store list if missing (case-insensitive), and apply the entered quantity. A remote-only
    match (found via search, not cached) is inserted into the cache and queued the same way.
  - Reactivation initiated **without** an explicit quantity keeps the item's existing
    quantity (never resets it).
  - If the item is already on the active list, tell the user instead of duplicating.
  - Each outcome emits a distinct snackbar ("Added …", "… is back on the list",
    "… is already on the list").
  - **Add keeps the dialog open**: after each add the Name/Quantity fields and any suggestion
    selection are cleared (the "Add" button disables until a new name is typed) so the user can
    add several items in one sitting. Focus returns to the **Name** field on open and after
    every add. The dialog is closed only by the **"Done"** button (the dismiss action) or by
    dismissing it. This applies to every add dialog, including the recipe ingredient add (R8.5).

### 7.4 Shopped-items browse (tap to re-add)

- **R7.12** At the very bottom of the shopping list (below the trip section) sits a subtle,
  centered text toggle — "Show shopped items" / "Show shopped items for {store}" /
  "Hide shopped items" with an expand/collapse chevron. **Collapsed by default.** Expanding
  reveals the shopped items for the store currently in view (all stores on "All"), ordered by
  Notion's `last_edited_time`, most recent first, so the user can re-add recent purchases
  with a tap instead of typing.
- **R7.13** The browse is **online-only and paginated** — the shopped set grows without bound,
  so it is never cached and never fetched exhaustively: one page (50) is fetched on expand,
  with a "Show more" row while further pages exist, a small inline spinner while loading, an
  inline error message on failure (e.g. offline), and "Nothing shopped here yet." when empty.
  Rows render lazily inside the main list. Expanding always fetches fresh; changing the store
  filter while expanded restarts the browse for the new store (filtered server-side); stale
  responses from an abandoned store/collapse are discarded.
- **R7.14** Each row is compact — a small add icon, the item name, and its quantity when set —
  with a one-line hint above the list ("Tap an item to put it back on the list"). Tapping a
  row reactivates the item through the standard path (un-shopped, current store appended if
  missing, quantity kept) and emits the "… is back on the list" snackbar. Items already on
  the active list or in the trip ledger are filtered out of the browse, so a tapped item
  disappears from it the moment it lands back on the list.
- **R7.15** The browse's toggle must remain reachable when the shopping list is empty (the
  empty state renders inside the scrollable list, above the toggle) — an empty store view is
  exactly where re-adding past purchases matters most. Pull-to-refresh keeps working in that
  state.

### 7.5 Edit item (long-press)

- **R7.16** **Long-pressing** an item row — on the shopping list or on a recipe's ingredient
  list — opens a shared edit dialog titled **"edit"** for the item's **name**, **stores**
  (chip picker over the known store options plus the item's own stores, with a "New store"
  free-text field; selected chips render their label in the primary blue), **tags** (R7.22),
  and **quantity** (leaving it empty clears Qty in Notion). Saving queues a normal
  offline-safe update. The dialog's content scrolls, so it stays usable on short screens.
  Renaming onto another item's name (case-insensitive) is rejected with a snackbar ("An item
  named … already exists") — names stay unique. Blank names cannot be saved. The dialog also
  has a **Delete** button (error color) that removes the item from the shopping database
  (R5.11); being destructive, the first tap arms it ("Really delete?") and only a second tap
  deletes, with a "Deleted …" snackbar.
- **R7.17** While a press is held on an editable row, a **growing color "iris"** (primary
  blue at low alpha) expands from the touch point, timed to the system long-press timeout so
  it reaches full row coverage as the edit action fires — a visual warning that holding will
  trigger something. Releasing early fades it out; after triggering it fades away. The iris
  is driven by the same press interactions as the click handling, so it tracks real presses
  (including cancellation by scrolling).

### 7.6 Swipe to discard

- **R7.18** **Swiping a row to the right** — on the active list *or* on the trip section
  (R7.6) — **discards** the item: it is marked `Shopped` in Notion (via the queue) but
  **never enters the trip ledger** (`trip_shopped = 0`), so it leaves the active list without
  being counted as part of this trip and is immediately reachable again from the shopped-items
  browse (R7.12). This is the correction for an item that shouldn't have been added at all.
  It is *not* a delete (R7.16 owns that) — the Notion page survives, shopped. Discarding
  clears the item's manual sort position (R7.4) and, unlike a check-off (R7.5), does **not**
  update the store's last-shopped timestamp: a discard is a correction, not a purchase. A
  "Discarded …" snackbar confirms it.
- **R7.19** As the row is dragged right, a **"Discard" backdrop** (a remove icon and the label,
  in the error color) is revealed behind it, fading in with the drag and deepening once the
  drag passes the ~40%-of-row-width commit threshold — so the arming point is visible before
  release. Releasing past the threshold slides the row clear and discards; releasing short of
  it springs the row back and does nothing.
- **R7.20** The row swipe must claim a drag **only once it is unambiguously rightward**
  (rightward-dominant past touch slop) and must leave every other drag unconsumed, so a
  **left swipe still reaches the pager** (R9.1: swipe left → Recipes) and vertical drags still
  scroll the list. Material's `SwipeToDismissBox` is therefore unusable here — it claims every
  horizontal drag on the row and would swallow the pager's gesture across the whole list.
  This direction test is the entire safeguard, so it lives in **one shared, direction-parameterized
  swipe component** used by both row-swipe surfaces (discard here, unlink on the recipe detail
  R8.14) rather than a copy per screen that could drift. A row swipe may only ever use the
  direction the pager does not need on that page.

### 7.7 Group by tag

- **R7.21** The active (unshopped) list can be **grouped by the item's `Tags`** (the
  multi-select of §2.1). A small icon button at the **right end of the store chip row** (R7.2) —
  sharing that line rather than taking one of its own, so the list keeps the vertical space —
  toggles the view: primary blue when grouping is on, `onSurfaceVariant` when off (the
  same on/off signal the selected store chip uses). The flag is **local-only** (DataStore, never
  synced to Notion), applies to **every store view** at once, and persists across restarts.
  Each group renders as its **tag name in light gray followed by a horizontal rule running to
  the right edge**, with that tag's items beneath it. Groups are ordered **alphabetically**, and
  so are the items within each group. An item carrying **several tags appears once under each of
  them** (rows are therefore keyed by tag *and* item id — keying by id alone duplicates Compose
  keys and throws); items with **no tags** collect in an **"other"** group rendered last, so
  nothing is ever hidden by the view. Grouping applies to the **unshopped list only**: the trip
  ledger (R7.6) and the shopped browse (R7.12) are untouched, so checking an item off removes it
  from every group at once and it appears exactly **once** under "Shopped (n)". Every row
  affordance survives grouping (check off, swipe-to-discard R7.18, long-press edit R7.16, store
  picker) **except** the drag handle (R7.4) — grouped rows carry a **tag icon in the handle's
  place** (R7.23), the property the view is arranged by standing in for the one it disables.
- **R7.23** The grouped row's **tag icon opens a quick tag picker** — "Tags for {item}" — the
  counterpart to the store picker (R7.3) for a single property, when the full edit dialog
  (R7.16) is more than the user wants. It offers the same chips and "New tag" field as R7.22,
  under the same lowercase rule, and saving with nothing selected clears the item's Tags and
  drops it into the "other" group. Every tag write in the app, from either dialog, passes
  through one normalization point so the lowercase invariant cannot depend on the entry point.
- **R7.22** The edit dialog (R7.16) edits the item's **`Tags`**: a chip picker over the known tag
  options (the cached schema options of R5.8, plus the item's own tags so one just added shows as
  set before the next refresh) with a **"New tag"** free-text field; selected chips render their
  label in the primary blue. Tags are forced **lowercase** both while typing and on save —
  deliberately unlike stores, which are title-cased: the Notion `Tags` options are lowercase, and
  a title-cased value would create a **second option beside the existing one**. Deselecting a chip
  removes that tag from the item and clearing them all clears the multi-select in Notion; the
  dialog never edits the Notion **schema's** option list (that would violate R2.7). Saving queues
  a normal offline-safe update through the same path as name/stores/quantity — one write, one sync
  request — and a tag Notion has never seen is created by the page patch itself, surfacing in the
  cached options after the next refresh. The **add-item dialog does not set tags** (R7.11): new
  items start untagged and are categorized later from the edit dialog.

### 7.8 Store chip counts

- **R7.24** Each **store** chip (R7.2) shows, after the store name, **how many active items are
  on that store's list** — so the row answers "how much is left at each store" without visiting
  them. The number is what selecting that chip would show: it counts the **unshopped** items
  carrying that store (case-insensitively, as the filter itself matches), and is computed from
  the **unfiltered** active list, so it does not change with the store in view. Shopped items and
  the trip ledger (R7.6) are excluded, so checking an item off decrements its stores' counts
  immediately, as does a discard (R7.18) or a delete (R7.16); an item in several stores counts
  once in **each**. A store with **nothing on its list shows no number** — a bare chip means
  empty. The count is rendered smaller and in `onSurfaceVariant`, subordinate to the name, which
  keeps its own selected/unselected color (R7.2). The **"All" chip carries no count**: it is a
  view, not a store. Counts are derived state — no schema, no Notion property, nothing synced.

---

## 8. Recipes — User Experience

- **R8.1** The Recipes tab lists all recipes with pull-to-refresh and two sort options,
  presented as chips to the right of the title: **"A–Z"** (alphabetical, the default) and
  **"Recent"** (most recently edited in Notion first, from the page's `last_edited_time`).
  The sort applies to both the Planned section and the main list. The whitespace above the
  "Recipes" title must match the shopping screen's title (same status-bar inset handling).
  Tapping a sort chip **scrolls the list back to the top** — the order just changed under the
  user, so their scroll offset no longer means anything. The chosen sort is **local-only
  (never synced) and persisted** in DataStore, so it survives leaving the tab and restarting
  the app; "A–Z" is the default only until the user picks. The scroll-to-top is **armed by the
  tap but performed when the re-sorted rows arrive**, and both halves are load-bearing:
  scrolling on the tap alone does nothing, because the sort round-trips through DataStore and
  the arriving order makes `LazyColumn` re-anchor the scroll onto the previously top-most item
  (it tracks items by key — the same behavior R7.2's chip row works around), undoing it;
  while scrolling on *every* sort emission would also fire when the pager re-creates the page
  (R9.1) and discard the scroll position a tab return is meant to preserve.
- **R8.2** Recipes with `Planned` checked appear in a **"Planned" section pinned above the
  main list** — it stays frozen in place and does not scroll with the list. It renders as a
  rounded-corner box ("card") with a bluish background (`primaryContainer`) and a subtle
  primary-tinted border, recipes inside separated by inset dividers; past a max height
  (~280 dp) the box scrolls internally so a large plan can't push the list off screen. The
  remaining recipes scroll below under an "All recipes" header, and pull-to-refresh engages
  on that scrolling list.
- **R8.3** Recipe detail shows: the recipe name; a **"Make it" / "Don't make it"** toggle
  button (with calendar-style icons) as the first content element, which toggles `Planned`
  (queued to Notion) and **must not touch the ingredients** in any way; an **"Open in
  Notion"** icon button (Notion "N" logo) that opens the recipe page
  (`https://www.notion.so/{page-id-without-dashes}`) via an external intent; a **rename**
  (pencil) affordance for the recipe name (R8.9); the **Shopping** list (from the `Shopping`
  relation — **always complete**, showing every linked item whether shopped or not, per R4.5);
  the editable **Ingredients** and **Instructions** text sections (R8.7–R8.8); a read-only
  **Additional** section for legacy page body (R8.4); and a **Delete recipe** action (R8.11).
  Each Shopping row shows its quantity to the **left** of the name (defaulting to 1 when Qty is empty, same
  formatting as the shopping list); tapping the row toggles the item between shopped and
  un-shopped, with shopped rows crossed out and marked by a check icon (checking off adds the
  item to the shopping screen's trip ledger, exactly like checking it off there). Long-pressing
  an ingredient opens the shared edit dialog (R7.16); swiping it **left** unlinks it from the
  recipe (R8.14).
- **R8.4** The recipe's legacy Notion page **body** is **read-only**, fetched via the paginated
  block-children endpoint, cached locally, and rendered per block type (paragraphs, headings,
  bulleted/numbered lists, to-dos, quotes, dividers, etc.); unsupported types degrade
  gracefully. It appears under an **"Additional"** heading **only when the page body has
  content** — editable recipe content now lives in the `Ingredients`/`Instructions` properties
  (R8.7), not the body.
- **R8.5** From a recipe, the user adds ingredients through the **same type-ahead add dialog
  as the shopping list** (R7.9–R7.10: local + remote suggestions, duplicate-name prevention),
  opened by an "Add ingredient" button below the ingredient list. Confirming links the item
  to the recipe via the relation and **automatically applies the "Grocery" store** (new items
  get it as their store; existing items get it appended case-insensitively) — the dialog
  shows "Will be tagged for Grocery". An entered quantity is applied; without one, an
  existing item's quantity is kept. Existing items are linked (not duplicated) and **keep
  their current shopped state** — unlike the shopping-list add path (R7.11), the recipe path
  never un-shops an item, so an already-shopped item stays crossed out in the recipe's list
  (per R4.5/R8.3) and its add emits a distinct "already shopped" snackbar. A remote-only
  suggestion (a shopped item evicted from the cache, found via search) goes through the same
  create path — its shopped state seeds the new row so the create-with-merge flush (R5.4)
  re-links it **without un-shopping** the Notion page and without duplicating. When the name is
  typed **in full without tapping a suggestion** (or added offline), there is no shopped state
  to seed: the row is created unshopped but flagged `shopped_assumed` (R4.1), and the
  create-with-merge flush adopts the existing page's `Shopped` (R5.4) so the page is still not
  un-shopped. Residual: until that flush lands the item displays unshopped and sits on the
  active list, and checking it off in the meantime is an explicit assertion that wins over the
  assumption. Blank names are rejected.
- **R8.6** Recipe screens include loading and error states like every other screen.
- **R8.7** **Editable Ingredients & Instructions.** Both are stored as newline-separated text
  in Notion rich_text properties (§2.2) and edited in place: each section has a pencil that
  swaps its view for a multi-line text field (one item/step per line) with Save/Cancel. Saving
  is **offline-safe** — it queues a `PENDING_UPDATE` on the recipe row (same offline queue as
  the Planned toggle) and the sync worker flushes the full recipe property payload (name,
  ingredients, instructions, planned). Rich_text is chunked to respect Notion's 2000-char
  per-object limit. The **Ingredients** view renders like **ruled paper**: each non-blank line
  is shown with a full-width divider beneath it; an empty section shows a muted placeholder. A
  line whose text begins with `--` is a **heading** within the list: the `--` marker
  is stripped and the line is rendered with extra whitespace above it and a slight background
  highlight to group the lines beneath it. Headings are display-only — they are not
  tappable and carry no per-line strike (R8.8). This `--` heading convention applies to **both
  the Ingredients and the Instructions** lists (the ruled-paper divider styling remains
  Ingredients-only). Each of those two section headers carries a **help (?) icon** beside its
  edit pencil that opens a short dialog explaining the `--` sub-heading convention, so the
  feature is discoverable.
- **R8.8** **Per-line strike (focus) tracking.** In view mode every non-blank Ingredients/
  Instructions line is tappable; tapping crosses it out and dims it (~60% alpha), tapping again
  restores it, to help the cook keep their place. This is **local-only** (R4.7) — never synced
  to Notion, independent of the Shopping list's shopped state, and a section's strikes reset
  when that section's text is edited.
- **R8.9** **Rename** (pencil by the title) edits the recipe `Name`; offline-safe like R8.7.
  Blank names are rejected.
- **R8.10** **Create recipe.** A "+" button on the Recipes list opens a dialog for name plus
  optional ingredient/instruction text; confirming is **online-first** (writes to Notion, then
  caches the row and opens it). Blank names are rejected; when offline the attempt reports that
  a connection is required. The ingredient/instruction fields **grow with their content**
  (all text visible, no inner field scrolling) and the dialog's panel scrolls as a whole once
  it outgrows the dialog — so a long prefilled extraction (R8.17) can be reviewed end to end.
- **R8.11** **Delete recipe.** A destructive action on the recipe detail (two taps to confirm,
  error color) that is **online-first**: it archives the Notion page (recoverable from the
  Notion trash), removes the local row and its relations, and returns to the list. When offline
  it reports that a connection is required.
- **R8.12** **Source link.** The recipe's `URL` property is shown near the top of the detail as
  a **clickable web link** (opens in the browser via an external intent; a missing scheme
  defaults to `https://`). It is **editable** (a pencil opens a link dialog; leaving it empty
  clears it) and settable at create time — both offline-safe/online-first like the rest of the
  recipe's editable fields (R8.7). When unset, a subtle "Add a link" affordance appears instead.
- **R8.13** **Search.** A search field is pinned under the title (below the sort chips). Typing
  filters the list to recipes matching the query in **any** text property — `Name`,
  `Ingredients`, `Instructions`, or `URL` — case-insensitively (substring match, query
  trimmed). While a query is active, the Planned/"All recipes" grouping (R8.2) collapses into a
  single flat list of all matches, and a "No matches" empty state shows when nothing matches. A
  trailing clear (✕) button empties the field, drops focus so the keyboard closes, and restores
  the normal grouped view. The clear button is shown whenever the field **has text or is
  focused** (even with no text), so a focused-but-empty field can always be dismissed; the
  keyboard's action key ("Search") also dismisses the keyboard without clearing the query, so
  the user can always leave the field. Search is a purely local filter over the cached rows (no
  Notion query).
- **R8.14** **Swipe to unlink an ingredient.** Swiping a Shopping row on the recipe detail
  **to the left** removes that item from **this recipe's** `Shopping` relation. It is purely a
  disassociation: the item stays on the shopping list with its stores, quantity, tags and
  shopped state untouched, its links to *other* recipes survive, and its Notion page is neither
  archived (R7.16 owns delete) nor marked shopped (R7.18 owns discard). A "Removed … from this
  recipe" snackbar confirms it; there is no undo affordance, since re-linking is a few taps
  through the add dialog (R8.5). Offline-safe: the local relation is dropped immediately and
  the item queued, and because a flush sends the item's local ref set as the page's **complete**
  relation (R5.10), the next sync drops the recipe from the Notion relation with no extra
  payload. Once unlinked, a shopped item is no longer pinned in the cache by the relation and
  becomes evictable on the next refresh (R4.5) — reachable again from the shopped-items browse
  (R7.12). The gesture is the **mirror image** of swipe-to-discard: same ~40% commit threshold,
  same fade-in-and-deepen backdrop and spring-back (R7.19), with a "Remove" label and unlink
  icon revealed on the **right** as the row travels left. Left is available on this surface
  precisely because Recipes is the pager's **last** page (R9.1), so nothing pages to its left;
  the right swipe (back to Shopping) must still get through, which is what R7.20's direction
  discipline guarantees.
- **R8.15** **Paste recipe from clipboard (trigger & preview).** **Long-pressing** the
  Recipes "+" button (with the same growing-iris hold affordance as R7.16's long-press; the
  button is a Surface-based FAB look-alike because Material3's `FloatingActionButton` exposes
  no `onLongClick`) reveals a **preview card**, titled with an ai-sparkle glyph, floating
  above the button showing the current clipboard text — first few lines ellipsized plus a
  character count — with an invisible
  full-screen scrim behind it so tapping anywhere else dismisses it. Only the **current**
  clipboard item is available (Android exposes no clipboard history).
  **URL clipboard.** If the clipboard holds a **single web URL** (a bare link — the trimmed
  text is a full `Patterns.WEB_URL` match with no internal whitespace, so a multi-line recipe
  blob never qualifies), the card instead titles itself **"Recipe from clipboard URL"** and
  shows the URL's **first part** (its host, less a leading `www.`) in place of the text peek
  and character count — the backend also parses a recipe out of the web page behind a URL. The
  submission is otherwise **identical**: the URL string is sent as the request `content`
  (R2.10) through the same pasted-source (`extract`) route as clipboard text, so it shares all
  the downstream flow (status row, Retry). Tapping the card
  submits the text to the extraction backend (R2.10) and dismisses the card, and a
  `SUBMITTING` status row (R8.16) appears **immediately** — before the POST completes.
  Guard rails: with no beta token stored (R6.6) the card instead shows a
  configure-in-Settings hint; an empty clipboard keeps the sparkle-and-title header but shows
  "copy a recipe first" below it; text over 100,000 chars and being offline each report via
  snackbar and create nothing. A submit that
  gets past those guards but fails on the wire (bad token, unreachable service) becomes a
  `FAILED` row with Retry (R5.13) rather than vanishing. A plain tap on the button still
  opens the R8.10 create dialog, unchanged.
  **Free text.** When a beta token is stored, the same long-press reveal also shows a
  **"Free-text recipe"** option, also marked with the ai-sparkle glyph, above the clipboard card. Selecting it opens a **max-sized dialog**
  (fills the screen, not the platform's narrow dialog width) with one large multi-line text
  area for the user to type or paste a recipe, plus Cancel and Create. Create sends the text
  to the **magic** extraction route (R2.10) — same guards (non-blank, ≤100,000 chars, online)
  and same downstream flow as the clipboard paste: a `SUBMITTING` row (R8.16) appears
  immediately and the dialog closes. A `FAILED` free-text extraction's Retry resubmits to the
  magic route (R2.10, R4.8).
- **R8.16** **Extraction status row.** Every row in `recipe_extractions` (R4.8) renders as a
  status row **pinned above the list** (above the Planned box, and still visible while
  searching — it is transient status, not list content), styled like the Planned card.
  While in flight it shows a small spinner plus a pulsing ai-sparkle glyph over a **pulsing**
  background (container color alpha animating ~0.4↔1.0, ~800 ms per leg) — labeled "Sending
  recipe…" while `SUBMITTING` (the POST can be held by a cold-started backend) and "Recipe
  magic happening…" once `PENDING`/`PROCESSING`, each over a small **patience caption** ("This
  can take a couple of minutes — hang tight.") because parsing — a page URL (R8.15) especially —
  can take up to ~2 minutes on the backend — with a ✕ to **cancel**: the row is deleted locally and any late
  result for it is dropped (R5.13); the backend job simply finishes unobserved. The row body
  itself is not tappable while in flight. On `COMPLETED` the pulse stops (steady `primaryContainer`) and the row shows
  the extracted title (fallback "Recipe ready") with a "Tap to review" caption — it **stays
  until the user acts on it**, surviving process death (R5.13) — plus a ✕ to dismiss without
  creating. On `FAILED` it turns `errorContainer` and shows the stored error with **Retry**
  (deletes the failed row and resubmits its `source_text` as a new extraction) and ✕
  (dismiss). On `NO_RECIPE` it renders like `FAILED` (error colors, the service's
  explanation, ✕) but **without Retry** — the service already judged the text not to be a
  recipe, so resubmitting it can't help.
  **URL-failure help.** Web-page parsing (R8.15) is unreliable, so a **terminal failure**
  (`FAILED` *or* `NO_RECIPE`) of a **URL source** additionally shows a **help (?) icon** on the
  row, beside Retry/✕. Whether a source was a URL is derived from the row's stored `source_text`
  (a single bare link — the same detection as the R8.15 preview), so no extra column is needed.
  Tapping the help icon opens a **full-view help dialog** ("Copy the recipe yourself") that
  walks the user through the manual fallback: open the page in a browser, select and copy all
  its text, then long-press + and use "Grab recipe from clipboard" (R8.15) on the copied text.
  Multiple extractions may run at once, one row each,
  oldest first. Extraction polling must not flicker the global network bar (R2.9).
- **R8.17** **Review & create.** Tapping a completed extraction row opens the **R8.10 create
  dialog prefilled** with the extracted title/ingredients/instructions for the user to review
  and correct — **nothing is auto-created in Notion**. Confirming runs the normal online-first
  create (R8.10, including opening the new recipe) and deletes the extraction row; cancelling
  keeps the row for later.

### 8.x Cook mode

- **R8.18** **Cook mode (toggle, persistence, keep-awake, pinned list).** The Instructions
  header carries a **frying-pan toggle button** (next to the help/edit icons, Instructions only)
  that turns **cook mode** on/off for that recipe. Cook mode is **app-scoped, in-memory session
  state**, held in an app-`@Singleton` `CookModeController` — **not** Room, Notion, or the sync
  queue: timers are transient runtime state, so a session survives leaving the recipe screen
  (navigating away, swiping to Shopping, visiting Settings) but is **lost on process death**,
  which is acceptable for a live stopwatch. While **any** recipe is in cook mode the **screen is
  kept awake** (`keepScreenOn`, applied once at the navigation-host root). Each cooking recipe
  appears as a **magenta-highlighted row in a top-pinned "Cooking" section** of the Recipes list
  (above even the extraction rows, R8.16); tapping a row opens that recipe. Cook mode uses a
  dedicated **magenta accent** (`CookMagenta`), deliberately distinct from the blue brand accent.
  **On entering** cook mode, **all Ingredients/Instructions strikes (R8.8) are cleared** as a
  fresh-start reset. **While** cook mode is active on a recipe, the detail screen adapts: the
  **Shopping list section is hidden** (linked items, its header, and "add item" — shopping is
  irrelevant mid-cook), and the **"Make it"/"Don't make it" planned toggle (R8.6) is replaced by
  the word "cooking" in magenta**. On the moment of **activation** (the off→on transition, not when
  reopening an already-cooking recipe) the detail **auto-scrolls the Instructions header to the
  top**, since the collapsing shopping list would otherwise leave the view mid-list.
- **R8.19** **Overall & per-step timers.** A cook session has **one overall timer**, always
  visible directly **under the Instructions heading** while in cook mode, plus **optional
  per-step timers**. A step timer appears only after a **long-press on that step** (a step is a
  non-blank, non-`--`-heading instruction line, keyed by its raw line index — the same index used
  for strike tracking, R8.8); long-pressing a step that already has a timer removes it. A step
  that has a timer is drawn inside a **magenta outline** that bounds the step text and its timer
  together, with the timer **below** the step text. Tapping a step still toggles its strike
  (R8.8) as in normal view.
- **R8.20** **Timer/stopwatch UX.** Every timer is a **single-line** control laid out as
  `reset | time | start-pause`, all **icon** buttons, time shown as **MM:SS** (minutes may exceed
  59). Mode is fixed by the value when **Start** is pressed: **0 ⇒ stopwatch** (counts up), a
  positive set value **⇒ countdown timer**. Start/Pause toggles running; while **running only
  Pause is enabled** (reset and the time fields are locked to prevent an accidental wipe). When not
  running the **minutes and seconds are edited inline** — tapping either segment selects it and the
  user types the value straight into the timer (no dialog); each edit commits immediately, and 0/0
  leaves it a stopwatch. **Reset** returns a stopwatch to
  0 and a timer to its set value; resetting an already-idle timer **a second time** — or setting
  it to 0 by hand — drops it back to **stopwatch** mode. A countdown that **reaches zero**
  auto-pauses at 0 and fires an alert: the default notification **tone plays once**, and a
  **continuous vibration** plus a **magenta flash** on the row run **until the timer is reset**
  (the vibration loops while *any* finished timer is unacknowledged, and stops once all are
  reset). Completion is detected by the controller (a monitor loop) so the alert fires even when
  the timer's UI is off-screen.

---

## 9. Global UI, Theme & Branding

- **R9.1** Bottom navigation with three destinations: Shopping, Recipes, Settings. The two
  **list** tabs (Shopping, Recipes) form a **horizontally swipeable pager** — a left/right
  swipe moves between them, and their bottom-bar items reflect and drive the current page
  (tapping animates to it). The pager owns horizontal drags: any row-level horizontal gesture
  inside a page (e.g. swipe-to-discard, R7.20) must claim only its own direction and leave the
  rest unconsumed, or it will silently disable paging across that whole list. Which direction a
  page has spare follows from its position: Shopping is the first page, so rows there may claim
  **right** (R7.18); Recipes is the last, so rows on the recipe detail may claim **left**
  (R8.14). **Settings is not swipeable**: it is a separate route reached only
  by tapping its bottom-bar item; tapping Shopping/Recipes from Settings returns to the pager on
  the chosen tab. The bottom bar shows on both the pager and Settings. Each list tab's ViewModel
  is scoped to the pager's back-stack entry so its data survives swiping; the off-screen page
  composition is disposed (which is what lets the shopping chip row re-open scrolled to the
  leftmost chip, R7.2). The recipe detail is **the content of the Recipes pager page** — the
  page shows the open recipe's detail in place of the list — not a separate route over the
  pager. So it is a first-class tab surface: the bottom bar stays visible (Recipes selected),
  and the **horizontal swipe still works on it** (swiping right reveals Shopping, exactly as
  from the list). A recipe stays "open" once entered until it is **closed** — closing is the
  detail's back affordance, the system back button (active only while the detail is the visible
  page), or a delete (R8.11), all of which clear the open-recipe state and return the page to
  the list. Switching to another tab (Shopping/Settings) from an open recipe does **not** close
  it: returning to Recipes shows the still-open recipe rather than the list. Because the detail
  replaces the list in the same page, opening a recipe swaps to it directly with **no
  list-then-detail flash**. The open-recipe id is remembered across rotation/process death. A
  horizontal swipe that begins on a horizontally scrollable child (e.g. the store-chip row)
  scrolls that child first and only pages once it reaches its edge.
- **R9.2** A **global network-activity indicator**: a thin (2 dp) indeterminate progress bar
  overlaid at the top of the screen (below the status bar), visible whenever any Notion HTTP
  request is in flight. Driven by an OkHttp interceptor counting active requests, with the
  "off" transition debounced (~300 ms) so back-to-back requests don't flicker.
- **R9.3** Dark theme only. Accent color is **blue `#06AFFF`** (primary), with a deep-blue
  container (`#0E3A52`) and dark ink surfaces (`#101314` family); errors in an ember/red tone.
  No green accents anywhere. The full canonical palette is specified in **R9.7**; every color
  used anywhere in the app must be drawn from it.
- **R9.4** The app icon is an adaptive icon built from the Hauly artwork on a solid `#06AFFF`
  background, with the glyph padded to sit inside the adaptive-icon safe zone (~61% of
  canvas). The same artwork appears as the logo on the onboarding screen.
- **R9.5** Every new Compose screen must model loading, empty, and error states explicitly;
  user-visible failures surface as snackbars or inline error views, never silently.
- **R9.6** Portrait orientation only, matching the single-handed bottom-nav design (R1). The
  lock is declared once via `android:screenOrientation="portrait"` on `MainActivity` (the app's
  only activity). Note that apps targeting SDK 36 have orientation restrictions ignored on
  large screens (smallest width >= 600 dp), so the lock is effective on phones only; state
  that survives rotation (e.g. the open-recipe id, R8.1) must still be preserved, since
  process death and large-screen rotation can both still occur.
- **R9.7** **Canonical Hauly color palette.** All UI color must come from this palette — no
  ad-hoc hex values in composables. It is dark-first (R9.3), built on near-black ink surfaces
  with blue as the primary accent. Colors are defined in
  `presentation/theme/Theme.kt` (private `Color` vals feeding a `darkColorScheme`); the token
  name below is that val's name where one exists.

  **Brand palette (the five defining Hauly colors):**

  | Hex | Name | Role |
  | --- | --- | --- |
  | `#101314` | Ink | Base background / surface (near-black) |
  | `#5D2E8C` | Violet | Extended brand accent (secondary/decorative) |
  | `#FF06AF` | Magenta | Extended brand accent (secondary/decorative) |
  | `#FFCA3A` | Gold | Extended brand accent (secondary/decorative) |
  | `#06AFFF` | HaulyBlue | Primary accent (matches the app icon, R9.4) |

  **Supporting palette (currently defined in `Theme.kt`):**

  | Hex | Token | Role |
  | --- | --- | --- |
  | `#1A1E20` | InkRaised | Raised surface (`surfaceVariant`, `surfaceContainer`) |
  | `#24292C` | InkHigh | Highest surface (`surfaceContainerHigh/Highest`, `secondaryContainer`) |
  | `#E4E7E8` | Mist | Primary text/icons (`onBackground`, `onSurface`) |
  | `#9AA4A8` | MistDim | Secondary text (`secondary`, `onSurfaceVariant`) |
  | `#0E3A52` | HaulyBlueDeep | Primary container (deep blue) |
  | `#00253A` | — | `onPrimary` (text/icon on the blue accent) |
  | `#F28B82` | Ember | Error tone (`error`) |
  | `#3A100C` | — | `onError` |
  | `#3A4145` | — | `outline` |
  | `#2A3034` | — | `outlineVariant` |

  The three extended brand accents (Violet `#5D2E8C`, Magenta `#FF06AF`, Gold `#FFCA3A`) are
  brand-defined but **not yet wired into the `darkColorScheme` in `Theme.kt`**; blue remains the
  sole primary UI accent (R9.3). When any of them is introduced into the UI, add it as a named
  `Color` val in `Theme.kt` and map it through the theme rather than hard-coding the hex at the
  call site.

---

## 10. Build & Environment Constraints

- **R10.1** `compileSdk`/`targetSdk` 36, `minSdk` 26, JVM target 17. All dependency versions
  are pinned in `gradle/libs.versions.toml`.
- **R10.2** Known-good version matrix (this machine has only the SDK 36 platform — newer
  androidx trains requiring compileSdk 37 / AGP 9 must not be used): AGP 8.13.2,
  Gradle 8.14.3, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Hilt 2.57.2 (2.60.1+ requires AGP 9),
  androidx.hilt 1.3.0, Room 2.7.2, Compose BOM 2025.09.01, core-ktx 1.17.0, lifecycle 2.9.4,
  activity-compose 1.11.0, navigation 2.9.8, work 2.10.5, datastore 1.2.1, retrofit 2.12.0
  (+ kotlinx-serialization converter), okhttp 4.12.0, kotlinx-serialization-json 1.9.0,
  coroutines 1.10.2, reorderable 2.5.1.
- **R10.3** `hiltViewModel()` is imported from `androidx.hilt.lifecycle.viewmodel.compose`
  (the non-deprecated package). `buildConfig = true` is required alongside Compose.
- **R10.4** Room schema changes ship with a migration; during pre-release development the
  database uses destructive fallback (`fallbackToDestructiveMigration(dropAllTables = true)`),
  which R5.9's startup sync compensates for.
- **R10.5** Verification on this machine is compile-only (`./gradlew assembleDebug` and the
  R8 release build); no emulator or device is available.

---

## 11. Hard Constraints (never violate)

1. Never bypass Room to hit Notion directly from the UI/ViewModel layer for list data
   (R3.3's search exception is the only carve-out).
2. Never remove the rate-limiting/backoff interceptor logic.
3. Never assume the Notion schema changed unless the user explicitly says it has.
4. Never invent libraries; stick to the established Jetpack/mainstream ecosystem.
5. All synced Room entities include `sync_status`; local-only columns (`manual_rank`,
   `trip_shopped`) must never be pushed to Notion.
