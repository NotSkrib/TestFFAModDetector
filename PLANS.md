# PLANS.md — TestFFAModDetector v5.0.0

Implementation plan for two approved work packages on the **existing** Maven Paper plugin in this
repository. Written against the files **on disk** (the git tree is dirty — 7 sources modified against
the single commit `b70b9bc "ErrorSMP Mod Detector v4.2.0"`). Never plan or diff against the commit.

- **Pass 1** — pure rebrand to TestFFA.
- **Pass 2** — replace `mode:`/`blocked-mods:` with a single tick-off `detect:` list plus automatic
  two-tier escalation.

Build tool is **Maven** (`mvn clean package`). There is no `build.sh`/`build.ps1` and no Gradle
wrapper; **do not** introduce a Gradle migration.

**Target platform is Leaf 1.21.11, and "make it for Leaf" means COMPATIBILITY ONLY** (§0.4): bump
the dependency set to `1.21.11` and verify the PacketEvents packet paths still behave correctly
under Leaf's async chunk sender. **No Leaf API coupling** — the plugin still compiles against
`io.papermc.paper:paper-api` `1.21.11-R0.1-SNAPSHOT` (provided) and stays portable to Paper and
Purpur. Leaf ships no anticheat, so there is no anticheat integration, no Leaf detection code, and
no runtime platform branch. PacketEvents API **stays** `2.13.0` (provided), `maven.compiler.release`
**stays** 21.

---

## 0. Current state, as read from disk

Established by reading all 8 sources, both resources, both docs, `pom.xml`, and `config.yml`
(1745 lines). Do not re-derive; treat as ground truth.

`git status` shows **8 modified sources** against the single commit `b70b9bc` (all of them; the two
docs are untracked-clean). The docs are *also* stale relative to the code in one specific way, called
out in §2.3.

| Fact | Value |
| --- | --- |
| `groupId` / `artifactId` / `version` | `xyz.nim` / `errorsmp-moddetector` / `4.2.0` |
| `finalName` | `${project.artifactId}-${version}` → `errorsmp-moddetector-4.2.0.jar` |
| `pom.xml` dependencies **as read on disk** | `io.papermc.paper:paper-api` `1.21.8-R0.1-SNAPSHOT` (provided), `com.github.retrooper:packetevents-spigot` `2.13.0` (provided), `maven.compiler.release` `21` — **all three are being changed or re-confirmed by the platform retarget; see §0.4** |
| Target server platform | **Leaf 1.21.11** (user-confirmed). Compatibility-only scope: no Leaf API coupling, stays portable to Paper/Purpur. |
| Java package | `xyz.nim.modDetectorPlugin` — **stays exactly as-is** |
| Plugin descriptor | `src/main/resources/plugin.yml` (legacy Bukkit, **not** `paper-plugin.yml`) |
| `depend` | `[packetevents]` |
| Command | `/moddetector`, alias `md`, declared `permission: moddetector.admin` |
| Catalog size | 174 mods — CHEAT 72, SUSPICIOUS 86, LAUNCHER 7, UTILITY 9 |
| `punish: true` mods | 74 (the "we can act on this" set). The **shipped `detect:` list is 72 of those 74** — the 2 excluded are `vanilla-spoof` and `geyser-spoof`; `no-brand` ships `punish: false` and was never in the 74. |
| Active sign-probe definitions | **Estimated, not counted** — ~225 total; ~105 belong to the 74 `punish: true` mods, and the **same** ~105 belong to the shipped 72, because the two excluded entries are inert (F1) and contribute no active definitions at all. Definition count ≠ mod count; see §3.1. |
| `hacks:` section in shipped config | `{}` (no custom hack definitions) |
| Detection sources in use | `mods:` + `custom-mods:` (passive+active), plus `hacks:` for extra probes |
| Detection selection today | `mode: blacklist` + `blocked-mods: []` → `ModCatalog.isTracked` |

### 0.1 Hardcoded permission strings (complete list — the Pass 1 rename set)

| File:line | Text |
| --- | --- |
| `ModDetectorPlugin.java:185` | `player2.hasPermission("moddetector.alerts")` |
| `ModDetectorPlugin.java:238` | `player.hasPermission("moddetector.bypass")` |
| `ModDetectorPlugin.java:239` | user-facing `" has moddetector.bypass - check skipped."` |
| `HackCheckManager.java:157` | `player.hasPermission("moddetector.bypass")` |
| `HackCheckManager.java:172` | `object = "moddetector.bypass." + hackDefinition.id()` |
| `HackCheckManager.java:179` | warning text `"…via moddetector.bypass.<id> permissions"` |
| `plugin.yml:19` | `permission: moddetector.admin` |
| `plugin.yml:22/25/28` | the three `permissions:` keys |
| `config.yml:5` | comment `# Tell staff (moddetector.alerts) …` |

`ModDetectorCommand.java` contains **no** hardcoded permission strings (the command's permission
comes from `plugin.yml`). Nothing else in the tree references `moddetector.*`.

### 0.2 `ErrorSMP`-branded strings (complete list — the Pass 1 rename set)

`Msg.java:11` `BRAND`; `ModDetectorPlugin.java:91` enable log line;
`ModDetectorCommand.java:109` help header; `ModDetectorCommand.java:120` status header;
`config.yml:2` header; `plugin.yml:1` name, `:5` author, `:6` description, `:16` command
description; `ADDING-MODS.md:1`; `NOTICE.md:3` and `NOTICE.md:8`.

### 0.3 Findings from reading the code (not in the approved scope — decisions recorded)

**F1 — three shipped catalog entries are currently inert (pre-existing bug).**
`config.yml` uses signal sources `NO_BRAND` (line 839), `VANILLA_SPOOF` (750), `GEYSER_SPOOF` (760).
`ModCatalog.SignalSource` is `{BRAND, CHANNEL, TRANSLATE, KEYBIND, METEOR_VARIANT, UNKNOWN}`, so
`parseSignalSource` throws `IllegalArgumentException`, is caught, and returns `UNKNOWN`
(`ModCatalog.java:133-143`). `Signal.isPassive()` is `BRAND|CHANNEL` and `isActive()` is
`TRANSLATE|KEYBIND|METEOR_VARIANT`, so `UNKNOWN` is invisible to **both** `detect()` and
`activeDefinitions()`. The entries `no-brand`, `vanilla-spoof`, `geyser-spoof` therefore never
detect anything today. Of the three, only `vanilla-spoof` and `geyser-spoof` ship `punish: true`
(`no-brand` ships `punish: false`), so only those two would otherwise have landed in the shipped
`detect:` list as no-ops.
*Recommendation:* **do not** silently fix this inside Pass 2. Adding three enum constants is a
one-line change but it turns on detection paths that have never run in production, with real
false-positive risk on the brand-anomaly heuristics. Ship it as a separate, explicitly-approved
increment after v5.0.0, and for v5.0.0 keep those ids **out** of the shipped `detect:` list so the
default config does not advertise a capability that does not work. Only `vanilla-spoof` and
`geyser-spoof` need excluding — `no-brand` is already outside the set because it ships
`punish: false`. Record all three ids in the commented reference block as usual (they are valid
mod ids and admins may tick them; ticking them is simply a no-op until F1 is fixed).

**F2 — the passive path ignores `skip-bedrock`.** `BedrockDetector.isBedrock` is consulted only in
`HackCheckManager.startCheck`. A Bedrock player who trips a tier-1 *passive* signal while
`hack-checks.on-join.enabled: false` is still alerted and can still be kicked. Pass 2 explicitly
requires bedrock-skip in both passes, so the passive escalation path must gain the guard. This is
a deliberate behaviour change and is specced in §4.6.

**F3 — a bypassed player can be alerted when `passive-delay-ticks > on-join.delay-ticks`.** Today
`passiveDelayTicks` is hardcoded to 5 and `on-join.delay-ticks` ships 20, so the passive task
always wins the race and `willHandleKick` is still `true` (because `markPending` ran at join). The
moment `passive-delay-ticks` becomes configurable (§4.7), the ordering can invert:
`startCheck` runs first, hits the bypass branch, and does `pendingKick.remove(uuid)`, after which
the passive task sees `willHandleKick == false` and alerts a player the admin explicitly bypassed.
Fixed by the global-bypass check at the top of the passive path (§4.6).

**F4 — `%hacks%` is wrong on the passive-only path.** `HackCheckManager.handlePassiveResults`
(line 83) calls `executePunishment(player, set, string, string)` — the same joined string for both
the `hacks` and `mods` placeholders, so a passive-only detection reports sign-probe hits under
`%hacks%`. Routing the passive path through the same merge helper that `finishCheck` uses (which
computes them separately) fixes it as a side effect of §4.5.

**F5 — `ModCatalog.parseMod(String, Map)` line 84** casts the raw `signals` value to
`ArrayList<Signal>` purely to test emptiness and then re-parses into `arrayList2`. It works
(erasure makes the cast a no-op) but it is nonsense. **Do not** drive-by "fix" it unless Pass 2
touches that method, in which case the fix is in scope.

**F6 — `HackCheckManager.configure` takes 7 positional args** and Pass 2 adds five more. Replace
with a record (recommended, §4.7).

**F7 — the redundant same-package imports** in `HackCheckListener.java:18-19` and `SignProbe.java:27`
are decompiler artifacts. **Leave them.** They are not drive-by churn.

## 0.4 Platform retarget — Leaf 1.21.11, compatibility only (user-confirmed)

The target platform is **Leaf 1.21.11**. The user has explicitly defined "make it for Leaf" as
**COMPATIBILITY ONLY**: bump the dependency set and verify that the PacketEvents packet paths still
behave correctly under Leaf's async chunk sender. It is **not** a port.

### What Leaf is, and why that bounds the scope

Leaf is a pure performance fork in the Gale → Pufferfish/Purpur lineage. Its distinguishing features
are **async chunk sending**, a **reversion of the tripwire dupe**, a **configurable UseItem
distance**, and **Sentry** error reporting. **Leaf ships no anticheat** (confirmed from the Leaf
README). Consequences, all of them binding on this plan:

- **No anticheat integration.** There is nothing on the Leaf side to integrate with.
- **No Leaf-detection code.** The plugin must not sniff the server implementation and branch.
- **No runtime platform branch.** No `if (isLeaf())` anywhere.
- **The API dependency stays `io.papermc.paper:paper-api`.** Leaf is a Paper fork and implements
  the same API surface, so the plugin remains portable to Paper and Purpur. Nothing in this plan
  should make it Leaf-only.

The one genuine behavioural concern Leaf raises is **async chunk sending**: chunk loads are no
longer tied to the main thread, so anything that depends on a sign block entity being resident in
the player's view when the fake sign is placed/reverted needs a manual test on Leaf. That is exactly
what the new §7 A0 checklist items assert, and the risk is recorded as **R13**.

### The version tuple

| Item | Before (on disk) | After | Basis |
| --- | --- | --- | --- |
| `io.papermc.paper:paper-api` | `1.21.8-R0.1-SNAPSHOT` | **`1.21.11-R0.1-SNAPSHOT`** | Verified to exist on repo.papermc.io (build 91, timestamp `20260511.115010`); also already present in the local `~/.m2` repository, so this does not require a network fetch. |
| PacketEvents API | `2.13.0` | **`2.13.0` — unchanged** | See rationale below. **Do not bump.** |
| `maven.compiler.release` | `21` | **`21` — unchanged** | Verified against Mojang's own 1.21.11 version metadata (`javaVersion.majorVersion: 21`). 1.21.11 is the **final** Minecraft version to require Java 21. |
| `plugin.yml` `api-version` | `'1.21'` | **`'1.21.11'`** | See rationale below. |

**Why PacketEvents stays at 2.13.0.** PacketEvents is a `provided` dependency, so the copy the
server has installed is the one that runs — our version only affects compilation. Minecraft 1.21.11
support landed in PacketEvents **2.11.0** (2025-12-09), so **2.13.0 already covers the target**.
Compiling against 2.13.0 is forward-compatible with whatever the operator has installed, whereas
compiling against 2.14.0 would pin the operator to exactly 2.14.0 server-side for no benefit.

**Why `api-version` becomes three-part.** Paper's `ApiVersion.getOrCreateVersion` accepts
`major.minor.patch` strings, and paper-api 1.21.11's own bundled `apiVersioning.json` reports
`"currentApiVersion": "1.21.11"` — so `'1.21.11'` is the version-correct declaration. The honest
consequence is recorded in **R12**: a three-part `api-version` is **rejected** by a 1.21.8 server, so
the plugin no longer loads as a fallback on the old target. The user has accepted this; it is
recorded rather than hidden.

### What is explicitly NOT in scope

No `plugin.yml` platform declaration, no `folia-supported`-style flag, no Leaf scheduler calls, no
async-chunk API usage, no changes to `ModDetectorPlugin`'s main-thread assumptions, and no
re-verification of the sign-probe geometry beyond the §7 checklist item. **The retarget itself
touches `pom.xml` (one version literal) and `plugin.yml` (`api-version`) and nothing else.** The
only code-level follow-up anywhere in the plan is the **R11** mitigation, which names an existing
literal in `SignProbe.java` and changes no behaviour.

---

## 1. Conventions to honour

These sources were originally decompiled, so the style is unusually dense. **Match the surrounding
file; do not reformat wholesale.**

- Records for value types; enums with explicit `;` bodies; terse `this.field`; 4-space indent.
- Single-line guard clauses and early returns; no blank line between guard and body where the
  file already omits one.
- Casts are kept explicit (`(Component)`, `(TextColor)`, `(Player)`) because the decompiler emitted
  them. Do not add more, do not remove them.
- `//` line comments for mechanics, `/** */` only where the existing file already has one
  (`HackCheckPacketListener.readUpdateSignSafely`).
