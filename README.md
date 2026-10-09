# TestFFAModDetector

Detects Minecraft client modifications on a Paper-family server, using two independent
signals:

- **Passive** — the plugin channels and client brand the server already sees on join.
- **Sign-probe** — the plugin briefly sends the client a fake sign containing translation
  and keybind keys, and reads back what the client resolved them to. This is the only method
  that catches a mod which deliberately hides itself, which real cheat clients do on purpose.

Detection scope is a tick-off `detect:` list in `config.yml` (tier 1) plus automatic
escalation over the unticked remainder whenever tier 1 hits anything (tier 2). Untick by
deleting a line, tick by adding one — no rebuild needed.

## Adding your own mods

Everything lives in `config.yml`, so you need no rebuild — just `/md reload`.

Each mod is one entry under `mods:` (or `custom-mods:`), declaring signals:

```yaml
mods:
  my-hack-client:
    display-name: "My Hack Client"
    category: CHEAT   # CHEAT | SUSPICIOUS | LAUNCHER | UTILITY
    signals:
      - source: TRANSLATE      # probe a translation key the client may resolve
        keys: ["key.myhackclient.title"]
      - source: KEYBIND        # probe a keybind id the client may have bound
        keys: ["key.myhackclient.toggle"]
      - source: CHANNEL        # match a plugin-messaging channel
        matches: ["myhackclient:*"]
      - source: BRAND          # match the self-reported client brand (regex)
        matches: ["(?i)^myhackclient"]
    punish: true
    punishments:
      - "kick %player% &cUse of a disallowed client is forbidden. Detected: %punishable%"
```

- `TRANSLATE`, `KEYBIND` and `METEOR_VARIANT` are **active** — the sign-probe. This is
  the only method that catches a mod which deliberately hides itself.
- `CHANNEL` and `BRAND` are **passive** — they only catch a mod that doesn't hide.
- `punish:` decides whether a detection on this mod can kick.

Declaring an entry does not probe it. Add its id to the top-level `detect:` list to have
it probed on every join; leave it out and only tier-2 escalation covers it.
`/md detect <mod>` and `/md ignore <mod>` edit that list for you.

To find the values, most mods worth tracking are open source: translation keys are in
`assets/<modid>/lang/en_us.json`, keybind ids come from `KeyMapping` construction in
the mod's main class, and channel namespaces come from `ClientPlayNetworking`
registrations. Write your own entries from those — don't bulk-import another
detector's compiled catalog.

## Requirements

- **Minecraft 1.21.11** on Paper, Purpur, Leaf or Folia
- **Java 21**
- **PacketEvents 2.11.0+** — a hard dependency, loaded before this plugin

## Install

1. Drop `testffa-moddetector-5.0.0.jar` in `plugins/`.
2. Start the server once to write `plugins/TestFFAModDetector/config.yml`.
3. Grant the permission nodes you want (below). The plugin will not work without `testffa.admin`.
4. Tune the `detect:` list, then `/md reload`.

## Commands

`/moddetector`, alias `/md`. All require `testffa.admin`.

| Command | Does |
| --- | --- |
| `/md check [player]` | Runs a full check now. No name checks you. |
| `/md history <player>` | Last recorded result for a player. |
| `/md list [category] [all\|ticked\|unticked]` | Browse the mod database. |
| `/md detect <mod>` | Tick a mod on — probed in tier 1. |
| `/md ignore <mod>` | Tick a mod off — only tier 2 escalation covers it. |
| `/md allow <mod>` | Stop kicking for a mod; still detected and alerted. |
| `/md disallow <mod>` | Make a mod grounds for a kick again. |
| `/md status` | Counts, tier split, escalation state, kick enforcement. |
| `/md hacks` | Sign-probe definition counts. |
| `/md reload` | Re-read `config.yml`. |

`detect` and `ignore` edit the file in place and reload the catalog themselves, so no
`/md reload` is needed after them.

## Permissions

| Node | Default | Grants |
| --- | --- | --- |
| `testffa.admin` | op | Everything above. |
| `testffa.alerts` | op | Receives chat alerts on detection. |
| `testffa.bypass` | false | Exempts a player from all detection. |
| `testffa.bypass.<mod-id>` | — | Exempts one mod. Built at runtime, so it will not autocomplete until granted. |

## Detection and punish are separate axes

`detect:` answers *is this mod looked for at all?* `punish:` (or `/md allow` /
`/md disallow`) answers *does finding it kick?* They are independent. A ticked mod with
`punish: false` is normal: found, alerted, named in reports, but it can never cost a kick.

## Documentation

- [`NOTICE.md`](NOTICE.md) — licence, attribution, and change history

## Building

```
mvn clean package
```

Produces `target/testffa-moddetector-5.0.0.jar`. Java 21, no wrapper, no Gradle.

## Licence

GPLv3. Forked from [0xnim/mod-detection-plugin](https://github.com/0xnim/mod-detection-plugin).
See [`NOTICE.md`](NOTICE.md) for attribution and the change log.