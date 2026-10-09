# NOTICE

TestFFAModDetector's channel-based detection lineage is a modified fork of
[0xnim/mod-detection-plugin](https://github.com/0xnim/mod-detection-plugin), licensed
under the GNU General Public License v3.0. As required by the GPLv3, this fork remains
licensed under GPLv3 and this notice documents the substantive changes made:

- Rebranded as ErrorSMPModDetector for the ErrorSMP server.
- Package/class layout reorganized under `xyz.nim.modDetectorPlugin`.
- Added the `hackcheck` subsystem: sign-probe translation/keybind fingerprinting
  (ported from the technique used by [CheckHacks](https://github.com/branduzzo/CheckHacks)),
  including same-tick open/revert, pure translation-probe lines (no marker/canary),
  masked off-to-the-side sign placement, on-join auto-checking, and
  deferred/combined kick messaging merging sign-probe and channel-based results.
- Replaced the bundled mod/hack catalogs with an independently maintained set,
  held in a single `config.yml` under its `mods:`, `hacks:` and `custom-mods:`
  sections - entries are compiled from mods' own public source
  repositories or from open detection tools, not extracted from any closed-source
  commercial product.
- Added three brand-string anomaly heuristics (blank/missing brand, a "vanilla"
  brand with plugin channels registered, and a "Geyser" brand Floodgate doesn't
  recognize as a real Bedrock connection), ported from the same upstream fork's
  built-in checks and re-expressed as ordinary catalog entries
  (`no-brand`/`vanilla-spoof`/`geyser-spoof`) instead of hardcoded logic - a
  deliberate divergence, not a gap, kept consistent with this fork's single-catalog
  design.
- v5.0.0 - Rebranded as TestFFAModDetector for the TestFFA server. Permission nodes
  moved from `moddetector.*` to `testffa.*` (`moddetector.admin` / `.alerts` /
  `.bypass` and the per-mod `moddetector.bypass.<id>`); this is a breaking change
  for existing permission-group setups. Retargeted to Leaf 1.21.11
  (`paper-api` 1.21.11); the previous 1.21.8 target is no longer supported, because
  the plugin now declares a three-part `api-version`.
- v5.0.0 - Detection scope reworked: the `mode:`/`blocked-mods:` blacklist/whitelist
  include-exclude system was replaced by a single tick-off `detect:` list with
  automatic tier-2 escalation on any tier-1 hit. The two-tier scope is: tier 1
  probes only ticked mods; tier 2 re-probes the unticked remainder once tier 1 hits,
  so a detection report names everything the client is actually running rather than
  only the first thing that tripped.
- v5.0.0 - Folia support added: `plugin.yml` declares `folia-supported: true`, every
  scheduled task goes through the Paper API's entity/global region schedulers (which
  also run on plain Paper and Leaf, so there is no platform check), console punishment
  commands are dispatched on the global region, and per-player state is held in
  concurrent collections.

Source for this fork is available alongside its distributed binary, per GPLv3 §5/§6.