- Long explanatory `//` blocks explaining *why* a non-obvious constant exists are idiomatic here
  and must be preserved and extended, not deleted. The `HackCheckManager:185-189` batch-of-80
  comment and the `HackCheckPacketListener` Javadoc both stay verbatim in intent.
- **Every** `onEnable`/`onDisable`/event handler is wrapped in `try { … } catch (Throwable t) { log;
  continue; }`. Never let a plugin exception escape into the server tick.
- **Never** leak filesystem paths, stack traces, or YAML internals into a player-facing message.
  Player-facing errors say what to do; the console log gets the exception.

---

# PASS 1 — rebrand to TestFFA

## 2.1 Acceptance criteria

1. `mvn clean package` produces `target/testffa-moddetector-5.0.0.jar` with zero errors and zero
   new warnings; the jar's `plugin.yml` reads `name: TestFFAModDetector`, `version: 5.0.0`.
2. Server start log contains `TestFFAModDetector enabled. …` and contains **no** occurrence of the
   string `ErrorSMP` (grep the deployed data folder's `latest.log` and the bundled resources).
3. `/md` (no args) prints the TestFFA help header; every MENU row is click-to-fill as before.
4. `/md status` prints the TestFFA status header and the new "ticked vs known" row is absent
   (that is Pass 2 — Pass 1 only changes the header text).
5. A player with `testffa.admin` can run every subcommand. A player with **only** `moddetector.admin`
   can run **none** of them (breaking change, intentional).
6. `grep -ri errorsmp src/ *.md pom.xml` returns nothing **in code or build files** (`src/`,
   `pom.xml`), and in the markdown set the only permitted hits are the historical / provenance
   entries in `NOTICE.md` (which Pass 2 appends to and §2.3 keeps byte-identical) plus this plan's
   own intentional references in `PLANS.md` (title, §0.2, §2.3, R9). **`ADDING-MODS.md` must
   contain no `ErrorSMP` hits at all** — Pass 1 rewrote its title, so anything surviving there is a
   missed rename, not history. Enforced by the companion check in §7.
7. Kick message wording is byte-identical: `"&cUnauthorized modifications detected: <punishable>"`.
8. `Msg.ACCENT` (`TextColor.color(7259903)`) is unchanged.
9. GPLv3 attribution in `NOTICE.md` is preserved verbatim.
10. Platform retarget (§0.4): `pom.xml` resolves `io.papermc.paper:paper-api`
    `1.21.11-R0.1-SNAPSHOT`, `maven.compiler.release` is still `21`, and the PacketEvents pin is
    still `2.13.0`. `unzip -p target/*.jar plugin.yml` shows `api-version: '1.21.11'`.
11. The built jar loads on a real **Leaf 1.21.11** server (with PacketEvents 2.11.0+ installed) and
    also on a Paper 1.21.11 server — i.e. the retarget did not make the plugin Leaf-only.

## 2.2 Changes, file by file

**`pom.xml`**
- `<artifactId>errorsmp-moddetector</artifactId>` → `<artifactId>testffa-moddetector</artifactId>`
- `<version>4.2.0</version>` → `<version>5.0.0</version>`
- **Platform retarget (§0.4), the only dependency change:**
  `io.papermc.paper:paper-api` `1.21.8-R0.1-SNAPSHOT` → **`1.21.11-R0.1-SNAPSHOT`**. This is a
  version literal bump on an existing `provided` dependency — no new dependency, no scope change,
  no repository change. It is deliberately **not** a Leaf artifact: Leaf implements the Paper API.
- `maven.compiler.release` **stays `21`** — verified against Mojang's 1.21.11 version metadata, and
  1.21.11 is the last Minecraft version to require Java 21. Do not "bump it to be safe".
- The PacketEvents pin **stays `2.13.0`** — `provided` scope means the server's installed copy wins
  at runtime, and 1.21.11 support landed in PacketEvents 2.11.0, so 2.13.0 already covers the
  target. **Do not bump to 2.14.0**: that would pin the operator to exactly 2.14.0 server-side for no
  benefit. Full rationale in §0.4.
- `groupId` stays `xyz.nim`. `packaging`, all other properties, repositories, both resource blocks
  (plugin.yml filtered / the rest unfiltered), compiler-plugin 3.13.0 and resources-plugin 3.3.1 all
  unchanged. Do not add or remove anything else.

**`src/main/resources/plugin.yml`**
- `name: ErrorSMPModDetector` → `name: TestFFAModDetector`
- `author: ErrorSMP` → `author: TestFFA`
- `description: TestFFAModDetector - channel-based + sign-probe mod/hack-client detection.`
  (Pass 2 appends `+ two-tier detect list.`)
- `commands.moddetector.description` → `TestFFAModDetector - status, manual checks, and reload.`
  (Pass 2 extends `usage:` with `|detect|ignore`.)
- `commands.moddetector.usage` → add `|detect|ignore` in the Pass 2 increment only.
- `permission: moddetector.admin` → `permission: testffa.admin`
- `permissions:` block keys → `testffa.admin` / `testffa.alerts` / `testffa.bypass`,
  `default:` values unchanged (`op` / `op` / `false`). Descriptions unchanged.
- `depend: [packetevents]`, `version: '${project.version}'`, and the 5-line comment about legacy
  Bukkit vs `paper-plugin.yml` are **untouched**.
- **Changed by the platform retarget (§0.4):** `api-version: '1.21'` → **`api-version: '1.21.11'`**.
  Paper's `ApiVersion.getOrCreateVersion` accepts three-part `major.minor.patch` strings and
  paper-api 1.21.11's bundled `apiVersioning.json` reports `"currentApiVersion": "1.21.11"`, so this
  is the version-correct declaration. **Consequence, recorded in R12:** a three-part `api-version` is
  rejected by a 1.21.8 server, so the plugin no longer loads as a fallback on the old 1.21.8
  target. Accepted by the user.
- Do **not** add a Leaf-specific key here. Compatibility-only scope (§0.4) — no platform declaration,
  no fork detection, no platform branch.
- Do **not** declare `testffa.bypass.<mod-id>` statically — it stays dynamically probed.

**`Msg.java`**
- `BRAND = "ErrorSMP Mod Detector"` → `BRAND = "TestFFAModDetector"`.
  Note the current value has a space while `plugin.yml`'s `name` does not; the target is the
  no-space form, which also matches the jar name. `ACCENT`, `prefix()`, `prefixed()`, `error()`,
  `divider()` unchanged.

**`ModDetectorPlugin.java`**
- `:185` `"moddetector.alerts"` → `"testffa.alerts"`.
- `:238` `"moddetector.bypass"` → `"testffa.bypass"`.
- `:239` user-facing `" has moddetector.bypass - check skipped."` → `" has testffa.bypass - check skipped."`
- `:91` `"ErrorSMP Mod Detector enabled. …"` → `Msg.BRAND + " enabled. …"` (reuse the constant so
  the next rebrand is one line).

**`HackCheckManager.java`**
- `:157` `"moddetector.bypass"` → `"testffa.bypass"`.
- `:172` `"moddetector.bypass." + hackDefinition.id()` → `"testffa.bypass." + hackDefinition.id()`.
- `:179` warning text → `"…via testffa.bypass.<id> permissions"`.
- Extract the duplicated literal into `private static final String BYPASS_PREFIX = "testffa.bypass.";`
  and build the global node as `BYPASS_PREFIX` and the per-mod node as `BYPASS_PREFIX + id`. This
  is justified: Pass 2 re-uses the same node in two places (both passes) and a second hardcoded
  copy is how a rename gets half-done.

**`ModDetectorCommand.java`**
- `:109` `"  ErrorSMP Mod Detector"` → `"  " + Msg.BRAND`
- `:120` `"  ErrorSMP Mod Detector status"` → `"  " + Msg.BRAND + " status"`
- No permission strings here.

**`config.yml`**
- `:2` header → `#  TestFFAModDetector configuration`
- `:5` comment → `# Tell staff (testffa.alerts) in chat whenever anyone is detected, …`
- Lines 11-14 (`mode:` / `blocked-mods:`) are removed in **Pass 2**, not Pass 1.

**`NOTICE.md`** — see §2.3.
**`ADDING-MODS.md`** — see §2.3.

## 2.3 Documentation rewrites

**`NOTICE.md`** is a GPLv3 change log, so history is **appended, never rewritten**.
- Keep paragraphs 1-3 of the current file byte-identical, including the
  `0xnim/mod-detection-plugin` attribution, the GPLv3 statement, and the `Source for this fork is
  available alongside its distributed binary, per GPLv3 §5/§6.` line.
- **Fix the stale reference:** bullet 3 says the catalog was replaced with `mods.yml`, `hacks.yml`.
  That is no longer true — there is a single `config.yml` containing the `mods:`, `hacks:` and
  `custom-mods:` sections. Rewrite that bullet to name `config.yml` and its sections. Do not delete
  the bullet: the catalog's independent provenance (compiled from mods' own public sources, not
  extracted from any closed-source commercial product) is the point of the notice.
- **Append** two new bullets, dated by version, at the end of the list:
  - `v5.0.0` — `Rebranded as TestFFAModDetector for the TestFFA server. Permission nodes moved from
    moddetector.* to testffa.* (moddetector.admin / .alerts / .bypass and the per-mod
    moddetector.bypass.<id>); this is a breaking change for existing permission-group setups.`
  - `v5.0.0` — `Detection scope reworked: the mode:/blocked-mods: blacklist/whitelist include-exclude
    system was replaced by a single tick-off detect: list with automatic tier-2 escalation on any
    tier-1 hit. The two-tier scope is: tier 1 probes only ticked mods; tier 2 re-probes the
    unticked remainder once tier 1 hits, so a detection report names everything the client is
    actually running rather than only the first thing that tripped.`
- Line 3's `ErrorSMPModDetector's channel-based detection lineage…` → `TestFFAModDetector's …`.
- Line 8's `Rebranded as ErrorSMPModDetector for the ErrorSMP server.` stays as a historical entry.

**`ADDING-MODS.md`**
- Title → `# Adding mods to TestFFAModDetector`.
- The `custom-mods:` schema paragraph and the entire "Where to find the real values" section stay,
  **including the instruction not to bulk-import another detector's catalog** — preserve it in
  spirit and wording: *"Write your own `display-name`, `punish` flag, and keys as you find them -
  don't bulk-import another detector's compiled catalog, even reformatted; see `NOTICE.md` for why
  that matters here specifically."* It is a provenance requirement, not a style note.
- Section 3 (`custom-mods:` and `blocked-mods:`) is rewritten by Pass 2 (§4.9).

## 2.4 Operator migration note (put this in the release notes / Pass 1 confirmation)

Because permissions are renamed and the version is a major bump, **server operators must update
their permission groups.** Concretely:

```
# LuckPerms example
/lp group default permission set moddetector.alerts testffa.alerts
/lp group admin   permission set moddetector.admin  testffa.admin
/lp user <name>   permission set moddetector.bypass testffa.bypass
# and for a per-mod exemption:
/lp user <name>   permission set moddetector.bypass.meteor testffa.bypass.meteor
```

Old nodes are **not** aliased. A shim (declaring the old nodes with `default: false` and accepting
either at each check site) was considered and rejected: it doubles the permission-check surface,
hides the migration behind a silent fallback, and the major version bump *is* the migration signal.
`/md` is a `testffa.admin` command, so an operator with only the old node loses all access — that is
intended and must be called out loudly in the release note.

---

# PASS 2 — two-tier "tick-off list" detection scope

## 3.1 Restatement of the design

**One source of truth.** `mode:` and `blocked-mods:` are deleted. A top-level `detect:` list of mod
ids is the only thing that decides *whether a mod is detected at all*. Unticking = deleting the
line; ticking = adding the line. Directly below the active list ships a **commented** reference
block listing every available mod id grouped by category, so an admin can read `config.yml` and see
the whole menu without needing an in-game command. `custom-mods:` and the `mods:` catalog are kept
unchanged as the signal database underneath.

**Two tiers.**
- **Tier 1 (`PRIMARY`)** — the `detect:` list. On join the plugin probes **only** ticked mods.
- **Tier 2 (`FULL`)** — the entire catalog, including everything not ticked. Reached only by
  escalation.
- **ESCALATE ON DETECTION** — the moment tier 1 produces *any* hit (passive brand/channel hit **or**
  sign-probe hit), tier 2 runs automatically so staff get the complete picture of what the client is
  actually running, not just the first thing that tripped.

### The critical design constraint, and why

> **The passive scan must also be restricted to tier 1.**

If the passive scan always ran the full catalog, then every ordinary vanilla / Fabric / Forge /
Sodium player trips on tier 1 immediately (their own brand string and channels are catalogued) and
escalates on **literally every join**. Two-tier detection would then be indistinguishable from
today's single full-catalog probe, except slower. Restricting both the passive scan and the
sign-probe to tier 1 is what makes the feature do anything at all.

### Pass 2 probes only what pass 1 did not cover

Pass 2 probes **only** definitions belonging to mods that were not covered in pass 1 (i.e. unticked
mods), and **carries pass 1's detected set forward**. The union of both passes is therefore exactly
the old full-catalog result, with **no duplicate network traffic**: every definition is put on a
sign at most once per player-check.

*Alternative considered and rejected:* re-probing the **whole** catalog for a second, independent
sample. It sends the same packets twice for no added coverage (a cheat client's translation keys
resolve the same way the second time), it doubles probe duration for exactly the players who can
least afford it, and it doubles the surface for the `HackCheckPacketListener` safe-reader edge
cases that the 384-char sign-line cap creates. Independence of sample was never the goal; coverage
was, and coverage is already complete after one pass over the unticked remainder.

### Bypass and Bedrock, stated explicitly

