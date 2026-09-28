# Adding mods to TestFFAModDetector

Everything lives in one file now: `config.yml` (bundled at
`src/main/resources/config.yml`, or the deployed copy in the plugin's data folder -
editing that one doesn't need a rebuild, just `/moddetector reload`).

Signals are declared in two independent places, because there are two independent
detection methods. A mod can be listed under either, or both, if you have signals
for each. Those two places are only the signal *database* - the `detect:` list
further down is what decides which of the declared signals are actually probed.

## 1. `mods:` - the main catalog (passive + active signals together)

This is where almost everything lives - both the passive brand/channel signals and
the active sign-probe signals for a mod are declared together, per mod id:

```yaml
mods:
  my-hack-client:
    display-name: "My Hack Client"
    category: CHEAT   # CHEAT | SUSPICIOUS | LAUNCHER | UTILITY
    signals:
      - source: TRANSLATE
        keys: ["key.myhackclient.title"]
      - source: KEYBIND
        keys: ["key.myhackclient.toggle"]
      - source: CHANNEL
        matches: ["myhackclient:*"]
      - source: BRAND
        matches: ["(?i)^myhackclient"]
    punish: true
    punishments:
      - "kick %player% &cUse of a disallowed client is forbidden. Detected: %punishable%"
```

- `source: TRANSLATE` / `source: KEYBIND` / `source: METEOR_VARIANT` are **active**
  signals (the sign-probe): the plugin briefly probes the client with a
  translation/keybind key and reads back what it resolves to. This is the only
  method that can catch a mod that deliberately hides itself (real cheat clients do
  this on purpose). `METEOR_VARIANT` is mechanically identical to `TRANSLATE`,
  reserved specifically for Meteor Client's own keys because Meteor's
  anti-detection produces a raw-key-echo signature that would otherwise be
  misread as an unrelated exploit-preventer failure.
- `source: CHANNEL` / `source: BRAND` are **passive** signals: they only catch a
  mod that doesn't hide - either because it registers a plugin channel (a side
  effect of most modloaders) or because the client reports an identifiable brand
  string. `channels` matches are either an exact channel name or a `prefix:*`
  wildcard, not full regex; `brand` matches are full regex.
- `source: NO_BRAND` / `VANILLA_SPOOF` / `GEYSER_SPOOF` are also **passive**, but
  unlike every other source they take no `matches`/`keys` - they catch a
  contradiction in the client's own self-reported state instead of matching a
  specific mod's signature, so they can flag mods this catalog has never heard of.
  `NO_BRAND` fires when the client brand is empty or missing (a real client always
  reports something). `VANILLA_SPOOF` fires when the brand is exactly "vanilla" but
  the client has registered plugin-messaging channels anyway (no genuine vanilla
  client ever does that - the brand was spoofed to hide whatever registered them).
  `GEYSER_SPOOF` fires when the brand claims to be Geyser but Floodgate (if
  installed) doesn't recognize the connection as a real Bedrock one. These three
  ship as their own entries (`no-brand`, `vanilla-spoof`, `geyser-spoof`) in
  `mods:` - edit or remove them like any other entry, don't add more of these
  sources elsewhere. Note that all three are currently inert and ship un-ticked;
  "Three mods that ship un-ticked" under `detect:` below explains why.
- `punish` - whether a detection on this mod counts toward the kick decision
  (`hack-checks.kick` in the same file). Leave it `false` (or omit `punishments`)
  for anything you just want logged/alerted, not enforced. The QoL/launcher
  entries that used to live in a separate `utilities.yml` before everything was
  merged into one file now sit in this same catalog, and they all still ship
  `punish: false` by default - so the shipped `mods:` block is itself the
  reference for which mods an admin is expected to treat as informational. This
  is the punish axis only; whether a mod is probed at all is the separate
  `detect:` decision described below.
- `punishments` (optional) - custom console commands run on detection instead of
  the generic `hack-checks.kick-message` kick. Supports `%player%`, `%punishable%`
  (only the mods that actually justify punishment - everything detected minus any
  `punish: false` entries riding along, like a minimap also seen on the same
  player), `%detected%` (everything, punishable or not), `%mods%` (channel/brand
  hits only), `%hacks%` (sign-probe hits only), and `&` legacy color codes. If a
  mod has `punish: true` but no `punishments:` of its own, `hack-checks.default-
  punishments` (top-level in `config.yml`) runs instead, before the plugin ever
  falls back to the single generic `kick-message`. Only fires if `punish: true`
  **and** `hack-checks.kick: true`.

## 2. `hacks:` - extra sign-probe entries without touching a mod's own listing

