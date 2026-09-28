# Upgrading: 4.2.0 → 5.0.0

For operators running **ErrorSMP Mod Detector 4.2.0** who are moving to
**TestFFAModDetector 5.0.0**. This is a checklist, not a manual. It covers only
what changes at the operator boundary. For how the plugin detects things, see
[`ADDING-MODS.md`](ADDING-MODS.md); for provenance, see
[`NOTICE.md`](NOTICE.md).

## Read this first

Five things change. Four of them will break a running server if you skip them.

| # | Change | If you ignore it |
| --- | --- | --- |
| 1 | Permission nodes renamed `moddetector.*` → `testffa.*`, **no aliases** | Every existing permission group silently stops working. Anyone holding only the old nodes loses `/md` entirely. |
| 2 | Server must be **1.21.11** | The plugin does not load on 1.21.8. |
| 3 | **PacketEvents** required, 2.11.0 or newer | The plugin does not load at all — it is a hard load-time dependency. |
| 4 | `mode:` / `blocked-mods:` removed from `config.yml` | Your detect set is re-derived in memory at load and lost on the next restart until you move the ids across. |
| 5 | Tier-2 escalation on any tier-1 hit | Flagged players get a second probe pass. Turn it off with `hack-checks.escalate: false`. |

Only #5 is new behaviour. #1–#4 are consequences of the rename and the 1.21.11
retarget.

## What you are installing

| | 4.2.0 | 5.0.0 |
| --- | --- | --- |
| Jar | `errorsmp-moddetector-4.2.0.jar` | **`testffa-moddetector-5.0.0.jar`** |
| `plugin.yml` `name:` | `ErrorSMPModDetector` | **`TestFFAModDetector`** |
| Data folder | `plugins/ErrorSMPModDetector/` | **`plugins/TestFFAModDetector/`** |
| Command | `/moddetector` (alias `md`) | `/moddetector` (alias `md`) — **unchanged** |