- `testffa.bypass` skips **everything** — no tier 1, no tier 2, no passive scan, no alert. Checked
  first in **every** entry point (this is also the F3 fix).
- `testffa.bypass.<mod-id>` filters that mod's definitions out of **both** passes. This is subtle
  and must be got right: if the filter were applied only in pass 1, a bypassed ticked mod would
  simply be "uncovered" and reappear in pass 2 through escalation — the bypass would silently do
  nothing. Pass 2 is built as a **set difference on the definition key** (§4.4), which makes
  overlap structurally impossible, and the bypass filter is then applied to what remains.
- Bedrock / Floodgate skip (`hack-checks.skip-bedrock`, default `true`) applies in both passes, and
  — per F2 — is added to the passive path as well.

### What actually gets faster

With the shipped default list and `batch-size: 80`:

| | definitions probed | batches (80/sign) |
| --- | --- | --- |
| **Today**, every player | ~225 (whole catalog) | 3 |
| **After**, clean player | ~105 (ticked only) | 2 |
| **After**, detected player | ~105 + ~120 (remainder) | 2 + 2 = 4 |

So the *total* traffic for a detected player is essentially unchanged (same 225 definitions, one
extra batch boundary and one extra open/revert cycle), and the *reward* is that clean players — the
overwhelming majority — stop being probed for 100 mods nobody is going to act on.

**What these numbers are, precisely — they are estimates of *definition* count, not mod count.**
A mod id can carry several signal definitions (a mod with brand + channel + keybind signals
contributes 3), so 72 ticked mod ids map to ~105 definitions, and the whole 174-id catalog maps to
~225. Only the **mod** counts are verified (174 / 74 / 72, and the per-category table in §4.7);
`~105`, `~120`, `~225`, and the resulting `2`/`3`/`4` batch counts are **order-of-magnitude
estimates** carried over from the original plan and are not independently counted. They were not
adjusted for the 74 → 72 change, deliberately: the two excluded ids are inert (F1) and contribute
**zero** definitions, so the tier-1 definition count is the same whether the ticked set is the 74
or the shipped 72. Do not add precision to them. *To replace them with real numbers at
implementation time* (cheap, and worth doing once the config is generated): log
`catalog.activeDefinitions(PRIMARY).size()` and `activeDefinitions(FULL).size()` from
`sign-probe-debug` on a real server and record both in this section. Until then, treat `2` vs `3`
vs `4` batches as "roughly 2 for a clean player, roughly twice that for a detected one".

## 3.2 Acceptance criteria

1. `mvn clean package` succeeds. `config.yml` no longer contains `mode:` or `blocked-mods:`.
2. `config.yml` contains a top-level `detect:` block followed by a commented per-category reference
   block listing all 174 mod ids.
3. Vanilla client, Fabric+client mods, Forge client: **no** escalation, no alert, no kick, and the
   sign-probe touches only ticked mods. Console (with `sign-probe-debug: true`) shows only ticked
   mod ids being probed.
4. A client running a ticked mod: tier 1 runs, hits, tier 2 runs over the unticked remainder, and
   **one** alert/kick fires **after** tier 2 finishes — naming the ticked mod *and* everything else
   found. No alert fires on the tier-1 hit alone.
5. A client running only an **unticked** mod: tier 1 finds nothing, **no** escalation, no alert.
6. `/md detect <unticked-mod>` then rejoin → now detected, and escalation fires.
7. `/md ignore <ticked-mod>` then rejoin → not detected, no escalation caused by it.
8. `/md detect` / `/md ignore` survive a `config.yml` that still has every hand-written comment
   (byte-diff the file before/after: only the item lines under `detect:` change).
9. Unknown mod id → clear player-facing error, no file write, no stack trace, no path leak.
10. `testffa.bypass` → nothing happens at all. `testffa.bypass.<ticked-mod>` → that mod is absent
    from the report **and** does not reappear via escalation.
11. Bedrock/Floodgate client with `skip-bedrock: true` → no passive alert, no kick, no probe.
12. `/md check <player>` runs the identical two-stage pipeline and reports the identical result as an
    automatic on-join check of the same player.
13. `hack-checks.escalate: false` → tier 1 only, never a second pass, and the config comment says so.
14. An **empty** `detect:` list produces a WARNING at load and detects nothing.
15. Unknown id / duplicate id / mixed-case id in `detect:` produce clear warnings, never an
    exception, and the plugin still enables.
16. Quit mid-escalation → session torn down, sign reverted, no alert, no kick, no leak in
    `activeChecks`.
17. `/md reload` mid-escalation → current pass completes with the union of what was already probed;
    no new pass is started; no exception.
18. `/md list cheat` shows every mod in the category with a ticked/unticked marker; `/md list cheat
    unticked` shows only unticked ones.
19. `/md status` shows `Detect list  N ticked / 174 known` and the escalation state.
20. No player-facing message anywhere contains a filesystem path, a stack trace, or a YAML key path.

## 4.1 `DetectionScope` — the new value type

**New file** `src/main/java/xyz/nim/modDetectorPlugin/catalog/DetectionScope.java`.
A top-level enum (not nested in `ModCatalog`) because it is referenced from `hackcheck`, `command`,
and the plugin class. Explicit `;` body per convention.

```java
package xyz.nim.modDetectorPlugin.catalog;

public enum DetectionScope {
    PRIMARY,
    FULL;

    public DetectionScope escalated() {
        return this == PRIMARY ? FULL : this;
    }
}
```

- `PRIMARY` = tier 1 = ticked mods only.
- `FULL` = tier 2 = the entire catalog, used both as the escalation probe set and as the final
  passive merge scope.
- `escalated()` exists so the transition is stated in one place rather than spelled
  `scope == PRIMARY ? FULL : scope` at three call sites.

## 4.2 `catalog/ModCatalog.java` — signature changes

| Before | After |
| --- | --- |
| `private Set<String> blockedMods` | `private Set<String> ticked` |
| `private boolean whitelistMode` | *(deleted)* |
| `private boolean isTracked(String id)` | `public boolean isTicked(String id)` → `return this.ticked.contains(id);` |
| `void load(ConfigurationSection mods, ConfigurationSection customMods, List<String> blockedMods, boolean whitelist)` | `void load(ConfigurationSection mods, ConfigurationSection customMods, List<String> detectList, Logger warn)` |
| `Set<String> detect(Player)` | `Set<String> detect(Player, DetectionScope scope)` |
| `List<HackDefinition> activeDefinitions()` | `List<HackDefinition> activeDefinitions(DetectionScope scope)` |
| `List<ModDef> listMods(String category)` | `List<ModDef> listMods(String category, TickFilter filter)` |
| `Map<Category,Integer> countsByCategory()` | `Map<Category,Integer> countsByCategory(TickFilter filter)` |
| `int trackedCount()` | `int tickedCount()` **and** `int untickedCount()` |

Details:

- `detect(Player, scope)`: keeps the existing iteration order and result type exactly
  (`HashSet<String>` of mod ids, `ArrayList<>(player.getListeningPluginChannels())` +
  `player.getClientBrandName()` read once). The only change is the guard on line 178:
  `if (scope == DetectionScope.PRIMARY && !this.isTicked(modDef.id())) continue;` — i.e.
  `if (scope != DetectionScope.FULL && !this.isTicked(...)) continue;`.
  **Ordering and dedupe behaviour is preserved exactly** — do not switch to `LinkedHashSet` or
  reorder the `known.values()` iteration.
- `activeDefinitions(DetectionScope scope)`: same guard change on line 205. The `switch` on
  `signal.source().ordinal()` (3→KEYBIND, 4→METEOR, 2→TRANSLATE) and the `keys`/`matches` fallback
  are **untouched**. The two `hack-checks` batch-size/keys-per-line tunables (§4.7) do **not** apply
  here; batching happens in `HackCheckManager`.
- `isTicked` is the only selection rule. `whitelistMode` is deleted outright — there is no second
  mode to get wrong.
- `load(...)` additionally: lower-cases and trims every id from `detectList`; drops ids that are not
  in `known` with a warning; de-duplicates with a warning; and — the one genuinely loud case —
  logs a WARNING when the resulting set is empty, because an empty `detect:` means "detect nothing,
    and escalation can never fire" which is a silent-failure trap:
  `warning("detect: is empty - no mod will be detected at tier 1, and escalation can never fire. Use /moddetector detect <mod> to tick mods on.")`
- New nested enum (mirrors the existing `Category` style):
  `public static enum TickFilter { ALL, TICKED, UNTICKED; }` used by `listMods`/`countsByCategory`.
  `listMods` keeps its existing `display()` case-insensitive sort and its existing
  "no filter = all" behaviour.
- `knows`, `allIds`, `displayName`, `shouldPunish`, `punishmentsFor`, `categoryName`, `knownCount`
  are unchanged.
- Untouched: `parseMod` ×2, `parseSignal`, `parseCategory`, `parseSignalSource`, `toBoolean`,
  `string`, `listOf`, `matchesPassiveSignal`, `matchesRegex`, `matchesChannel`, the `ModDef` /
  `Signal` records, the `Category` / `SignalSource` enums. **F5 stays unless this file is otherwise
  being edited in the same hunk.**

## 4.3 `ModDetectorPlugin.java` — signature changes

| Before | After |
| --- | --- |
| `Set<String> scanPassive(Player)` | `Set<String> scanPassive(Player player, DetectionScope scope)` |
| `private void loadCatalog()` | reads `detect:` (validating), calls the new `catalog.load(...)` |
| `private void loadHackDefinitions()` | builds **two** lists, `hackDefinitionsPrimary` + `hackDefinitionsFull` |
| `private List<HackDefinition> hackDefinitions` | `hackDefinitionsFull` (renamed), plus new `hackDefinitionsPrimary` |
| `hackCheckManager.setDefinitions(List)` | `hackCheckManager.setDefinitions(List primary, List full)` |
| `private void setPunish(String, boolean)` | unchanged |
| `public static enum PunishEditResult` | unchanged, plus new `public static enum DetectEditResult` |
| — | **new** `public DetectEditResult setTicked(String modId, boolean ticked)` |
| — | **new** `public Set<String> knownSignalIds()` (tab completion + validation source) |
| — | **new** `public boolean escalateOnDetection()` |
| `private Map<UUID, Set<String>> passiveResults` | `private Map<UUID, Map<DetectionScope, Set<String>>> passiveResults` |

Details:

- `scanPassive(player, scope)`: body is unchanged apart from the scope argument —
  `new HashSet<>(this.catalog.detect(player, scope))`, stored under `uuid → scope`, returned. The
  cache is now a nested `EnumMap<DetectionScope, Set<String>>` per player so the tier-1 and tier-2
  passive results are tracked separately. `setChannelMods` wiring changes to the new
  scope-aware resolver (§4.4).
- `loadCatalog()`:
  ```java
  ConfigurationSection mods = this.getConfig().getConfigurationSection("mods");
  ConfigurationSection custom = this.getConfig().getConfigurationSection("custom-mods");
  List<String> detectList = this.getConfig().getStringList("detect");
  this.catalog.load(mods, custom, detectList, this.getLogger());
  ```
  `getStringList` returns `[]` for a missing key and for `detect: []`, so an absent key degrades to
  the empty-list warning rather than an NPE.
- `loadHackDefinitions()`: keep `dedupeHackDefinitions` **byte-identical** — its dedupe key
  `id + "\0" + mode + "\0" + key` is reused as the pass-1/pass-2 subtraction key in §4.4, so changing
  it would break the non-overlap guarantee. Build the full list exactly as today, then partition:
  ```java
  this.hackDefinitionsFull  = this.dedupeHackDefinitions(all);
  this.hackDefinitionsPrimary = this.dedupeHackDefinitions(all.stream()
      .filter(d -> this.catalog.isTicked(d.id()) || !this.catalog.knows(d.id()))
      .toList());
  ```
  **Scope rule for `hacks:`-section entries:** a definition whose id is a known mod follows that
  mod's tick state; a `hacks:`-only id (an id the catalog has never heard of — the admin wrote it
  deliberately) is **always tier 1**, because otherwise it would be un-tickable and would only ever
  be reachable through escalation. `knownSignalIds()` returns the union of `catalog.allIds()` and
  the ids of `hackDefinitionsFull`, and is what `/md detect`, `/md ignore`, and the load-time
  validation check against — so a `hacks:`-only id **is** valid input to those commands.
- `handleResult`: the `%detected%` display loop and the `alert-staff` broadcast are unchanged.
- `manualCheck(player, sender)`: now
  `markPending → scanPassive(player, PRIMARY) → hackCheckManager.startCheck(player)`. It drives the
  **same** manager pipeline, so escalation, bypass, and bedrock handling are shared automatically —
  do not duplicate any of that logic in the command.
- `totalHackCount()` → `this.hackDefinitionsFull.size()`;
  `enabledHackCount()` → `this.signProbeActive ? total : 0`. Add
  `primaryHackCount()` → `this.hackDefinitionsPrimary.size()` for the status row.
- `onEnable` gains an outer `try { … } catch (Throwable t) { this.getLogger().log(Level.SEVERE,
  "…failed to enable; disabling.", t); this.getServer().getPluginManager().disablePlugin(this); }`
  and `onDisable` gains `try { … } catch (Throwable t) { log; }`. The existing inner `try` around
  `HackCheckPacketListener.registerIfAvailable` stays as-is.

### `setTicked(String modId, boolean ticked)` — the line-based `detect:` editor

**The config must NOT be re-serialized through `config.set()` + `saveConfig()`.** That would rewrite
all 1745+ lines, destroy every hand-written comment, and delete the commented reference block that
the whole tick-list feature depends on. This method uses the same read-lines / edit-lines / write-lines
shape as the existing `setPunish()` (which already works for the same reason), applied to the list.

