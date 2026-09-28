# Adding mods to TestFFAModDetector

Everything lives in one file now: `config.yml` (bundled at
`src/main/resources/config.yml`, or the deployed copy in the plugin's data folder -
editing that one doesn't need a rebuild, just `/moddetector reload`).

There are two independent sections in it, because there are two independent
detection methods. A mod can be listed under either, or both, if you have signals
for each.

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
  sources elsewhere.
- `punish` - whether a detection on this mod counts toward the kick decision
  (`hack-checks.kick` in the same file). Leave it `false` (or omit `punishments`)
  for anything you just want logged/alerted, not enforced - see how `utilities.yml`
  content used to be structured before the merge: every QoL/launcher entry ships
  `punish: false` by default.
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
about one mod in one place.

## `custom-mods:` and `blocked-mods:`

`custom-mods:` (top-level, alongside `mode:`/`blocked-mods:`) uses the same schema
as a passive-only `mods:` entry - it's just a separate place to add your own
without editing the bundled catalog directly.

`mode: blacklist` (the default) tracks **everything** in `mods:` + `custom-mods:`
except ids listed in `blocked-mods:` - so `blocked-mods:` is an *exclude* list in
this mode, not an include list. Switch to `mode: whitelist` if you want the
opposite: nothing tracked except ids explicitly listed in `blocked-mods:`.

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
