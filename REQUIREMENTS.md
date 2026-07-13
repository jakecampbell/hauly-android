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
  chip order) are stored in DataStore Preferences.

---

## 4. Local Cache (Room)

- **R4.1** `shopping_items` table: local UUID primary key `local_id`; nullable unique
  `remote_id` (Notion page id); `name` unique with `NOCASE` collation (duplicate prevention);
  `stores` and `tags` as JSON-converted string lists; nullable `quantity` (Double);
  `shopped`; `sync_status`; `updated_at` (epoch millis); plus two **local-only** columns that
  bypass sync entirely: `manual_rank` (nullable drag position) and `trip_shopped`
  (current-trip ledger flag).
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
  alphabetically (case-insensitive).

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
  remote — and link the local row to the existing page id.
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

---

## 7. Shopping List — User Experience

### 7.1 Main list

- **R7.1** Screen title: **"Get your haul! :)"**.
- **R7.2** A row of store filter chips above the list. Chips are in a **manually chosen order**:
  long-pressing a chip drags it to a new position (a plain tap still selects it); the order is
  **local-only** (stored in DataStore Preferences, never synced to Notion) and persists across
  restarts. A store not yet manually placed (new from the Notion schema, or first seen on an
  item) is appended at the end of the manually ordered stores. The **"All" chip always sits at
  the end** of the whole row and is never draggable. Selecting a store filters items to those
  whose store list contains it (case-insensitive). "All" shows everything, including store-less
  items. The selected chip's label (including "All") renders in the primary blue. On opening the
  screen, the **leftmost store is selected by default** (i.e. the first store in the manual
  order) rather than "All"; the default never overrides a choice the user has already made in
  that session. The store a check-off happens in updates that store's last-shopped timestamp
  (still tracked, though it no longer drives chip order). The chip row's horizontal scroll
  position is never restored across navigation (e.g. switching tabs — by tap or swipe — and back) —
  it always opens scrolled to show the leftmost chip.
- **R7.3** Each item row shows the name and the needed amount as **"Need: X"**, where X
  defaults to **1** when quantity is empty. Whole-number quantities render without decimals
  ("2", not "2.0"). Rows also expose a store-assignment affordance (chip-based picker dialog
  listing known store options).
- **R7.4** Items can be **drag-reordered**. The order is local-only (never synced to Notion),
  persists across restarts and refreshes, and an item loses its manual position when shopped.
  Dragging must be constrained to the active section (cannot drag into the shopped section).
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
  free-text field; selected chips render their label in the primary blue), and **quantity**
  (leaving it empty clears Qty in Notion). Saving queues a normal offline-safe update.
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

---

## 8. Recipes — User Experience

- **R8.1** The Recipes tab lists all recipes with pull-to-refresh and two sort options,
  presented as chips to the right of the title: **"A–Z"** (alphabetical, the default) and
  **"Recent"** (most recently edited in Notion first, from the page's `last_edited_time`).
  The sort applies to both the Planned section and the main list. The whitespace above the
  "Recipes" title must match the shopping screen's title (same status-bar inset handling).
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
  an ingredient opens the shared edit dialog (R7.16).
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
  existing item's quantity is kept. Existing items are reactivated (un-shopped, trip flag
  cleared) rather than duplicated; a remote-only suggestion goes through the same create path
  and relies on the create-with-merge flush (R5.4) to avoid duplicates. Blank names are
  rejected.
- **R8.6** Recipe screens include loading and error states like every other screen.
- **R8.7** **Editable Ingredients & Instructions.** Both are stored as newline-separated text
  in Notion rich_text properties (§2.2) and edited in place: each section has a pencil that
  swaps its view for a multi-line text field (one item/step per line) with Save/Cancel. Saving
  is **offline-safe** — it queues a `PENDING_UPDATE` on the recipe row (same offline queue as
  the Planned toggle) and the sync worker flushes the full recipe property payload (name,
  ingredients, instructions, planned). Rich_text is chunked to respect Notion's 2000-char
  per-object limit. The **Ingredients** view renders like **ruled paper**: each non-blank line
  is shown with a full-width divider beneath it; an empty section shows a muted placeholder.
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
  a connection is required.
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
  the normal grouped view; the keyboard's action key ("Search") also dismisses the keyboard
  without clearing the query, so the user can always leave the field. Search is a purely local
  filter over the cached rows (no Notion query).

---

## 9. Global UI, Theme & Branding

- **R9.1** Bottom navigation with three destinations: Shopping, Recipes, Settings. The two
  **list** tabs (Shopping, Recipes) form a **horizontally swipeable pager** — a left/right
  swipe moves between them, and their bottom-bar items reflect and drive the current page
  (tapping animates to it). **Settings is not swipeable**: it is a separate route reached only
  by tapping its bottom-bar item; tapping Shopping/Recipes from Settings returns to the pager on
  the chosen tab. The bottom bar shows on both the pager and Settings. Each list tab's ViewModel
  is scoped to the pager's back-stack entry so its data survives swiping; the off-screen page
  composition is disposed (which is what lets the shopping chip row re-open scrolled to the
  leftmost chip, R7.2). The recipe detail is a full-screen route pushed over the pager (no
  bottom bar). A horizontal swipe that begins on a horizontally scrollable child (e.g. the
  store-chip row) scrolls that child first and only pages once it reaches its edge.
- **R9.2** A **global network-activity indicator**: a thin (2 dp) indeterminate progress bar
  overlaid at the top of the screen (below the status bar), visible whenever any Notion HTTP
  request is in flight. Driven by an OkHttp interceptor counting active requests, with the
  "off" transition debounced (~300 ms) so back-to-back requests don't flicker.
- **R9.3** Dark theme only. Accent color is **blue `#06AFFF`** (primary), with a deep-blue
  container (`#0E3A52`) and dark ink surfaces (`#101314` family); errors in an ember/red tone.
  No green accents anywhere.
- **R9.4** The app icon is an adaptive icon built from the Hauly artwork on a solid `#06AFFF`
  background, with the glyph padded to sit inside the adaptive-icon safe zone (~61% of
  canvas). The same artwork appears as the logo on the onboarding screen.
- **R9.5** Every new Compose screen must model loading, empty, and error states explicitly;
  user-visible failures surface as snackbars or inline error views, never silently.

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