Algorithm:

1. `Files.readAllLines(new File(getDataFolder(), "config.yml").toPath(), UTF_8)` → on `IOException`,
   log the exception to console and return `IO_ERROR`.
2. Find the key line: the **first** line whose trimmed form matches `^detect:\s*(\[\s*\])?\s*(#.*)?$`
   and which starts at column 0. If there is **no** such line → `NOT_FOUND`.
   - If a second `detect:` line exists later in the file, log
     `warning("detect: found more than one 'detect:' key in config.yml - using the first (line N). Remove the duplicate.")`
     and carry on with the first. An out-of-order or duplicated key must never crash or silently
     pick a different list.
   - If the line is `detect: [a, b]` (a non-empty **flow** sequence), the item-block shape does not
     exist → return `UNSUPPORTED_SHAPE` (see enum below). Do not try to parse the flow list; the
     shipped format is the block form and the editor only ever writes block form.
3. From `keyIndex + 1`, consume while the line matches `^[ ]+- (.+)$` (space-only indent, so a
   tab-indented or `#`-commented line is never consumed). That regex cannot match
   `#   - meteor` because `#` is not whitespace, so the **commented reference block is structurally
   immune** to this editor. `endIndex` = first non-matching line, or `endOfFile`.
4. Trim trailing blank lines back out of the consumed range (exactly the `for (n = n2; n > n4 + 1 &&
   list.get(n - 1).isBlank(); --n) {}` loop in `setPunish`) and re-emit them after the rewritten
   block, so the blank-line separation before the reference block is preserved.
5. Parse the consumed items into a `LinkedHashSet<String>` of lower-cased ids (order preserved,
   first occurrence wins).
   - ticking an already-ticked id → return `ALREADY_TICKED` **without writing the file**.
   - unticking an id that is not in the list → return `NOT_TICKED` **without writing the file**.
   - unticking the last remaining id → the block is rewritten as `detect: []`? **No.** Write the
     bare `detect:` key with zero items, which is valid YAML for an empty list and keeps the block
     shape stable for the next tick. `getStringList` returns `[]` for it.
6. For a tick, append `  - <canonical-id>` at `endIndex`, where the canonical id is the id as it
   appears in the catalog (validated by the caller against `knownSignalIds()`), not the raw
   user-typed casing. Existing order is **not** re-sorted — preserving admin grouping keeps diffs
   readable; only new ids are appended.
7. `Files.write(path, lines, UTF_8)` → on `IOException`, log and return `IO_ERROR`.
8. `this.reloadAll()` and return `OK`.

`DetectEditResult` (nested in `ModDetectorPlugin`, next to `PunishEditResult`, same style):

```java
public static enum DetectEditResult {
    OK,
    ALREADY_TICKED,
    NOT_TICKED,
    UNKNOWN_MOD,
    UNSUPPORTED_SHAPE,
    NOT_FOUND,
    IO_ERROR;
}
```

**Structural requirement, to be documented in the shipped config and in `ADDING-MODS.md`:** the
`detect:` key must be at column 0, its items must be space-indented `- item` lines directly beneath
it, and nothing but comment/blank lines may sit between the item block and the next key. This is
the same class of structural requirement that `allow`/`disallow` already imposes on the `mods:`
catalog, and the same failure mode (`NOT_FOUND` + a clear message).

## 4.4 `hackcheck/HackCheckManager.java` — the two-stage session

### Injected resolvers

| Before | After |
| --- | --- |
| `Function<UUID, Set<String>> channelMods` | `BiFunction<UUID, DetectionScope, Set<String>> passiveCache` |
| — | **new** `BiFunction<Player, DetectionScope, Set<String>> passiveProbe` |
| `List<HackDefinition> definitions` | `List<HackDefinition> definitionsPrimary` + `definitionsFull` |
| `void setDefinitions(List)` | `void setDefinitions(List primary, List full)` |
| `void configure(boolean, String, int, int, boolean, boolean, String)` | `void configure(HackCheckSettings settings)` (F6) |
| `void handlePassiveResults(Player, Set<String>)` | signature unchanged; **body replaced** (§4.5) |
| `void startCheck(Player)` | signature unchanged; **body restructured** |
| `void finishCheck(Player, Set<String>)` | replaced by `endPass(...)` + `finishCheck(Player, CheckSession)` |

Wiring in `ModDetectorPlugin.onEnable`:
```java
this.hackCheckManager.setPassiveCache((uuid, scope) -> this.passiveResultFor(uuid, scope));
this.hackCheckManager.setPassiveProbe((player, scope) -> this.scanPassive(player, scope));
```
`passiveProbe` is cache-populating and idempotent, so `finishCheck` can use it to guarantee both
scopes are populated even if the scheduled passive task never ran (delay 0 and queue ordering, a
player who joined before a reload, a manual check on a long-online player).

`hackDefinitionsFull` minus `hackDefinitionsPrimary` gives pass 2's definition list, computed as a
**set difference on the same key `dedupeHackDefinitions` uses** (`id \0 mode \0 key`). This is what
makes "no duplicate network traffic" and "the union of both passes is exactly the old full-catalog
result" structural properties rather than hopes. The per-mod bypass filter
(`player.isPermissionSet(node) && player.hasPermission(node)`) is extracted to
`private boolean isModBypassed(Player, String modId)` and applied to **both** lists, with the
"N of M bypassed" warning emitted **once** for the union, not once per pass.

### Session state machine

`CheckSession` gains:

```java
DetectionScope scope;                              // the pass currently being probed
Set<String> coveredKeys;                            // definition keys already put on a sign
Set<String> detectedHacks;                          // ACCUMULATED across passes (unchanged field)
boolean reloadBarrier;                              // set by reload(); blocks a new pass
```
`batches` is rebuilt per pass (it is a `Deque<List<HackDefinition>>`; on escalation it is replaced,
not appended). `batchIndex` resets per pass and is used for sign-spot rotation; add
`passIndex` purely for debug labels.

```
startCheck(player)
 ├─ testffa.bypass                        -> return (no session, no escalation)      [F3 fix]
 ├─ activeChecks already has uuid         -> return (log, second attempt refused)
 ├─ skipBedrock && isBedrock(player)      -> terminal(clean), NO escalation
 ├─ pass1 = definitionsPrimary minus bypassed
 ├─ pass1 empty
 │     └─ endPass(session) with no probe
 └─ new CheckSession(scope=PRIMARY, coveredKeys = keys(pass1)); processBatch(player, session)

processBatch -> SignProbe.openProbe, arm timeout(timeout-ticks)
handleSignResponse -> evaluateBatch -> advanceBatch
advanceBatch -> batches empty ? endPass : processBatch
timeout fires  -> advanceBatch   (a timed-out batch contributes NO detections)

endPass(player, session)
 ├─ restoreBlock(session)                        // revert the fake sign, always
 ├─ escalate = escalateOnDetection
 │            && session.scope == PRIMARY
 │            && !session.reloadBarrier
 │            && !(session.detectedHacks ∪ passiveCache(uuid, PRIMARY)).isEmpty()
 │            && !pass2.isEmpty()
 ├─ pass2 = (definitionsFull minus definitionsPrimary) minus bypassed
 ├─ yes -> session.scope = FULL; session.coveredKeys += keys(pass2)
 │         session.batches = batch(pass2); session.batchIndex = 0
 │         processBatch(player, session)
 └─ no  -> finishCheck(player, session)

finishCheck(player, session)                                     // terminal, exactly once
 ├─ signHits   = session.detectedHacks                            // all passes
 ├─ primary    = escalate fired ? passiveProbe(player, PRIMARY)
 │                             : passiveCache(uuid, PRIMARY)
 ├─ full       = escalate fired ? passiveProbe(player, FULL) : primary
 ├─ all        = signHits ∪ full
 ├─ onResult(player, all)
 ├─ pendingKick.remove(uuid)
 └─ executePunishment(player, all, hacks = join(signHits), mods = join(full))
```

**Escalation trigger = (tier-1 sign hits ∪ tier-1 passive hits), non-empty.** Both halves are
required: a client can trip on a ticked mod's *passive* signal while every sign probe comes back
clean, and that still has to escalate, otherwise the feature misses the most common real case.

**A timeout is not a detection and does not by itself trigger escalation.** Today a timed-out batch
is simply skipped (`advanceBatch` runs, `evaluateBatch` never does), so an unresponsive or lagging
client is never detected by the sign probe. That behaviour is preserved: escalation requires a real
hit, never a silence. Documented explicitly in `ADDING-MODS.md`.

**Bedrock / Floodgate.** Checked in `startCheck` (pass 1, as today) and again in the pass-1→pass-2
transition inside `endPass`, so a client that is skipped never reaches the terminal merge by that
route. Plus the new guard on the passive-only path (§4.5). Skipped players finish clean, are not
alerted, and are not kicked — identical to today.

### The states the plan must get right

| Event | Behaviour |
| --- | --- |
| **timeout on any batch** | `advanceBatch` → next batch, or `endPass`. No detections attributed. Escalation unaffected (a hit from another batch still counts). |
| **player quits mid-escalation** | `HackCheckListener.onQuit` → `cancelCheck(uuid)`: removes the session, cancels `timeoutTask`, `restoreBlock` (only if still online — it is not, so this is a no-op and the sign is already gone with the player), `pendingKick.remove`. Pass 2 never starts. No alert, no kick. No leak in `activeChecks`. |
| **plugin disable mid-escalation** | `ModDetectorPlugin.onDisable` → `hackCheckManager.shutdown()` inside its try/catch: for each session `endSession` (cancel timeout, revert the sign if the player is still online), then clear `activeChecks` and `pendingKick`. No `onResult`, no punishment — a `/reload` must never ban anyone. |
| **second concurrent check** | `startCheck` returns early if `activeChecks.containsKey(uuid)`. Because the escalating session is the *same* object in the same map slot, `isChecking(uuid)` stays `true` throughout pass 2, so `HackCheckListener.onSignChange` and `HackCheckPacketListener` keep forwarding replies with no code change. `manualCheck` refuses with the existing "a check is already running" message. |
| **config reload mid-escalation** | `reloadAll()` sets `reloadBarrier = true` on every in-flight session before swapping the definition lists. The current pass runs to completion with the definition list it started with; `endPass` then goes terminal instead of escalating, and the report is the union of what was already probed. Rationale: the definition set changed underneath the session, so a "pass 2" would be assembled from a different config than pass 1 and the report would not correspond to any single configuration. |
| **no sign spot found** | `startCheck`'s existing early-out becomes `endPass`-equivalent for a fresh session: escalate if the passive tier-1 set is non-empty (there is genuinely nothing more to probe *actively*, but the passive picture is still worth completing), else finish clean. |
| **everything bypassed** | `pass1` and `pass2` both empty → one combined warning → `endPass` → no escalation, no alert, no kick. |

## 4.5 Passive path — `handlePassiveResults`

```java
public void handlePassiveResults(Player player, Set<String> tier1) {
    UUID uuid = player.getUniqueId();
    if (player.hasPermission("testffa.bypass")) { this.pendingKick.remove(uuid); return; }   // F3
    if (this.skipBedrock && BedrockDetector.isBedrock(player, this.bedrockNamePrefix)) { this.pendingKick.remove(uuid); return; }  // F2
    Set<String> passive = new HashSet<>(tier1);
    if (this.settings.escalate() && !tier1.isEmpty()) {
        passive.addAll(this.passiveProbe.apply(player, DetectionScope.FULL));
    }
    this.onResult.accept(player, passive);
    this.pendingKick.remove(uuid);
    this.executePunishment(player, passive, this.join(Set.of()), this.join(passive));
}
```

- The `hacks` placeholder is now correctly empty (F4 fixed) instead of duplicating `mods`.
- The tier-2 passive re-read is **not** network traffic: it re-reads
  `player.getListeningPluginChannels()` / `getClientBrandName()`, which are server-side state. So
  "no duplicate network traffic" holds for the passive path trivially, and there is no reason to
  restrict the re-read to the unticked remainder — restricting it would add a code path for zero
  benefit. The **sign-probe** is where the unticked-only restriction matters, and that is enforced
  via the definition set difference.
- The escalation decision mirrors `endPass` exactly, so a passive-only hit and a sign-probe hit
  produce the same tier-2 escalation.

## 4.6 The `pendingKick` / `willHandleKick` guard

Today: `willHandleKick(uuid) = kickEnabled && (pendingKick.contains(uuid) || activeChecks.containsKey(uuid))`,
called from exactly one place — `HackCheckListener`'s scheduled passive task.

**Decision: rename to `ownsResult(UUID)` and drop the `kickEnabled` gate.**

```java
public boolean ownsResult(UUID uuid) {
    return this.pendingKick.contains(uuid) || this.activeChecks.containsKey(uuid);
}
```

Justification, and it is a correctness requirement of Pass 2 rather than a cleanup: with escalation,
an early tier-1-only alert is not cosmetic, it is **wrong**. The player would be told "detected:
meteor" and then, one second later, told "detected: meteor, wurst, x13-xray, …". A duplicate, and
the first one is misleading. When `hack-checks.kick: false` the old guard returns `false` for a
player who still has a pending on-join check, so the partial alert fires *and* the full one fires
afterwards. Deferring on "an active pipeline owns this player's result", regardless of whether
punishment is enabled, removes the duplicate in every configuration.

This is one renamed method with one call site — a deliberately scoped rename, not drive-by churn.
It changes behaviour in exactly one configuration (`kick: false` + on-join enabled): one alert
instead of two, and the one that arrives is the complete tier-1+2 result.

## 4.7 Configuration — removed, added, and newly-parameterised

### Removed (Pass 2)

