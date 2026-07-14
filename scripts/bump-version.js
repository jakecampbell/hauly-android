#!/usr/bin/env node
// Bumps the app version in app/build.gradle.kts: increments the semantic
// versionName (major/minor/patch) and bumps versionCode by 1.
//
// Usage:
//   node scripts/bump-version.js [major|minor|patch]   (default: patch)
//
// Requires Node 18+. No external dependencies.

const fs = require("fs");
const path = require("path");

const part = (process.argv[2] || "patch").toLowerCase();
if (!["major", "minor", "patch"].includes(part)) {
  console.error(`Unknown bump "${part}". Use: major | minor | patch`);
  process.exit(1);
}

const gradleFile = path.join(__dirname, "..", "app", "build.gradle.kts");
let contents = fs.readFileSync(gradleFile, "utf8");

const nameMatch = contents.match(/versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"/);
const codeMatch = contents.match(/versionCode\s*=\s*(\d+)/);
if (!nameMatch || !codeMatch) {
  console.error("Could not find versionName/versionCode in " + gradleFile);
  process.exit(1);
}

let [major, minor, patch] = nameMatch.slice(1, 4).map(Number);
if (part === "major") { major += 1; minor = 0; patch = 0; }
else if (part === "minor") { minor += 1; patch = 0; }
else { patch += 1; }

const oldName = `${nameMatch[1]}.${nameMatch[2]}.${nameMatch[3]}`;
const newName = `${major}.${minor}.${patch}`;
const oldCode = Number(codeMatch[1]);
const newCode = oldCode + 1;

contents = contents
  .replace(nameMatch[0], `versionName = "${newName}"`)
  .replace(codeMatch[0], `versionCode = ${newCode}`);

fs.writeFileSync(gradleFile, contents);

console.log(`versionName ${oldName} -> ${newName}`);
console.log(`versionCode ${oldCode} -> ${newCode}`);