```yaml
hacks:
  my-hack-client-alt-key:
    display-name: "My Hack Client"
    key: "key.myhackclient.altkey"
    mode: TRANSLATE
    punish: true
```

Same TRANSLATE/KEYBIND/METEOR modes as above. Use this only when you want to add a
probe key without editing the mod's own entry in `mods:` - anything you can express
as a `signals:` entry there is equivalent and preferred, since it keeps everything
about one mod in one place. An id that exists only here is always tier 1, ticked or
not; see the `detect:` section below.

## `detect:` - the tick-off list, and `custom-mods:`

`detect:` is a top-level list of mod ids, and it answers exactly one question:
which of the mods declared in `mods:`/`custom-mods:` this plugin is actually
looking for. Read it as a tick-off list, not an allowlist, so the direction of
travel is the opposite of what the name suggests - an id **present** in the list
is ticked, an id **absent** is unticked. To untick a mod you delete its line; to
tick a mod you add it. Every id in the catalog is either ticked or unticked, and
there is no third state and no include/exclude mode to flip.

`custom-mods:` is still a top-level key, now sitting alongside `detect:`. It uses
the same schema as a passive-only `mods:` entry - it's just a separate place to
add your own without editing the bundled catalog directly. Ids you declare there
are valid `detect:` entries like any other, and they join tier 1 the moment you
tick them.

### Tier 1 and tier 2

`mods:` is the signal *database*; `detect:` indexes into it. Being listed in
`mods:` does **not** mean a mod is probed on join, only that it *can* be.

Tier 1 is the ticked set, and it is the only thing that runs on a normal join.
Tier 2 is everything left unticked, and it runs **only** after tier 1 has already
hit something - a sign-probe key that resolved, or a passive brand/channel match.
That ordering is the whole reason it's split that way: rather than stopping at
the first thing that tripped, the report names everything the client is actually
running. The escalated pass re-reads server-side brand and channel state and
sends the sign-probe batch for the unticked definitions, carrying tier 1's
findings forward, so tier 1 plus tier 2 comes to the same answer a single
full-catalog scan would have given. The two sets are disjoint, so nothing is
probed twice.

`hack-checks.escalate` (inside `hack-checks:`, `true` by default) is the one
switch for that second pass. Set it `false` and only tier 1 runs - faster for an
already-flagged player, but the report then names just the ticked mods. One flag
is both the escalation switch and the speed/completeness trade-off.

There is no hard floor on the list. An empty `detect:` logs a warning at startup
and the plugin still starts and runs normally; it just has nothing to probe in
tier 1, which also means escalation can never fire. That is only a sensible thing
to want on a server that genuinely doesn't want auto-detection.

### Detect axis vs punish axis

These are two independent axes, and conflating them is the most common way to end
up with a config that doesn't behave the way you expected.

- The **detect axis** - `detect:` in the file, `/moddetector detect` and
  `/moddetector ignore` in game - answers *is this mod looked for at all?*
- The **punish axis** - `punish:` in the mod's entry, `/moddetector allow` and
  `/moddetector disallow` in game - answers *does finding it kick?*

**They are independent.** Ticking says the plugin looks for the mod; `punish:`
says a detection on it kicks. So a ticked mod with `punish: false` is a
perfectly normal combination: it is still found, still alerted, still named in
`%detected%`, and simply kept out of `%punishable%` so it can never cost anyone a
kick. And unticking a mod does not un-punish it - it only drops the mod out of
tier 1, where it is still covered by tier 2 on any detection. If you want a mod
gone from reports entirely you untick it *and* allow it; if you only want it to
stop kicking, allow it and leave it ticked.

`/moddetector status` shows both sides of the ledger: the tick counts for the
`detect:` list, the tier split, the escalation switch, and whether kick
enforcement is on.

### Editing the list from in game

`/moddetector detect <mod>` ticks a mod; `/moddetector ignore <mod>` unticks it.
Both live under `/moddetector` (alias `md`) and need `testffa.admin`, and both
apply immediately - no `/moddetector reload` afterwards. `/moddetector list`
walks the catalog by category and marks which entries are ticked; it takes a
third argument to filter on that axis (`/moddetector list utility ticked`, or
`unticked`), which is usually faster than opening the file to work out what's
already on.

Unlike `allow`/`disallow`, which rewrites a single `punish:` value, `detect` and
`ignore` edit the `detect:` block **line by line, in place**. The plugin never
re-serializes `config.yml` (it is not round-tripped through `saveConfig()`), so
your comments, blank lines and formatting all survive an edit - including the
big commented reference block in the shipped file that lists every known id,
which is the whole reason the tick-off list is usable without running a command.
The cost of that is that the edit is structural, so the block has a required
shape:

```yaml
detect:
  - meteor
  - wurst
  - impact
```

The `detect:` key has to sit at **column 0**, with no leading whitespace, and the
ids have to be `"  - <id>"` lines **directly beneath it**. If any other line gets
wedged in between - something that is not blank, not a comment, and not a `- item`
entry - the editor stops there, decides there is no list to edit, and
`detect`/`ignore` report that they could not find it. The same thing happens if
you inline the list (`detect: [meteor, wurst]`): valid YAML, but not the block
form these commands edit. The header comment above the shipped `detect:` key
spells the same rule out, and points back here.

An id that only exists under `hacks:` - one the `mods:` catalog has never heard
of - is **always** tier 1, and is a valid argument to `detect` and `ignore` like
any other id. That is deliberate: a hand-written `hacks:` entry was added on
purpose, and if it were escalation-only there would be nothing to un-tick it
with. Note that ticking it changes nothing about its tier, so a load will warn
that the id isn't in the `mods:`/`custom-mods:` catalog - harmless, and the entry
is still probed.

### Pre-5.0.0 configs

`mode:` and `blocked-mods:` are gone. If you're upgrading an install whose
`config.yml` still carries them, the ticked set is derived from those legacy keys
**in memory** at load time, and the plugin logs a loud warning telling you so.
**No file is rewritten** - nothing is migrated on disk, and the legacy keys are
ignored from then on. Both modes translate exactly, so a migrated server detects
what it detected on 4.x: a legacy whitelist ticks exactly the ids it listed, and a
legacy blacklist ticks every catalog id *except* those it listed. Either way, add
the ids you want to the `detect:` list yourself to make the choice permanent, and
delete the dead keys whenever it suits you.

One consequence is worth planning for. A legacy blacklist with a short or empty
`blocked-mods:` ticks almost the whole catalog, and tier 1 will then match on
ordinary players - so tier 2 escalation runs on most joins, which is the cost of
having escalated at all rather than something v5 invented. If that is too much on
your server, the fix is to tick only the ids you actually want rather than to
change the derivation.

### Three mods that ship un-ticked

The three brand-anomaly entries - `no-brand`, `vanilla-spoof` and `geyser-spoof` -
all ship **un-ticked** in the bundled `detect:` list, and all three are currently
**inert**. That is a known, deliberately unfixed gap in the plugin, not a config
error and not something you can fix from the config: they signal through
`NO_BRAND`, `VANILLA_SPOOF` and `GEYSER_SPOOF` respectively, and
`ModCatalog.SignalSource` only knows `BRAND`, `CHANNEL`, `TRANSLATE`, `KEYBIND`,
`METEOR_VARIANT` and `UNKNOWN`. All three therefore parse to `UNKNOWN`, which is
neither a passive nor an active signal, so they can never match anything.

Only **two** of the three needed excluding. The shipped rule is "every `mods:` id
that ships `punish: true`", which gives `74 - 2 = 72` ticked ids. `vanilla-spoof`
and `geyser-spoof` both ship `punish: true`, so they qualified for the tick and
were deliberately left out because they are inert. `no-brand` already ships
`punish: false`, so it never qualified for the tick in the first place - it is
un-ticked for a different reason than the other two, even though the effect is
identical.

Ticking any of them today costs a probe and reports nothing. They are expected to
start working if the matching `SignalSource` entries are ever implemented, so on a
build that has them, ticking them is exactly the right thing to do.

## Where to find the real values

Most mods worth tracking - cheat clients included - are open source. From the mod's
own public GitHub repo:

- **Translation keys**: `src/main/resources/assets/<modid>/lang/en_us.json`. Every
  key it registers is listed there directly.
- **Keybind ids**: search the main mod class for `KeyMapping`/`KeyBinding`
  construction - the string passed in is the id.
- **Channel ids**: search for `ClientPlayNetworking.registerGlobalReceiver` or
  `PayloadType` registration - the namespace there is the channel prefix.
- **Brand string**: some clients self-report a custom brand (visible via
  `Player#getClientBrandName()` server-side); check the mod's `ClientBrandRetriever`
  or equivalent override if it has one.

Write your own `display-name`, `punish` flag, and keys as you find them - don't
bulk-import another detector's compiled catalog, even reformatted; see `NOTICE.md`
for why that matters here specifically.

## After editing

Run `/moddetector reload` (or restart) to pick up changes without a rebuild -
`config.yml` is read from the plugin's data folder at runtime, not compiled in.
`/moddetector detect`, `ignore`, `allow` and `disallow` skip that step: they edit
the file in place and reload the catalog themselves.