```yaml
mode: blacklist
blocked-mods: []
```
and the two comment lines above them (lines 11-12). One source of truth only.

### Added — top-level `detect:` list (replaces them, placed near the top of the file)

```yaml
# ============================================================
#  What gets detected - the tick-off list
# ============================================================
#  Every mod id you list here is a mod this plugin looks for.
#  Untick a mod = delete its line.  Tick a mod = add the line.
#
#  Two tiers:
#    TIER 1 (this list)  - probed on every join. This is the fast path.
#    TIER 2 (everything) - probed ONLY after tier 1 hits something, so a detection
#                          report tells you what the client is ACTUALLY running
#                          instead of just the first thing that tripped.
#  Turn tier 2 off entirely with hack-checks.escalate: false further down.
#
#  Structural requirement: keep the key at column 0 and the items as indented
#  "- item" lines directly beneath it. /moddetector detect and
#  /moddetector ignore edit this block in place - if the shape changes, those
#  commands report that they could not find the list. See ADDING-MODS.md.
#
#  Shipped default: every mod in the catalog that ships `punish: true`, sorted
#  alphabetically, EXCEPT the two inert brand-anomaly entries vanilla-spoof and
#  geyser-spoof - they signal through NO_BRAND / VANILLA_SPOOF / GEYSER_SPOOF,
#  none of which ModCatalog.SignalSource knows, so they are no-ops today (see
#  ADDING-MODS.md). That is 74 - 2 = 72 ids: only the mods this plugin can
#  actually act on. Add the informational ones from the reference block below
#  (utilities, launchers, QoL mods) if you want them reported in tier 1 too;
#  they are still covered by tier 2 on a detection.
detect:
  - aristois
  - ...
```

**Shipped default = the 72 ids (`74 punish: true` minus the 2 inert ones).** Recommendation and
justification:
- An empty list is a **silent total disable**. A server admin who drops in the jar, mistypes the
  key name, or reads `detect:` as "detect the plugin's own catalogue" gets a plugin that detects
  nothing and never says so at WARNING level beyond one line. Failing open toward *detecting the
  things we can punish* is the safer default for an anti-cheat.
- Shipping **everything** ticked would preserve today's exact behaviour but forfeit the entire
  benefit: every clean Fabric player would probe all 174 mods again.
- The 74 `punish: true` mods are the only ones with a working `kick`/`punishments` chain, so the
  default is exactly "detect what we can act on, escalate for the rest". Escalation still means a
  clean player costs 2 batches instead of 3.
- Concretely that yields **~105 tier-1 sign-probe definitions** and **~120 tier-2**. These are
  **estimates of definition count, not mod count**, and are not independently verified — §3.1
  records this and says how to replace them with real numbers. Dropping the two inert ids from the
  ticked set costs **zero** probe traffic, because an `UNKNOWN` signal source is neither
  `isPassive()` nor `isActive()` (F1) and therefore contributes no definitions to either pass —
  which is why "~105 tier-1" is identical for the 74 and for the shipped 72.

**Exactly which ids are excluded, and why the count is 72 and not 74.** The rule is "every catalog
entry with `punish: true`, minus the inert brand-anomaly heuristics". The per-category breakdown is
**verified ground truth**, counted per category from the shipped `config.yml` — not an estimate:

| Category | Total | `punish: true` | `punish: false` | Ticked in the shipped `detect:` |
| --- | --- | --- | --- | --- |
| CHEAT | 72 | **71** | 1 | **69** (71 − the 2 excluded) |
| SUSPICIOUS | 86 | **3** | 83 | **3** |
| LAUNCHER | 7 | 0 | 7 | 0 |
| UTILITY | 9 | 0 | 9 | 0 |
| **Total** | **174** | **74** | **100** | **72** |

The 3 `punish: true` SUSPICIOUS entries are exactly `auto-armor`, `seedfinder`, and
`health-indicators`. The count of catalog entries is 174 — a naive regex over `config.yml` can
briefly appear to report 175, because the `on-join` key inside `hack-checks:` matches a similar
shape; it is a `hack-checks:` sub-key, **not** a mod, and 174 is correct.

So the generator must exclude `vanilla-spoof` and `geyser-spoof` **by name** — both are category
CHEAT, which is why the CHEAT ticked count is 69 and not 71 — with the comment above saying why. A
blanket "skip anything that looks inert" filter is not possible, and `no-brand` needs no exclusion
because it was never in the `punish: true` set. Both excluded ids still appear in the commented
reference block below, as valid ids an admin may tick (ticking them is a no-op until F1 is fixed).
See **R7**.

### Added — commented reference block, immediately below the list

```yaml
# ------------------------------------------------------------
#  Full menu of available mod ids, by category. Nothing here is
#  read by the plugin - it exists so you can pick ids without
#  needing /moddetector list in game. Copy any line above into
#  the detect: list above to tick it on.
#
#  CHEAT (72)
#  # meteor
#  # meteor-plus
#  ...
#  SUSPICIOUS (86)
#  # baritone
#  ...
#  LAUNCHER (7)
#  # badlion
#  ...
#  UTILITY (9)
#  # sodium
#  ...
#  UNKNOWN (0)
#  ...
# ------------------------------------------------------------
```

All 174 ids, one `#  <id>` line each, grouped under the **five `ModCatalog.Category` enum values in
declaration order** — `CHEAT` / `SUSPICIOUS` / `LAUNCHER` / `UTILITY` / `UNKNOWN` — with the same ids
as the `mods:` catalog below, so the two blocks can be diffed by eye. **Generate the headers from the
enum, never from a hand-maintained list**, so the block cannot drift from the enum when a category
is added.

`UNKNOWN` renders **empty apart from its header**, and that is correct, not a bug: the shipped
`config.yml` assigns **zero** entries to it (72 + 86 + 7 + 9 = 174 accounts for the whole catalog).
It is still a real category an admin may assign by hand — e.g. to a custom mod whose category they
do not recognise — so the section must be present in the generated block even with a count of 0, and
`/moddetector list unknown` must keep working.

This block is what makes the feature usable for an admin who never runs a command. **This is
exactly the content that a `saveConfig()` round-trip would destroy** — which is why the line editor
exists.

### Added/changed inside `hack-checks:`

| Key | Default | Meaning |
| --- | --- | --- |
| `escalate` | `true` | Tier-2 escalation. `true` = a tier-1 hit triggers a full-catalog second pass (complete reports). `false` = tier 1 only; faster for the detected player, but the report names only ticked mods. **This single flag is both the escalation switch and the "speed over completeness" switch** — two knobs for one axis invite misconfiguration. |
| `passive-delay-ticks` | `5` | Ticks after join before the tier-1 passive scan runs. **Currently hardcoded to 5 and the unused `DEFAULT_PASSIVE_DELAY_TICKS` field is the residue**; the pluggable point is the F3 ordering fix. `0` = scan on the next tick. |
| `timeout-ticks` | `40` | Per-batch reply timeout. **Currently hardcoded to 40** in `reloadAll`'s `configure(...)` call. |
| `between-batch-ticks` | `0` | Delay between sign batches. **Currently hardcoded to 0.** Raise it if large catalogues make the probe burst noticeable. |
| `batch-size` | `80` | Definitions packed onto one sign (4 lines × 20). **Currently hardcoded to 80.** Lower it if a large catalogue makes the probe visible; the cap is 4 lines × `keys-per-line`. |
| `keys-per-line` | `20` | Definitions per sign line, 1-20. **Currently hardcoded to 20**, and the value is protocol-driven: the real cap is ~384 **decoded** chars per line, and real key strings run 40-60 chars, so 20 is the safe max. Comment must repeat the `HackCheckManager:185-189` reasoning and point at `HackCheckPacketListener`. Clamp to `1..20` at load, warn if out of range. |
| `skip-bedrock` | `true` | **Already read by `reloadAll` but UNDECLARED in the shipped config.** Declare it with the default, and extend it to the passive path (F2). |
| `bedrock-name-prefix` | `"."` | **Also already read but UNDECLARED.** Declare it. |

Unchanged: `kick` (`true`), `kick-message` (**wording unchanged, byte-identical**),
`default-punishments` (`[]`), `on-join.enabled` (`true`), `on-join.delay-ticks` (`20`),
`on-join.only-first-join` (`false`), `sign-probe-debug` (`false`). Also unchanged: `alert-staff`,
`detection-log.enabled`, `detection-log.file`, `custom-mods: {}`, `hacks: {}`, `mods:`.

`HackCheckSettings` (new record, `hackcheck` package, F6) — replaces the 7-positional
`configure(...)`:
```java
public record HackCheckSettings(boolean kick, String kickMessage, int timeoutTicks,
        int betweenBatchTicks, boolean debug, boolean skipBedrock, String bedrockNamePrefix,
        boolean escalate, int batchSize, int keysPerLine) {
}
```
All numeric fields clamped with `Math.max`/`Math.min` **at the boundary** (in `ModDetectorPlugin`
where config is read), not inside the manager, matching the existing convention that
`HackCheckManager.configure` already clamps.

### Deliberately NOT made configurable

- `HackCheckPacketListener.MAX_LINE_CHARS = 384` and `MAX_DECLARED_BYTES = 65535` — protocol
  constants tied to the 1.20.5+ `update_sign` wire format. Configuring them can only desync clients.
- `SignProbe.HORIZONTAL_OFFSETS` (24) / `Y_OFFSETS` (6) — probe geometry, not an admin preference.
  Exposing it invites an admin to pick an offset set that places the fake sign somewhere the client
  will actually render/edit, which is exactly the fragile part. Keep it internal; the
  `sign-probe-debug` flag is the supported diagnostic.
- `HackCheckManager.randomFallback()`'s `___mdp___` prefix — cosmetic, and changing it changes the
  canary pattern that `evaluateBatch` matches on.

## 4.8 `hackcheck/HackCheckListener.java`

- `onJoin`: the scheduled passive task calls
  `((ModDetectorPlugin) this.plugin).scanPassive(player, DetectionScope.PRIMARY)`.
  **This single argument is the entire implementation of the critical design constraint** — the
  passive scan is now restricted to tier 1. Keep the existing
  `if (!player.isOnline() || !this.firstJoinSeen.contains(...)) return;` re-validation and the
  `set.isEmpty()` early return.
- The guard becomes `if (this.manager.ownsResult(player.getUniqueId())) return;` (was
  `willHandleKick`, §4.6).
- The `on-join.enabled` / `on-join.delay-ticks` / `only-first-join` scheduling and the synchronous
  `markPending(uuid)` stay exactly as they are. `markPending` must still run **before** the delayed
  `startCheck`, because that is what tells the passive task at `passiveDelayTicks` to defer.
- Wrap the body of `onJoin`, `onQuit`, and `onSignChange` each in
  `try { … } catch (Throwable t) { this.plugin.getLogger().warning("[HackCheck] …"); }`.
- `onSignChange` and `onQuit` otherwise unchanged.

## 4.9 `command/ModDetectorCommand.java`

**Subcommands** — `SUBCOMMANDS` becomes
`List.of("help", "status", "hacks", "check", "history", "list", "allow", "disallow", "detect", "ignore", "reload")`.
`MENU` gains two entries and one description rewrite:

```java
new Entry("allow <mod>",    "Does a detection kick? No - still logged/alerted", true),
new Entry("disallow <mod>", "Does a detection kick? Yes - make it grounds again", true),
new Entry("detect <mod>",   "TICK a mod on: add it to the detect: list (tier 1)", true),
new Entry("ignore <mod>",   "UNTICK a mod: remove it from the detect: list", true),
```

**The two axes must be legible and must not be conflated.** They are independent:
`detect`/`ignore` answer *"is this mod detected at all?"*; `allow`/`disallow` answer *"does a
detection of it kick?"*. A mod can be ticked and allowed (detected, reported, not punished), or
unticked and disallowed (never detected, so the punish flag is moot). The MENU descriptions and the
`list` hover both say which axis they are on. Naming: `enable`/`disable` was **rejected** precisely
because it reads like the punish flag; `tick`/`untick` is accurate to the config but awkward in
chat; `detect`/`ignore` names the list being edited and tab-completes over mod ids, which makes it
unambiguous in practice (`check` also exists but takes a *player*, and completion resolves that).

**`setDetect(CommandSender, String[], boolean ticked)`** — mirrors `setPunish` exactly:
length check → `catalog`-backed validity check → `plugin.setTicked(id, ticked)` → switch over
`DetectEditResult` with one player-facing message per case:
- `OK` → `<Display> is now <ticked ? "ticked - probed in tier 1" : "unticked - only covered by tier 2 escalation">`
- `ALREADY_TICKED` → `<Display> is already in the detect: list.`
- `NOT_TICKED` → `<Display> isn't in the detect: list.`
- `UNKNOWN_MOD` → `Unknown mod id 'x'. Hover an entry in /moddetector list <category> to see its id.`
- `UNSUPPORTED_SHAPE` → `The detect: list in config.yml isn't in the block form this command edits. Use the detect: key with one "- id" per line (see ADDING-MODS.md).`
- `NOT_FOUND` → `Couldn't find a detect: list in config.yml's to edit. Keep the bundled structure - see ADDING-MODS.md.`
- `IO_ERROR` → `Failed to read/write config.yml - check the console for details.`
  (the same wording `setPunish` already uses — console gets the exception, chat does not.)
Validity is checked against `plugin.knownSignalIds()` (catalog ids ∪ `hacks:`-section ids), so a
`hacks:`-only id works with these commands too.

**`list(CommandSender, String[])`** — decision on how to surface unticked mods:

> **`/moddetector list [category] [all|ticked|unticked]`, defaulting to `all`, with every entry
> carrying an explicit ticked marker in the visible text and the state in the hover.**

