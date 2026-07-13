#!/usr/bin/env node
// Normalizes Shopping List item names to lowercase and reports duplicate
// names (post-normalization) so they can be merged manually in Notion.
//
// Usage:
//   NOTION_TOKEN=secret_xxx NOTION_SHOPPING_DB_ID=xxxx node scripts/cleanup-shopping-list.js
//
// Requires Node 18+ (uses global fetch). No external dependencies.

const NOTION_VERSION = "2022-06-28";
const NOTION_BASE_URL = "https://api.notion.com/v1";
const MAX_ATTEMPTS = 5;
const BASE_DELAY_MS = 1_000;
const MAX_DELAY_MS = 30_000;

const token = process.env.NOTION_TOKEN;
const databaseId = process.env.NOTION_SHOPPING_DB_ID;

if (!token || !databaseId) {
  console.error(
    "Missing NOTION_TOKEN and/or NOTION_SHOPPING_DB_ID environment variables."
  );
  process.exit(1);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function backoffMillis(attempt) {
  const exponential = BASE_DELAY_MS * 2 ** (attempt - 1);
  const jitter = Math.random() * BASE_DELAY_MS;
  return Math.min(exponential + jitter, MAX_DELAY_MS);
}

// Retries 429/5xx with exponential backoff + jitter, honoring Retry-After,
// mirroring RetryAfterInterceptor.kt so this script follows the same
// Notion rate-limit contract as the app (REQUIREMENTS.md R2.3).
async function notionFetch(path, options = {}) {
  const url = `${NOTION_BASE_URL}${path}`;
  let attempt = 0;

  while (true) {
    const response = await fetch(url, {
      ...options,
      headers: {
        Authorization: `Bearer ${token}`,
        "Notion-Version": NOTION_VERSION,
        "Content-Type": "application/json",
        ...options.headers,
      },
    });

    if (response.status !== 429 && (response.status < 500 || response.status > 504)) {
      if (!response.ok) {
        const body = await response.text();
        throw new Error(`Notion API error ${response.status}: ${body}`);
      }
      return response.json();
    }

    attempt++;
    if (attempt >= MAX_ATTEMPTS) {
      const body = await response.text();
      throw new Error(`Notion API error ${response.status} after ${attempt} attempts: ${body}`);
    }

    const retryAfterHeader = response.headers.get("Retry-After");
    const retryAfterMs = retryAfterHeader ? Number(retryAfterHeader) * 1_000 : null;
    await sleep(retryAfterMs ?? backoffMillis(attempt));
  }
}

async function fetchAllShoppingItems() {
  const items = [];
  let cursor = undefined;

  do {
    const body = await notionFetch(`/databases/${databaseId}/query`, {
      method: "POST",
      body: JSON.stringify(cursor ? { start_cursor: cursor } : {}),
    });

    for (const page of body.results) {
      const titleProp = page.properties?.Name;
      const name = (titleProp?.title ?? []).map((t) => t.plain_text).join("");
      items.push({ id: page.id, name, url: page.url });
    }

    cursor = body.has_more ? body.next_cursor : undefined;
  } while (cursor);

  return items;
}

async function renameItem(pageId, newName) {
  await notionFetch(`/pages/${pageId}`, {
    method: "PATCH",
    body: JSON.stringify({
      properties: {
        Name: {
          title: [{ text: { content: newName } }],
        },
      },
    }),
  });
}

function normalize(name) {
  return name.trim().toLowerCase();
}

async function main() {
  console.log("Fetching shopping list items...");
  const items = await fetchAllShoppingItems();
  console.log(`Found ${items.length} items.`);

  const byNormalizedName = new Map();
  for (const item of items) {
    const key = normalize(item.name);
    if (!byNormalizedName.has(key)) byNormalizedName.set(key, []);
    byNormalizedName.get(key).push(item);
  }

  console.log("\nNormalizing item names to lowercase...");
  let renamed = 0;
  for (const item of items) {
    const normalized = normalize(item.name);
    if (item.name === normalized) continue;
    await renameItem(item.id, normalized);
    console.log(`  renamed: "${item.name}" -> "${normalized}"`);
    renamed++;
  }
  console.log(`Renamed ${renamed} item(s).`);

  const duplicates = [...byNormalizedName.entries()].filter(([, group]) => group.length > 1);

  if (duplicates.length === 0) {
    console.log("\nNo duplicate item names found.");
    return;
  }

  console.log(`\nFound ${duplicates.length} duplicate name group(s):`);
  for (const [normalizedName, group] of duplicates) {
    console.log(`\n"${normalizedName}" (${group.length} items):`);
    for (const item of group) {
      console.log(`  - ${item.name}  ${item.url}`);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