The plugin name inside `plugin.yml` changed, so the data folder moves with it. On
first start 5.0.0 looks in `plugins/TestFFAModDetector/`, does not find a config
there, and writes a fresh default — **your 4.2.0 `config.yml` is left sitting
unread in `plugins/ErrorSMPModDetector/`**. Move it across yourself if you want
your settings; see [Config migration](#config-migration).

Commands are unaffected by the rename. `/moddetector` and `/md` are the same two
names as before, and every pre-4.x subcommand still exists.

Built with **Maven** (`mvn clean package`) on **Java 21**. If you are rebuilding
from source rather than taking a released jar, note that there is no Gradle
build and no `build.sh` / `build.ps1` in this project — `mvn clean package` is
the only build.

## Platform requirement (breaking)

`api-version` is now the three-part **`1.21.11`**. Your server must be
**Leaf 1.21.11**. A three-part `api-version` is rejected by a 1.21.8 server, so
**the old 1.21.8 target is no longer supported** — the plugin will not load
there, and there is no fallback.

The Leaf retarget is **compatibility only**. No Leaf API is used anywhere in the
plugin; it compiles against Paper's API and stays portable to **Paper and Purpur
1.21.11**. There is no runtime platform check and nothing Leaf-specific to
configure.

### PacketEvents

PacketEvents is a **hard dependency** (`depend: [packetevents]`). The server
must have **PacketEvents 2.11.0 or newer** installed — that is the release
1.21.11 support landed in. Without it the plugin does not load.

Two version numbers show up here and they mean different things:

- **2.11.0** — the minimum **your server** needs installed. This is your
  requirement.
- **2.13.0** — the version the plugin **compiles** against. It is a `provided`
  dependency, so it is not bundled; the copy on your server is what actually
  runs. Any version at or above 2.11.0 is fine, and you do not need exactly
  2.13.0.

## Permission nodes renamed (breaking)

All four node families moved from the `moddetector.*` namespace to `testffa.*`:

| 4.2.0 | 5.0.0 |
| --- | --- |
| `moddetector.admin` | `testffa.admin` |
| `moddetector.alerts` | `testffa.alerts` |
| `moddetector.bypass` | `testffa.bypass` |
| `moddetector.bypass.<mod-id>` | `testffa.bypass.<mod-id>` |

**The old nodes are not aliased, and there is no compatibility shim.** This is
deliberate: a shim that accepts either node would double the permission-check
surface and hide the migration behind a silent fallback. The major version bump
*is* the migration signal.

> **`/md` is a `testffa.admin` command. An operator holding only
> `moddetector.admin` loses all access to the command entirely** — not partial
> access, none. This is intended. Fix it before you need the command.

LuckPerms example:

```
# LuckPerms example
/lp group default permission set moddetector.alerts testffa.alerts
/lp group admin   permission set moddetector.admin  testffa.admin
/lp user <name>   permission set moddetector.bypass testffa.bypass
# and for a per-mod exemption:
/lp user <name>   permission set moddetector.bypass.meteor testffa.bypass.meteor
```

Two notes on the nodes themselves:

- `testffa.admin` and `testffa.alerts` default to `op`; `testffa.bypass`
  defaults to `false`. Defaults are unchanged from 4.2.0, so anyone relying on
  op-based access keeps working — but any **explicitly granted** old node is now
  dead and must be re-granted.
- Only the three base nodes are declared in `plugin.yml`. The per-mod
  `testffa.bypass.<mod-id>` nodes are built at runtime from the mod id and are
  **not** declared anywhere, so they will not show up in a permission-manager
  autocomplete until you have granted one.

## Config migration (breaking, but automatic and non-destructive)

`mode:` and `blocked-mods:` are **gone**. They are replaced by a single
top-level `detect:` list of mod ids: an id present in the list is looked for on
join, an id absent is not. The read direction is the opposite of what the old
whitelist/blacklist names suggested.

### What the plugin does with an old config

If your `config.yml` still has `mode:` / `blocked-mods:` and no `detect:` list,
the plugin **derives the ticked set in memory at load** and logs a **loud
WARNING** to the console telling you it did. Both legacy modes translate
exactly, so a migrated server detects what it detected on 4.2.0:

- `mode: whitelist` — ticks exactly the ids that were in `blocked-mods:`.
- `mode: blacklist` — ticks every catalog id **except** those in `blocked-mods:`.

**No file is ever rewritten.** The plugin never round-trips `config.yml` through
`saveConfig()`, so your comments, blank lines and formatting survive every edit —
including the big commented reference block that lists every available mod id.

**The migration is per-session.** It lives in memory only, is not written to
disk, and is gone on the next restart. You must move the ids you want into
`detect:` by hand, or with `/moddetector detect` / `/moddetector ignore`, and
then delete the dead `mode:` and `blocked-mods:` keys. Until you do, every
restart re-derives from the legacy keys and re-logs the warning.

### Watch the cost of a migrated blacklist

This is the one case that surprises people.

4.2.0 shipped `mode: blacklist` with an **empty** `blocked-mods:`. If you never
edited that, the migration ticks **every id in the 174-mod catalog**. Tier 1
will then match any player running a catalogued mod — including the common
launchers and QoL utilities, not only cheat clients — so tier-2 escalation
runs on most *modded* joins. A fully vanilla client still matches nothing: its
brand and channel set appear in no catalogued entry.

That is **not a regression in what is detected**. It is 4.2.0's own detection
set under the new escalation rule; the empty exclusion list was the only throttle
you had, and it is being honoured exactly. The fix is to **trim the `detect:`
list** to the ids you actually want. Do not change anything else to compensate.

## Tier-2 escalation (the one genuinely new behaviour)

On a normal join, tier 1 probes **only** the ticked mods. The moment tier 1
produces any hit — a sign-probe key that resolved, *or* a passive brand/channel
match — the plugin automatically runs a **second pass over the unticked
remainder** and merges the results. One report then names everything the client
is actually running, instead of just the first thing that tripped.

The two passes are disjoint, so no definition is ever probed twice. One
config switch controls it:

```yaml
hack-checks:
  escalate: true   # default. false = tier 1 only.
```

Set `escalate: false` to get the old single-pass cost profile back. The trade is
completeness: with it off, a detection report names only ticked mods, so an
unticked mod on a cheating client is never surfaced.

Two operational notes:

- Escalation is triggered by a real hit, never by silence. A sign batch that
  times out contributes no detections and does not trigger the second pass — an
  unresponsive client is not evidence of anything.
- `/moddetector check <player>` runs the identical pipeline, so a manual check
  and an automatic on-join check of the same player report the same thing.

## New commands

Two subcommands were added, on the detect axis:

- `/moddetector detect <mod>` — tick a mod on, adding it to `detect:`.
- `/moddetector ignore <mod>` — untick a mod. Only tier-2 escalation covers it
  afterwards.

Both require `testffa.admin`. Both edit the `detect:` block **in place, line by
line**, and reload the catalog themselves, so **no `/moddetector reload` is
needed** after them. Because the edit is structural rather than a re-serialise,
the `detect:` key must stay at column 0 with `  - <mod-id>` lines directly
beneath it; if that shape is broken, the commands report that they could not find
the list. Details in [`ADDING-MODS.md`](ADDING-MODS.md).

To see what is currently ticked without opening the file, use
`/moddetector list <category> ticked` (or `unticked`), or `/moddetector status`
for the counts and the escalation state.

## `detect:` and `punish:` are separate axes

`detect:` answers *is this mod looked for at all?* `punish:` — or
`/moddetector allow` and `/moddetector disallow` — answers *does finding it
kick?* They are **independent**, and conflating them is the most common way to
end up with a config that does not behave as expected. A ticked mod with
`punish: false` is a normal combination: still found, still alerted, still
named in `%detected%`, simply never able to cost anyone a kick. And unticking a
mod does not un-punish it. See the *Detect axis vs punish axis* section of
[`ADDING-MODS.md`](ADDING-MODS.md).

## Order of operations

For an existing server, in this order:

1. **Back up** the whole data folder: `plugins/ErrorSMPModDetector/`.
2. **Stop** the server.
3. **Install** `testffa-moddetector-5.0.0.jar`. Remove the 4.2.0 jar — the
   plugin name changed, so the old jar loads as a second, separate plugin
   against the same players.
4. **Confirm** PacketEvents 2.11.0+ is installed and the server is 1.21.11.
5. **Move** `plugins/ErrorSMPModDetector/config.yml` into the new
   `plugins/TestFFAModDetector/` folder, or start without it and accept the
   shipped defaults.
6. **Start** the server and **read the console WARNING** if it appears. It tells
   you how many ids were derived and how many were left unticked.
7. **Move the ids you want into `detect:`** — by hand, or with
   `/moddetector detect <mod>`. This is the step that makes the migration
   permanent.
8. **Delete** the dead `mode:` and `blocked-mods:` keys.
9. **Re-grant permissions** under the `testffa.*` names, and verify you can
   still run `/md`.

For a **fresh install** there is nothing to migrate: delete the old data folder
and let 5.0.0 write its own. The shipped `detect:` list has **72 ticked ids**,
chosen as the mods the plugin can actually act on.

`hack-checks.escalate: true` is the shipped default. If the derived set turns
out to be too large on your player base, trimming `detect:` is the right fix;
reaching for `escalate: false` throws away detection coverage on every flagged
player.