Justification for this over the two alternatives:
- *A flag on `list`* beats a new subcommand: it keeps one "browse the database" mental model, reuses
  the existing category click-to-fill flow, needs no new row in the help menu beyond one, and
  tab-completion is a one-line `case` addition. A new subcommand would have to be discovered before
  an admin learns the unticked mods exist — the very thing the feature hides by default.
- *Hover-only* is rejected on its own: the admin's actual question when scanning a 72-entry
  category is "which of these am I looking for?", and hover is not scannable and does not exist at
  all in the server console or in a log paste. But hover is the right place for the **detail**
  (id, ticked state, punish state, whether the entry has active signals, which tier it would be
  probed in).
- Defaulting to `all` rather than `ticked` is deliberate. With the tick-list feature, an un-ticked
  CHEAT mod is a *safety bug waiting to happen*, not noise — the admin needs to see it in the same
  list as the ticked ones. The marker makes the difference scannable; filtering is the follow-up
  action, not the default view.

Marker and colours: reuse the existing `bool(boolean, String, String)` helper (`✔` green /
`✖` red) as the visible prefix, so `list` looks like `status` and `hacks` already do. Hover becomes
id (dark grey) + `ticked: true|false` (green/red) + `punish: true|false` (red/green) + the tier it
is probed in. The existing `countsByCategory()` summary gains the same treatment, and with the
shipped default list the summary line for the largest category reads exactly:
`CHEAT: 72 (69 ticked)`. Those two numbers are **verified, not illustrative** — they are counted
per-category from the shipped `config.yml` in the table in §4.7, where CHEAT is 72 total of which
71 ship `punish: true`, minus the two excluded inert entries (`vanilla-spoof`, `geyser-spoof`).
The other three category lines follow from the same table and are listed in §4.7 as well.

**`status(CommandSender)`** — header switches to `Msg.BRAND + " status"` (Pass 1). Row changes:
```java
statRow("Detect list",   Component.text(tickedCount + "/" + knownCount, WHITE)
                                    .append(text(" ticked", DARK_GRAY)))
statRow("Tier 2 probes", Component.text(fullHackCount + " definitions", WHITE)
                                    .append(text(" (tier 1: " + primaryHackCount + ")", DARK_GRAY)))
statRow("Escalation",     bool(escalateOnDetection(), "on - tier 1 hit runs the full catalog", "off - tier 1 only"))
```
Keep the existing `Sign-probe definitions`, `Sign-probe`, and `Kick enforcement` rows. The old
`Known mods … (N tracked)` row is replaced by the two rows above, since "tracked" no longer exists
as a concept and "known" is now visible as the denominator of `Detect list`.

**Tab completion** — `onTabComplete`:
- `stringArray.length == 1`: `SUBCOMMANDS` (unchanged mechanism).
- `case "detect", "ignore"` → `prefixMatch(plugin.knownSignalIds(), args[1])`.
- `case "list"` → when the arg index is 2, offer
  `List.of("all", "ticked", "unticked", "cheat", "suspicious", "launcher", "utility", "unknown")`;
  at index 1, keep the current category list.
- `case "allow", "disallow"` → keep `catalog().allIds()` (unchanged; the punish axis edits the
  `mods:` catalog, not the `detect:` list).
- Unchanged: `check` over online players, `history` over online ∪ recorded names.

**`hacks` subcommand** → report both tiers:
`Tier 1 (detect list): <primary>/<total> | Tier 2 (full catalog): <total>`.

## 4.10 `catalog`-level UI support (new methods)

```java
public boolean isTicked(String id)                 // replaces isTracked
public int tickedCount()
public int untickedCount()
public Set<String> knownSignalIds()               // NEW, on ModDetectorPlugin
public boolean escalateOnDetection()              // NEW, on ModDetectorPlugin
public int primaryHackCount()                     // NEW, on ModDetectorPlugin
```

---

## 5. Implementation sequence

Each step is independently compilable and independently verifiable. `mvn clean package` is the
gate after every step that touches Java. **Pass 1 must be complete and committed before Pass 2
starts** — it is a clean, revertable rebrand, and mixing it into the refactor makes a bad day
unrecoverable.

### Pass 1

| # | Step | Verify |
| --- | --- | --- |
| 1.1 | `pom.xml`: artifactId, version, **plus the platform retarget (§0.4) — `paper-api` `1.21.8-R0.1-SNAPSHOT` → `1.21.11-R0.1-SNAPSHOT`, leaving `maven.compiler.release` at 21 and the PacketEvents pin at 2.13.0**. `plugin.yml`: name, author, description, `permission:`, `permissions:` block, **and `api-version: '1.21'` → `'1.21.11'`**. | `mvn clean package` → jar is `testffa-moddetector-5.0.0.jar`; `unzip -p target/*.jar plugin.yml` shows `name: TestFFAModDetector`, `api-version: '1.21.11'`, and three `testffa.*` nodes. Resolve check: paper-api 1.21.11, PacketEvents 2.13.0, release 21, no new dependency, no Leaf artifact. |
| 1.2 | `Msg.BRAND` + the two `ModDetectorCommand` headers + the `ModDetectorPlugin` enable log line (switching all three to `Msg.BRAND`). | `mvn clean package`; grep the jar's strings for `ErrorSMP` → none. |
| 1.3 | Permission strings: `ModDetectorPlugin:185/238/239`, `HackCheckManager:157/172/179` + extract `BYPASS_PREFIX`. | `mvn clean package`; `grep -rn "moddetector\.\(admin\|alerts\|bypass\)" src/` → only the `detect` list/command path from Pass 2 will re-match; no hits under `src/main/java`. |
| 1.4 | `config.yml` lines 2 and 5 comments. | Byte-diff shows only those two comment lines changed. |
| 1.5 | `NOTICE.md` + `ADDING-MODS.md`. | Re-read `NOTICE.md`: GPLv3 paragraph + `Source for this fork…` line intact; no `mods.yml`/`hacks.yml`; new v5.0.0 bullets appended; `ADDING-MODS.md` still carries the do-not-bulk-import instruction. |
| 1.6 | Release notes / confirmation message with the LuckPerms migration block (§2.4). | — |

### Pass 2

| # | Step | Depends on | Verify |
| --- | --- | --- | --- |
| 2.1 | New `catalog/DetectionScope.java`. | — | `mvn clean package`. |
| 2.2 | `ModCatalog`: `ticked` set, `isTicked`, `load(detectList)`, `detect(Player, scope)`, `activeDefinitions(scope)`, `TickFilter`, `listMods`/`countsByCategory(filter)`, `tickedCount`/`untickedCount`, load-time validation + warnings. | 2.1 | `mvn clean package` **will fail** at this point — `ModDetectorPlugin`/`HackCheckManager` still call the old signatures. Either fix the callers in the same step or mark the old methods `@Deprecated` for one step and delete them in 2.3. Recommended: land them together. |
| 2.3 | `config.yml`: insert the `detect:` block + commented reference block; delete `mode:`/`blocked-mods:`; add the 7 new `hack-checks` keys; update the `mods:` header comment (which currently references blacklist mode). | — | `mvn clean package`; `/md reload`; the empty-list WARNING fires as expected. |
| 2.4 | `ModDetectorPlugin`: `scanPassive(player, scope)`, nested passive cache, `loadCatalog` reading `detect:`, `loadHackDefinitions` partitioning into primary/full, `setDefinitions(primary, full)`, `setPassiveCache`/`setPassiveProbe` wiring, `primaryHackCount`, `totalHackCount` → full list, `configure(HackCheckSettings)`, top-level try/catch in `onEnable`/`onDisable`. | 2.2, 2.3 | `mvn clean package`; `/md reload` with a deliberately bogus `detect:` id → one clear WARNING, plugin still enabled, `/md status` still works. |
| 2.5 | New `hackcheck/HackCheckSettings` record; `HackCheckManager`: two definition lists, `isModBypassed`, the two-stage `startCheck`/`endPass`/`finishCheck`, `passiveProbe`/`passiveCache`, the new `handlePassiveResults`, `ownsResult`, `reloadBarrier`, batch/keys-per-line from settings. **`SignProbe`: replace the bare block-entity-type literal `7` with `private static final int SIGN_BLOCK_ENTITY_TYPE_ID = 7;` plus the provenance comment (R11) — naming only, no behaviour change.** | 2.4 | `mvn clean package`; `sign-probe-debug: true` on a vanilla client → exactly the ticked mod ids appear in the debug lines, and **no second pass**. |
| 2.6 | `HackCheckListener`: `scanPassive(player, PRIMARY)`, `ownsResult` guard, per-handler try/catch. | 2.5 | Vanilla client, no alert, no kick. Fabric+client-mods client, no alert, no kick, and the debug log shows tier-1 ids only. |
| 2.7 | `ModDetectorCommand`: `detect`/`ignore` + `setDetect`, `DetectEditResult` mapping, `SUBCOMMANDS`/`MENU`, `list` marker + third arg, `status` rows, `hacks` two-tier line, tab completion. | 2.4 | `/md detect wurst` then `/md ignore wurst`; byte-diff `config.yml` — **only** item lines under `detect:` changed, all ~180 comment lines of the reference block intact. `/md detect nonexistentmod` → `UNKNOWN_MOD`, no write. |
| 2.8 | `ModDetectorPlugin.setTicked` — the line-based editor. | 2.7 | Every `DetectEditResult` case exercised against a real file: unknown id, duplicate, `detect: []`, `detect: [a, b]` (→ `UNSUPPORTED_SHAPE`), missing `detect:` key (→ `NOT_FOUND`), read-only file (→ `IO_ERROR`). |
| 2.9 | `HackCheckManager.shutdown`/cancel paths + `reloadAll` reload-barrier wiring. | 2.5 | Reload mid-escalation; quit mid-escalation; `/reload` (plugin disable) mid-escalation. |
| 2.10 | `ADDING-MODS.md` (§6) + `NOTICE.md` (§2.3 bullets) + `config.yml` reference-block header. | 2.3, 2.7 | Docs match the shipped file exactly — the id list in the reference block is the same as in `mods:`. |
| 2.11 | Full manual checklist (§7), end to end, on a live server. | all | — |

---

## 6. Documentation content to write (Pass 2)

**`config.yml` — the `detect:` block comment** must state, in this order: what ticking means; the
two tiers and that tier 2 only runs on a tier-1 hit; that `hack-checks.escalate: false` disables
tier 2; the structural requirement (column-0 key, `- item` lines beneath, nothing else in between)
because `/moddetector detect|ignore` edits that block in place; that the punish axis
(`allow`/`disallow` → `punish:`) is a **separate** question from this one; **and that the shipped
default is the 72 `punish: true` entries minus `vanilla-spoof` and `geyser-spoof`, naming those two
and pointing at `ADDING-MODS.md` for the reason.** The exact wording is specced in §4.7 — the last
item is a **required** item, not an optional extra, because §4.7's comment block ends with
"see ADDING-MODS.md" and that cross-reference is only honest if this section actually writes the
subsection it points at. Both halves of that cross-reference are committed to here.

**`ADDING-MODS.md`**
- New **section 0, before the current section 1**: `# The detect: list - choosing what gets
  detected`. Content: the tick/untick mechanic; tier 1 vs tier 2; **"the report changes shape"** —
  after escalation a detection names everything found, not just the ticked mod, so `punishments:`
  `%detected%`/`%mods%`/`%hacks%` will name unticked mods too, and admins writing punishment
  commands must expect that; the reference block; `/moddetector detect|ignore`; the structural
  requirement.
- **New subsection within section 0 — required, and it is the target of §4.7's config-comment
  cross-reference: "Three mods that ship un-ticked."** Content, in this order:
  - `vanilla-spoof`, `geyser-spoof`, and `no-brand` ship **un-ticked** in the default `detect:`
    list. Their `SignalSource` values — `VANILLA_SPOOF`, `GEYSER_SPOOF`, and `NO_BRAND` — **do not
    exist** in `ModCatalog.SignalSource`, which is `{BRAND, CHANNEL, TRANSLATE, KEYBIND,
    METEOR_VARIANT, UNKNOWN}`. `parseSignalSource` therefore throws, the exception is caught, and
    the source resolves to `UNKNOWN` — and `UNKNOWN` is neither `isPassive()` (`BRAND|CHANNEL`) nor
    `isActive()` (`TRANSLATE|KEYBIND|METEOR_VARIANT`), so these three ids are invisible to **both**
    `detect()` and `activeDefinitions()` and can never match anything (F1).
  - Ticking them is **harmless**: it is a silent no-op, not an error, and they remain valid input
    to `/moddetector detect`. It simply does nothing until the enum gap is fixed.
  - **The two reasons must be stated separately, or the text misleads.** `vanilla-spoof` and
    `geyser-spoof` ship `punish: true` and were **explicitly excluded by name** from the
    `punish: true` set (which is why the shipped list is 74 − 2 = 72, and why CHEAT ticks 69 of its
    71 `punish: true` entries). `no-brand` ships **`punish: false`** and was therefore **never in
    that set** — it is un-ticked automatically, not excluded. Both are un-ticked; the reason differs.
  - **When the enum gap is fixed:** ship `vanilla-spoof` and `geyser-spoof` with `punish: false`
    first and promote them only after watching real alerts on a real player base, because they are
    brand-anomaly heuristics with genuine false-positive potential (locale quirks, offline-mode
    clients, odd brand strings). This is the same advice R7 records — keep the two consistent, and
    do not let the doc and the risk drift apart.
- Section 1 (`mods:`) — add one line: an entry here is *not* detected until its id is in
  `detect:`. Signals declared but unticked are still probed in tier 2.
- Section 2 (`hacks:`) — add: a `hacks:`-only id is always tier 1 (it is not a catalog mod, so
  there is no tick state to consult); a `hacks:` id that matches a catalog mod follows that mod's
  tick state.
- Section 3 → retitle `# custom-mods: and the detect: list`. Keep the `custom-mods:` schema
  paragraph (add: "a `custom-mods:` id is only detected once it is ticked in `detect:`"), and
  replace the `mode:`/`blocked-mods:` paragraph entirely.
- Keep "Where to find the real values" **and** the do-not-bulk-import instruction, unchanged in
  intent (§2.3).
- Keep "After editing" — `/moddetector reload` or restart.

**`NOTICE.md`** — the two v5.0.0 bullets from §2.3, plus the `mods.yml`/`hacks.yml` fix. Do not
rewrite the existing bullets.

---

## 7. Manual test / verification checklist

Run against a real **Leaf 1.21.11** server, with **PacketEvents 2.11.0 or newer installed on that
server** (1.21.11 support landed in 2.11.0; the plugin compiles against 2.13.0 and runs against
whatever the server has — §0.4), `hack-checks.sign-probe-debug: true` for the probe cases, and
`detection-log.enabled: true` so JSONL output can be diffed.

*Note on the two `ErrorSMP` assertions — they are not in conflict, do not reconcile them into one
another.* §2.1 criterion 6 is the **broad** pass: `grep -ri errorsmp src/ *.md pom.xml` and it
tolerates `NOTICE.md`'s historical GPLv3 entries, because that history must survive. The check in
**Build / static** below is the **deliberately stricter** subset: code and build files only
(`src/ pom.xml`, no `*.md`), expecting **zero** hits, because a branded string in compiled code or
in the pom is always a missed rename with no defensible excuse. The `*.md` half is then enforced by
its own check in the same block, which asserts the residual hits are confined to `NOTICE.md` (plus
this plan). Between them all three are covered; each is narrow on purpose.*

**Build / static**
- [ ] `mvn clean package` → `target/testffa-moddetector-5.0.0.jar`, no errors.
- [ ] `unzip -p target/testffa-moddetector-5.0.0.jar plugin.yml` → `name: TestFFAModDetector`,
      `version: 5.0.0`, `main: xyz.nim.modDetectorPlugin.ModDetectorPlugin`,
      `depend: [packetevents]`, `api-version: '1.21.11'`, three `testffa.*` nodes, `version` still
      interpolated (not literal).
- [ ] `mvn dependency:tree` / read the pom → `paper-api` `1.21.11-R0.1-SNAPSHOT`,
      PacketEvents `2.13.0`, `maven.compiler.release` `21`. No Leaf artifact, no new dependency.
- [ ] `grep -rn "ErrorSMP" src/ pom.xml` → nothing. `grep -rn "moddetector\.\(admin\|alerts\|bypass\)" src/main/java` → nothing.
- [ ] **Markdown half of §2.1 criterion 6, which the check above does not cover:**
      `grep -rli errorsmp *.md` → the **only** files listed are `NOTICE.md` (its GPLv3 history, which
      must be preserved verbatim per §2.3) and `PLANS.md` (this file, whose title and §0.2/§2.3/R9
      legitimately name the old brand). `ADDING-MODS.md` must return **no hits at all** — Pass 1
      rewrote its title, so any surviving `ErrorSMP` there is a missed rename, not history.
      This is what protects the GPLv3 provenance requirement: a careless rewrite or truncation of
      `NOTICE.md` that dropped the `0xnim/mod-detection-plugin` attribution, the GPLv3 statement, or
      the `Source for this fork…` line would pass every other check in this section, and this is the
      only check that fails it.
- [ ] `grep -n "^mode:\|^blocked-mods:" src/main/resources/config.yml` → nothing.
- [ ] `grep -c "^  - " config.yml` inside the `detect:` block == **72**.
- [ ] The 72 ids are exactly the `punish: true` entries minus `vanilla-spoof` and `geyser-spoof`;
      `no-brand` is absent because it ships `punish: false` (it is *not* one of the exclusions).

**A0. Platform / Leaf-specific**
- [ ] Plugin enables cleanly on a real **Leaf 1.21.11** server with PacketEvents 2.11.0+; console
      shows `TestFFAModDetector enabled. …` and no `Unsupported API version` rejection.
- [ ] Portability: the same jar enables on a **Paper 1.21.11** server too — the retarget must not
      have made the plugin Leaf-only (compatibility-only scope, §0.4).
- [ ] **Sign probe under Leaf's async chunk sender** — the one behavioural reason the retarget
      exists. Two assertions, both compared against the same runs on Paper:
      - A client that **never replies to signs** (e.g. a headless/bot client, or `timeout-ticks` set
        low) times out **identically to Paper** — same batch advance, same no-detection outcome, no
        desync and no stuck sign left reverted incorrectly.
      - A client that **does reply** yields the **same `UPDATE_SIGN` parse** — same detections, same
        detection contents in `detections.jsonl` — with no packet dropped and no "received unknown
        packet" client-side error.
- [ ] Sign revert (`restoreBlock`) under async chunk sending: the fake sign disappears cleanly on
      both the timed-out and the answered path.
- [ ] `grep -n "SIGN_BLOCK_ENTITY_TYPE_ID" src/main/java/.../SignProbe.java` → the literal `7` is
      named and commented per **R11** (the mitigation must be in the file, not just in this plan).

**A. Known-cheat client (the escalation case)**
- [ ] Client with a `punish: true` mod + 2-3 unticked mods (e.g. Meteor + Sodium + Xaero).
- [ ] Console shows batch 1 covering only ticked ids; **no** alert yet.
- [ ] Console shows the tier-2 pass covering unticked ids.
- [ ] **Exactly one** alert, **after** tier 2, naming the ticked mod **and** the unticked ones.
- [ ] Exactly one kick (or the configured `punishments:`), with `<punishable>`/`%detected%` naming
      what was actually found.
- [ ] `detections.jsonl` has **one** new line, and its `mods` array is the full union.
- [ ] Debug log: no definition appears on a sign twice (pass 1 ∪ pass 2 == the whole list, disjoint).

**B. Vanilla client**
- [ ] Join. No alert, no kick, no log entry.
- [ ] Debug log shows ≤ 2 batches, ticked ids only, and **no** second pass.
- [ ] `/md history <name>` → `clean`.

**C. Fabric + Sodium/OptiFine client**
- [ ] Join. No alert, no kick (both ship `punish: false` and are unticked).
- [ ] Debug log confirms tier 1 ran and returned nothing → **no escalation**. This is the single
      most important assertion in the whole feature: if a Fabric player escalates, the design is
      broken (see §3.1).
- [ ] `/md detect sodium` → rejoin → now alerted (tier-1 hit) and escalated; `/md ignore sodium` →
      rejoin → nothing again.

**D. Bedrock / Floodgate client**
- [ ] Join with `skip-bedrock: true`: no passive alert, no kick, no probe, nothing in the log.
- [ ] Set `skip-bedrock: false` → rejoin → the passive path now applies, so verify separately.
- [ ] Install a ticked mod on the Bedrock client if possible → still skipped.
- [ ] Name-prefix fallback: rename to `.bedrocktest` with no Floodgate installed → skipped.
- [ ] Rename to `bedrocktest` (no dot) → **not** skipped. Confirms the fallback boundary.

**E. Un-ticked mod only**
- [ ] Client with only an unticked mod → nothing. Then `/md detect <it>`, rejoin → tier-1 hit +
      escalation.
- [ ] Verify `/md list <category> unticked` shows it before the tick and hides it after.

**F. Config-reload mid-session**
- [ ] Join with a cheat client, `/md reload` **during** tier 1: current pass completes, one report
      fires, no second pass starts, no exception, no leak in `activeChecks`.
- [ ] `/md reload` again while idle → `passiveResults`/`lastResult` cleared, `/md history` reports
      "no detection record", no exception.
- [ ] `/md detect wurst` while a check is in flight → reload is applied, in-flight session finishes
      on its own definitions, no `ConcurrentModificationException` in the log.
- [ ] Server `/reload` (plugin disable) **during** escalation → no kick, no ban, no
      `BukkitScheduler` "task already cancelled" warning, sign packets stop.

**G. Bypass**
- [ ] `testffa.bypass` on a cheat client → **nothing at all**: no probe in the debug log, no alert,
      no kick, no JSONL line. (This is the F3 case — also test it with
      `passive-delay-ticks: 100` / `on-join.delay-ticks: 5`, the inverted ordering.)
- [ ] `testffa.bypass.meteor` on a Meteor client → no Meteor probe, no Meteor in the report, and
      **no Meteor after escalation** (the critical assertion: pass 2 must not re-add it).
- [ ] `testffa.bypass.<mod>` for an **unticked** mod → nothing changes (it was never in tier 1;
      it *will* appear in tier 2 on some other mod's hit — decide whether to document that as
      expected and assert it).

**H. Kick-message / punishment placeholders**
- [ ] `punishments: []` (no per-mod list) → `hack-checks.default-punishments` runs.
- [ ] Both empty → the generic `kick-message` fires, wording **byte-identical** to 4.2.0.
- [ ] Passive-only detection with `kick: true` → `%hacks%` is empty and `%mods%` is populated
      (this is the F4 assertion).

**I. Text editor robustness**
- [ ] `detect: []` → `/md detect wurst` works (block is created in place).
- [ ] `detect: [wurst, vape]` (flow form) → `UNSUPPORTED_SHAPE`, clear message, **no write**.
- [ ] Two `detect:` keys → one console WARNING naming both line numbers, the **first** is edited.
- [ ] No `detect:` key at all → `NOT_FOUND`, clear message, no write.
- [ ] Tab-indented or comment-interleaved items → those lines are left alone; only `^[ ]+- ` lines
      are consumed.
- [ ] `chmod 444 config.yml` (or a read-only mount) → `IO_ERROR` to chat, exception to console, and
      **no path in the chat message**.
- [ ] After every successful edit: byte-diff the file and confirm the ~180-line commented reference
      block and all other comments survived byte-for-byte.
- [ ] `detect:` left empty by the last `ignore` → load logs the "detect: is empty" WARNING and
      nothing is detected.

**J. Config validation**
- [ ] `detect:` with a typo id → WARNING naming the id and the `detect:` key, plugin still enables,
      that mod simply is not ticked.
- [ ] `detect:` with `Meteor` (wrong case) and `meteor` → one de-duplication WARNING, one tick.
- [ ] `keys-per-line: 500` → clamped to 20 with a WARNING; probe still works, no desync, no
      "Received unknown packet id" in the client log.
- [ ] `escalate: false` → a cheat client gets one report naming only ticked mods, exactly one pass
      in the debug log.

**K. Concurrency / lifecycle**
- [ ] `/md check <player>` while an on-join check is running → "a check is already running".
- [ ] `/md check <cheat client>` manually → identical pipeline: two passes, one report, and the
      report matches an automatic on-join check of the same player.
- [ ] Quit mid-escalation (during pass 2) → no alert, no kick, `activeChecks` empty, no orphaned
      scheduled task warning.
- [ ] 10 players joining simultaneously → no cross-talk, no interleaved sign replies, correct
      per-player results.

**L. UI**
- [ ] `/md help` → TestFFA header, all 11 subcommands, click-to-fill works on every row.
- [ ] `/md status` → `Detect list 72 ticked / 174 known`, escalation row, existing rows intact.
- [ ] `/md list cheat` → all 72 with `✔`/`✖` markers; hover shows id + ticked + punish.
- [ ] `/md list cheat unticked` and `/md list cheat ticked` → correct subsets.
- [ ] Per-category summary lines match the **verified** table in §4.7 exactly — these are counts,
      not illustrations:
      `CHEAT: 72 (69 ticked)`, `SUSPICIOUS: 86 (3 ticked)`, `LAUNCHER: 7 (0 ticked)`,
      `UTILITY: 9 (0 ticked)`. The 4 summary lines sum to `72 ticked / 174 known`.
- [ ] `vanilla-spoof` and `geyser-spoof` both show as **unticked** in the CHEAT list, and
      `no-brand` shows as unticked too — but for the *punish* reason (it ships `punish: false`),
      which the hover makes visible, not because it was excluded. All three are `punish: false`
      in effect from tier 1's point of view, but only the first two were ever in the `punish: true`
      set (see §4.7).
- [ ] Every player-facing error string: no path, no stack trace, no `java.io.` prefix.

---

## 8. Risks and recorded decisions

Both questions that were previously open here are now **closed and user-confirmed**: R3 (hard floor
on an empty `detect:`) and R4 (config migration approach). They are recorded below as decisions, not
recommendations.

**R1 — the config text editor is fragile by construction.** Both `setPunish` and `setTicked` are
line-based text editors over a file whose structure is a hard requirement. Any admin who reformats
`config.yml` (a YAML round-trip through a GUI editor, a `yq`-style reformat, tabs, a flow-style
list) breaks them. *Mitigations:* `NOT_FOUND` / `UNSUPPORTED_SHAPE` results instead of a corrupt
write; the structural requirement documented in both the config comment and `ADDING-MODS.md`; a
clear player-facing message that names the problem and the fix without leaking paths; the shipped
config is *generated*, not hand-freed, so the reference block and the `mods:` catalog cannot drift
apart. *Residual risk accepted* — this is the same trade the existing `allow`/`disallow` already
makes, and the alternative (`config.set` + `saveConfig`) is strictly worse because it destroys the
comments the feature depends on. *Worth doing if it ever bites:* switch to a marker-anchored block
(`detect: # testffa:detect`) and keep a separate machine-owned region.

**R2 — probe duration for detected players.** A detected player is probed over 4 batches instead of
3 (§3.1). Worst case that is one extra `timeout-ticks` window of latency before the report lands.
*Mitigations:* `between-batch-ticks` and `batch-size` are now configurable; `escalate: false` is the
escape hatch; the total definition count is unchanged, so the extra cost is one batch boundary and
one extra open/revert packet sequence, not a second full probe. *Watch for:* a client that never
replies to signs would now hit 4 timeouts instead of 3 — still detected by nothing, and still
correct (a timeout is not a detection), just 4 × `timeout-ticks` of latency. That is an argument for
keeping `timeout-ticks` modest (the shipped 40) rather than raising it.

**R3 — the `detect:` list is an allow-list that can silently disable everything.** This is the most
dangerous property of the design. An empty list, a typo'd key, or a `mode: whitelist` remnant
copied from an old config means **nothing is ever detected**, and the only signal is one console
WARNING that scrolls away. *Mitigations shipped:* a load-time WARNING (not INFO) on an empty list,
naming the exact remedy (`/moddetector detect <mod>`); a WARNING per unknown id; a WARNING per
duplicate; the `Detect list N ticked / 174 known` row in `/md status` so the number is always one
command away; the commented reference block so ticking a mod needs no command at all.

> **Decision (user-confirmed, closed): no hard floor.** There is deliberately **no** refusal to
> enable and **no** forced minimum size for `detect:` — an empty list is legal and produces a
> **WARNING** and nothing else. Rationale: an admin deliberately running alert-only, or
> temporarily ticking everything off while editing, must not be locked out of their own config, and
> a hard floor converts a misconfiguration into an outage, which is strictly worse than the
> misconfiguration. WARNING is the right severity for this condition, and the R3 mitigations above
> exist precisely because the failure mode cannot be prevented outright. This question is **closed**:
> implement §4.2's load-time WARNING exactly as specced, and do not add a hard floor.

**R4 — backward compatibility / migration for a deployed 4.x `config.yml`.** The plugin ships a
config inside the jar, but `saveDefaultConfig()` will **not** overwrite an existing
`plugins/TestFFAModDetector/config.yml`, so an upgraded server keeps its 4.x file — with `mode:`
and `blocked-mods:` and **no `detect:` key**. Consequence: `getStringList("detect")` returns `[]`,
the load WARNING fires, and the plugin detects nothing. Silent-ish failure, exactly R3.

> **Decision (user-confirmed, closed): Option A — a one-time migration inside `loadCatalog()`.**
> Options B and C are rejected, not deferred. This is approved scope; no further sign-off is needed.

**The specced implementation, Option A:**
- **Trigger:** the `detect:` key is **absent** *and* `blocked-mods:` is **present**. Both conditions
  — a missing `detect:` on a genuinely fresh file is a different problem (the default config always
  ships it) and must not trigger migration.
- **Derivation:** for `mode: blacklist`, tick every catalog id that is **not** in `blocked-mods:`;
  for `mode: whitelist`, tick exactly the ids in `blocked-mods:`. "Tick everything except
  blocked-mods" reproduces 4.x behaviour **exactly**, which is the safest possible migration — a
  migrated server detects precisely what it detected on 4.2.0.
- **Do NOT rewrite the file on disk.** The in-memory ticked set is corrected so the server keeps
  working; the admin still moves the ids by hand. This is consistent with the line-based editor
  rule (never `config.set()` / `saveConfig()`) and means the migration cannot destroy a hand-written
  4.x config.
- **Loud warning naming the exact remedy**, e.g.
  `warning("config.yml looks like a pre-5.0 file: mode/blocked-mods found and no detect: list. Migrating to detect: = [...] - review it, then re-run /moddetector reload.")`
  WARNING, not INFO, and it names the command.
- **Release notes must lead with the migration**, since the behaviour is preserved but the config
  needs a manual edit to become durable.

*Why not the alternatives (retained for the record):* Option B (`config-version: 5`, refuse to
enable until migrated) is safer on paper but has the worst first-run experience on an auto-updating
server — the plugin simply will not start. Option C (do nothing) is cheapest and is a real trap,
because it lands the admin in R3.

**R5 — `escalate` semantics could surprise.** With the default `escalate: true` and a broad ticked
list, a single false positive on one mod turns into a full-catalog probe and a much longer report.
That is the intended trade (completeness) but it is a real change from 4.2.0's latency profile.
*Mitigations:* `escalate: false`; `batch-size`/`between-batch-ticks`; the config comment states
plainly that escalation widens both the probe and the report.

**R6 — the `ownsResult` rename is a behaviour change in one configuration.** With
`hack-checks.kick: false` and on-join enabled, a detected player now gets **one** alert (the full
tier-1+2 result) instead of two (a partial tier-1 one, then the full one). This is a fix, not a
regression, but it changes what an admin sees when they flip `kick` off — worth a line in the
release note.

**R7 — F1 leaves inert catalog entries that tick on but never fire.** If F1 is fixed separately
later, the inert entries suddenly become live, and two of them (`vanilla-spoof`, `geyser-spoof`)
are brand-anomaly heuristics with real false-positive potential (locale quirks, offline-mode
clients, odd brand strings — the shipped `config.yml` comment at lines 1610-1614 says so).
*Recommendation:* when F1 is fixed, ship `vanilla-spoof`/`geyser-spoof` with `punish: false`
first, and only promote them after watching alerts on a real player base.

**Why they are excluded from the shipped `detect:` list for v5.0.0 — and exactly which ones.** Only
**two** of the three F1 entries needed excluding, not three, which is why the shipped default is
**74 − 2 = 72** ids rather than 74 or 71:

- `vanilla-spoof` ships `punish: true` → it is inside the 74, so a "ship everything `punish: true`"
  rule would have picked it up. **Explicitly excluded by name.** Both excluded entries are category
  **`CHEAT`**, which is why CHEAT ticks 69 of its 71 `punish: true` entries (§4.7) — stated here so
  this risk, §4.7, and §6 cannot drift.
- `geyser-spoof` ships `punish: true` → same. **Explicitly excluded by name.** (Also category `CHEAT`.)
- `no-brand` ships **`punish: false`** → it was **never in the 74** to begin with, so it required no
  exclusion. The earlier phrasing of this risk ("three `punish: true` entries") was simply wrong;
  `no-brand` is inert *and* already outside the set, which is why it is a double non-event for
  v5.0.0. It still appears in the commented reference block like any other valid id.

The residual risk accepted for v5.0.0 is therefore limited to the two spoof heuristics. An admin
may still tick any of the three manually; doing so is a harmless no-op until F1 is fixed.

**R8 — dirty working tree.** The tree is dirty against `b70b9bc` (8 modified sources; `git status`
confirms). *Action:* before starting, `git add -A && git commit -m "pre-v5.0.0 working tree"` as a
restore point, so Pass 1 can be committed as a clean, self-contained diff. Do not run
`git checkout`/`git restore` against `b70b9bc` — that would discard uncommitted work.

**R9 — the project folder is still named `ErrorSMP-ModDetector`.** Explicitly **out of scope** for
both passes (the user-approved constraint is that it is not renamed). Noted as an optional
follow-up: renaming the directory changes the relative path used by every local dev workflow and
by nothing in the build. Zero code impact; do it as its own commit, never mixed into Pass 1 or 2.

**R10 — PacketEvents 1.21.11 packet-path verification (research WAS needed and WAS performed
manually).** Any earlier claim that no external API research was required, and that
`Redstone-APISearch` was therefore not invoked, is **withdrawn** — it was wrong, and it is replaced by
the verification results below. For accuracy about method: the `Redstone-APISearch` subagent was
**unavailable** for this run (`Model unavailable: opencode/minimax-m2.5-free`), so the verification
was carried out by hand against upstream sources. The rest of the API surface remains low-risk —
every other API this work touches (`Player#getListeningPluginChannels`,
`Player#getClientBrandName`, `Player#isPermissionSet`, `Player#hasPermission`, Adventure
`Component`/`HoverEvent`/`ClickEvent`, Bukkit `ConfigurationSection#getStringList`, PacketEvents
`PacketType.Play.Client.UPDATE_SIGN` and `sendPacketSilently`) is **already in use in these files**,
and the new code adds no new external API surface beyond the packet-path questions settled here.

**Verified, and correct for 1.21.11 with no code change required:**

1. **`HackCheckPacketListener`'s hand-rolled `UPDATE_SIGN` parser is correct for 1.21.11.** It
   matches PacketEvents' own `WrapperPlayClientUpdateSign.read()` exactly: for
   `>= V_1_20 && < V_26_3` that wrapper reads the `isFrontText` boolean **before** the four
   `readString(384)` calls, which is precisely what the plugin's existing
   `if (isNewerThanOrEquals(V_1_20)) readBoolean();` does. The trailing boolean form only appears at
   **26.3+**, so 1.21.11 is well inside the branch the plugin already implements. **No change needed.**
2. **`WrapperPlayServerOpenSignEditor(Vector3i, boolean)` is unchanged** between 2.13.0 and the
   1.21.11 target, and the boolean it carries (`isFrontText`) is written for `>= 1.20`. The plugin's
   current call is **correct as-is.**
3. **`SignTextComponent` is annotated `@versions 26.3+`** — it is a new component type introduced
   after our target. Therefore 1.21.11 still uses the **NBT sign block-entity format**, and the
   plugin's NBT-based sign construction/reading **remains valid.** No migration to a new component
   type is required.
4. **`SignProbe`'s hardcoded block-entity type id `7` is correct for 1.21.11.** Confirmed against
   ViaVersion's registry-order table
   (`protocols/v1_17_1to1_18/data/BlockEntities1_18.java`), which lists `sign` at index 7. The first
   eight entries of that table — furnace, chest, trapped_chest, ender_chest, jukebox, dispenser,
   dropper, sign — are **all pre-1.18 types**; vanilla appends new block-entity types to the **end**
   of the registry and has never reordered these, so index 7 is stable. Two negative findings, both
   recorded so the question is not re-derived later: there is **no public Bukkit/Paper API** to
   resolve a block-entity type id (`org.bukkit.UnsafeValues` in paper-api 1.21.11 exposes no
   block-entity id accessor), and **PacketEvents ships no block-entity type mapping data** (the repo
   has no `mappings/data` tree). The residual risk is tracked as **R11**.
   - *Do not try to derive protocol ids from `mcmeta` registry dumps.* The misode `mcmeta` dumps
     were checked and are **alphabetically sorted**, not registry-ordered, so they cannot be used for
     this purpose. Noted here so nobody re-attempts that derivation and trusts the result.

**Net effect on the plan: no code change in Pass 1 or Pass 2 is required by the platform retarget.**
The retarget is a dependency bump plus the `api-version` change plus the §7 A0 verification items.
The only code-level follow-up is the R11 mitigation (naming the magic number), which is
documentation-grade, not behavioural.

**R11 — `SignProbe`'s bare literal `7` for the sign block-entity type is an unresolvable
protocol-level magic number.** `SignProbe` writes a `7` where a block-entity *type id* is expected.
It is currently **correct for 1.21.11** (R10 finding 4), but there is no API that resolves it —
neither Bukkit/Paper (`UnsafeValues` exposes no block-entity id accessor) nor PacketEvents (no
`mappings/data` tree). The failure mode is nasty: any future Minecraft version that ever *inserts* a
block-entity type before index 7 would silently break the sign probe — the client would simply
ignore the malformed packet and the probe would never fire, degrading to **"nothing is ever
detected."** That is the same class of silent failure as **R3**, and it would be hard to
diagnose from the symptom. *Mitigation:* replace the bare literal with a well-named
`private static final int SIGN_BLOCK_ENTITY_TYPE_ID = 7;` carrying a comment that records the
provenance (ViaVersion's registry-order table, `sign` at index 7) **and** the append-only
assumption it depends on, so the next reader knows what to re-verify on a version bump. *Explicitly
rejected as a mitigation:* resolving the id dynamically by reflecting into NMS / the internals
mapping. It is version-fragile by construction, would break on every Minecraft update, and would
reintroduce exactly the kind of breakage R11 is documenting.

**R12 — `api-version: '1.21.11'` removes the 1.21.8 fallback.** Declaring a three-part
`api-version` is the version-correct choice for a 1.21.11 target (§0.4), but a 1.21.8 server
**rejects** it — `ApiVersion` in Paper 1.21.8 has no knowledge of a `1.21.11` API version, so the
plugin will **fail to load** there rather than degrade. The user has accepted this: 1.21.11 is the
target, and the previous 1.21.8 target is not being preserved. *Consequence to state in the release
note:* this jar is **not** a drop-in replacement for a 1.21.8 server. *If* the old target ever has
to be supported again, it needs its own build or a `paper-plugin.yml`/multi-release approach — out
of scope for v5.0.0. The compensating verification is the portability assertion in §7 A0 (the same
jar must load on Paper 1.21.11 as well as Leaf), which is what keeps the retarget from quietly
becoming Leaf-only.

**R13 — Leaf's async chunk sender is the one untested behavioural difference, and its failure mode
is silent.** The paper-api bump is source-compatible and the packet paths were verified against
upstream sources (R10), but the *runtime timing* of a packet whose payload assumes a chunk is
resident in the client's view is not something source review can settle. If Leaf's async chunk
sending ever causes the fake sign to be placed, read, or reverted outside the client's view, the
probe silently returns no detections and the plugin looks like a clean player — degrading to
"nothing is ever detected", the same failure class as R3 and R11. *Mitigations:* the §7 A0 items
test the answered path and the timed-out path on a real Leaf 1.21.11 server and compare both
against Paper; `sign-probe-debug: true` is the supported diagnostic; the `SIGN_BLOCK_ENTITY_TYPE_ID`
naming in R11 keeps the other silent-failure vector documented in the file itself. *Residual risk
accepted* — Leaf is the only platform in scope with this behaviour, the plugin must stay portable,
and a Paper-only CI server cannot exercise it, so this is verified manually on the target server
rather than in the build.
