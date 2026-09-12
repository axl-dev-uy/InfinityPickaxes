# InfinityGear

InfinityGear is a Paper 26.2 / Java 25 plugin for persistent, uniquely identified, levelable equipment. It is the staged successor to InfinityPickaxes: the migrated `infinitygear:pickaxe` profile preserves block-breaking progression, managed enchantments, deployed UUIDs, duplicate restrictions, commands, permissions, and legacy API adapters while profile-driven foundations support other tools, weapons, and armor.

## Dependencies

- Required: Paper 26.2, EcoEnchants 2026.33, eco 2026.33.
- Optional: Vault plus an economy provider, Nexo 1.25+ (the build targets its typed 1.27 API), PlaceholderAPI, and WorldGuard.
- SQLite is shaded into the production jar. Nexo and Vault are compile-only server dependencies.

EcoEnchants/Bukkit remain authoritative for canonical enchantment identity, native targets, conflicts, compatibility, metadata, and native maximums. InfinityGear adds administrator caps, disabled policies, symmetric additional conflicts, profile compatibility, sockets, and costs. `enchants.yml` synchronization is additive: missing live entries are appended; administrator edits and temporarily unavailable/orphaned entries are preserved.

## Legacy plugin-folder migration

Remove the old jar before enabling InfinityGear. InfinityGear refuses to run if the old plugin is active and declares `InfinityPickaxes` as a provided legacy identity.

On first startup, the legacy `plugins/InfinityPickaxes` folder is copied to a dated sibling backup and missing files are copied into `plugins/InfinityGear`. Existing InfinityGear files are never overwritten, the old folder is never deleted, and `.infinitypickaxes-migration-v1` makes the process idempotent. A partial or ambiguous migration fails closed. This automatic compatibility step only migrates the legacy plugin folder and its local SQLite-era layout. It does not import quarantine records into MariaDB, approve a Task #7 authority cutover, freeze SQLite or enable `book-application`.

Legacy item PDC is read indefinitely and migrated lazily when an item is inspected, scanned, held, or used. Valid migration preserves UUID, level, XP, blocks mined, quarantine state, material, name, lore, and enchantments, writes schema version 1, and mirrors legacy keys during the compatibility window. New data is preferred. A malformed/missing legacy UUID is preserved and reported; InfinityGear never silently creates a replacement UUID.

While `quarantine-authority: sqlite`, `duplicates.db` is the single active
quarantine authority copied into the new folder. Its existing tables and records
remain in place; a transactional local SQLite schema migration adds
`schema_migrations`, tracked kind and tracked type without losing status,
timestamps, reason, resolver, replacement UUID, sightings, actor or location.
That local schema migration is distinct from the operator-invoked Task #7 import
into MariaDB and never performs that import silently.

Legacy plugin rollback, before any MariaDB-authority runtime write: stop the
server, save `plugins/InfinityGear` and its database/WAL files, remove the
InfinityGear jar, restore the dated legacy backup as `plugins/InfinityPickaxes`,
and restore the old jar. Never run both jars. After any MariaDB-authority runtime
write, do not revive the older SQLite writer; keep the server offline and
reconcile or roll forward.

## Gear profiles

`profiles.yml` uses stable namespaced IDs and supports accepted vanilla materials, external IDs, default creation item, enabled/auto-convert policy, display/lore, unbreakable behavior, compatible targets, enchantment overrides, base/expanded sockets, socket milestones, XP sources, and a cost multiplier.

`enchantment-overrides` is sparse: omitted fields inherit `enchants.yml`. It supports `enabled`, `unlock-level`, `standard-maximum`, `absolute-maximum`, `socket-cost`, additive `additional-conflicts`, `removable`, and removal `cost-weight`. Explicit `enabled` and `removable` values replace their global defaults for that profile. A profile cannot loosen the global/native standard maximum, and its absolute maximum remains bounded by the LimitBreak allowance available at the gear's current progression level. Installed disabled, overcap, or oversocketed enchantments are retained and continue contributing their resolved socket cost.

Book fusion deliberately remains profile-neutral because no destination gear/profile is selected at the Fusion Altar; it therefore uses the global standard book maximum. The selected destination profile is enforced when the fused book is later applied.

- `EXPERIENCE`: intentional configured XP sources. The pickaxe profile keeps block-break progression.
- `ITEM_UPGRADES`: levels only through explicit upgrade operations; the armor starter uses this.
- `STATIC`: no passive progression; starter axe/sword profiles use this.

Every profile may opt into automatic vanilla conversion with `auto-convert: true`; conversion remains disabled in the shipped defaults. Eligible single items are converted when acquired, held, used, or moved in a player inventory, including armor slots. Profile lore is rebuilt while existing item names and enchantments are preserved. Administrators may still opt into a profile-wide `display-name`, but the shipped generic profiles leave names untouched. No passive damage-taking armor XP is implemented.

## Stations and enchantments

`stations.yml` configures a Runic Table, Fusion Altar, and Gear Forge using `VANILLA` material or `NEXO` IDs, interaction range, and an administrator/testing bypass. With the secure default `require-registered-instance: true`, an administrator must target the exact placed block or Nexo furniture and run `/igear station bind <type>`; bindings persist in `station-instances.yml`, and ordinary blocks with the same material do nothing. If Nexo's ray-target lookup cannot resolve furniture immediately, the command arms a 30-second binding window and the administrator completes it by right-clicking that furniture. Nexo furniture ownership is keyed to the stable base-entity location rather than its clicked hitbox position. Use `/igear station status` or `/igear station unbind` while targeting an instance to inspect or remove its ownership record. Breaking, exploding, or piston-moving a registered block invalidates its binding. The Nexo adapter uses the documented typed `NexoItems`, `NexoBlocks`, `NexoFurniture`, and interaction-event APIs—no reflection or console commands.

Station menus select live player inventory slots and render clones. Inputs never move into decorative slots. Confirmation re-reads the station, range, player, slots, item identity/UUID, duplicate state, enchantments, policy, output capacity, and payment. Shift-click, drag, hotbar, collect, double-click, repeated confirmation, close, and disconnect cannot leave deposited GUI items because no real inputs are deposited.

All player-visible station inventory titles, button names/lore, payment formatting, preview labels, policy-state labels, and dynamic preview lines are configured in `menus/stations_menu.yml`. MiniMessage and the documented `%placeholder%` tokens are supported. Synchronization is additive: reloads append missing defaults without replacing administrator-edited text.

The legacy pickaxe overview and socket browser remain configurable through `menus/main_menu.yml` and `menus/enchants_menu.yml`. The socket file includes separate no-enchantments fallback states, page-button materials/names/lore/slots, and active/maximum LimitBreak badges. The duplicate warning prepended to migrated pickaxe lore is configured at `pickaxe-lore.quarantine-lore` in `config.yml`; hiding that visual warning does not weaken quarantine enforcement.

### Runic Table

- Apply: a normal book must contain exactly one managed enchantment. A higher book installs its target level directly; equal/lower is rejected. A new enchantment consumes one socket, while raising an existing one does not. Normal books cannot pass the standard maximum. Existing oversocketed/overcap/disabled equipment is not stripped or deactivated.
- Remove: destroys the selected enchantment and preserves all others. Removing the final enchantment from a book yields a blank ordinary book. `non-removable` can be set per enchantment; overcap removal is allowed with configurable surcharge.
- Transfer: moves one selected enchantment at its exact level to a fresh canonical book, consumes one blank ordinary book and the configured Runic Conduit, and preserves other source enchantments. Over-standard/LimitBroken transfer is rejected.
- View: displays installed managed enchantments and policy information without mutation.

### Fusion Altar

Two books must each contain one identical canonical managed enchantment at the same level. They produce one fresh level+1 book through Bukkit’s enchantment API; arbitrary PDC, unrelated enchantments, and malicious metadata are not copied. Fusion cannot pass the standard maximum and has no chance of failure.

`Fuse All Matching` performs binary compression, preserves unmatched original books, recomputes its complete plan on confirmation, refuses insufficient output capacity, and charges the sum of every pairwise fusion using result-level weights. Five level-III books therefore produce a level-V book and retain one original level-III book.

### Gear Forge

Socket expansion consumes the configured Runic Rivet/payment, permanently adds one socket to that item, and stops at the profile’s expanded maximum. Installed enchantments are untouched; already-oversocketed items remain grandfathered.

## Artifacts and LimitBreak

`items.yml` defines provider, vanilla material or Nexo item ID, name, lore, model data, enabled/required/consumed policy, and unique tracking for Runic Eraser, Runic Conduit, and Runic Rivet. Nexo supplies the visual template; InfinityGear stamps the UUID/kind/type/schema/quarantine identity. A raw Nexo item or stacked tracked singleton is invalid.

Specific and universal legacy LimitBreak books remain recognized. The enchantment must already exist at its configured standard maximum; LimitBreak adds exactly one, cannot introduce an enchantment, and cannot exceed the absolute profile/progression maximum. Used overcap levels are derived as `current - standard`; there is no separate counter. Universal books require explicit selection. EcoEnchant maximums remain authoritative and administrator values may only tighten them; explicitly configured vanilla managed exceptions such as Fortune may deliberately exceed Bukkit's native safe maximum. Runes, pity, reward distribution, Black Archive gacha, dungeons, and NoxwardArchives are deliberately outside this project.

## Costs

`costs.yml` defines reusable payment options for application, fusion, removal, transfer, and socket expansion. Components inside an option are logical AND; options are logical OR. An empty component list is the explicit representation of free.

Components support Vault money, total XP points, XP levels, vanilla items, Nexo items, and uniquely tracked InfinityGear artifacts. Missing Vault/Nexo providers or invalid IDs disable affected options and never turn them into free operations. The GUI displays and lets players cycle configured options. Charging journals removed items—including unique UUID-bearing catalysts—and compensates earlier components in reverse order if a later withdrawal or mutation fails.

Fusion result weights charge every actual pair. Removal money uses `base × enchantment weight × level weight × profile multiplier`, then the configured per-overcap-level surcharge. Level tables use floor lookup; values beyond the last entry use the last weight, while levels before the first use the documented fallback.
Socket expansion uses the same structured floor-weight approach keyed by resulting capacity, allowing progressively larger Rivet/item/money requirements without an expression evaluator.

## Duplicate protection

Tracked kinds include `GEAR`, `RUNIC_ERASER`, `RUNIC_CONDUIT`, and `RUNIC_RIVET`; ordinary books and currencies are not tracked. Physical player/ender inventories, stable physical storage identities, double containers, retained opened storage, nested containers, dropped items, and debounced scans retain quarantine/revocation/rekey behavior and persistent sightings.

Detection is observational. It cannot prove detection of copies that are never simultaneously visible (for example, an offline inventory and an unopened chest), and arbitrary virtual inventories owned by other plugins are not globally enumerable.

For the approved initial Archive application topology, MariaDB is the approved
deployment quarantine mode and migration 13 provides its normalized authority
plus an explicit legacy SQLite importer. That architecture decision does not by
itself approve production cutover. The importer is operator-invoked only:
SQLite remains the default authority until Axel approves an offline maintenance
window, the actual deployment DB/WAL/SHM set is reconfirmed and backed up outside
the plugin directory, the import succeeds without unresolved conflicts and the
cutover is explicitly configured. MariaDB mode requires the exact accepted
import source ID and never opens or writes `duplicates.db`. After any MariaDB-mode
runtime mutation, falling back to the stale SQLite writer is unsafe. Import
conflicts preserve all source evidence and remain quarantined for manual
resolution.

## API compatibility

`InfinityGearService` is registered through Bukkit’s service manager. It provides immutable inspection snapshots, profile resolution, gear/artifact creation, explicit mutation results and reason/message codes, enchantment application, socket inspection, duplicate/quarantine status, eligible enchantments, and immutable resolved global-plus-profile enchantment policy queries. Mutation methods require the primary server thread.

The separate `com.infinitygear.api.v1.BookApplicationService` is the
operation-scoped entry point for tracked Archive book application. It accepts an
external `PolicyAuthority`, preserves server-thread inventory access and
worker-thread persistence, and recovers only by durable operation ID. Discovery
reports `book-application` only while that participant, MariaDB migration and an
explicit authority are live. The aggregate `book-lifecycle` capability remains
false; replacement, removal, transfer and fusion are not exposed.

`com.infinitypickaxes.api.InfinityPickaxesAPI` and legacy model/manager accessors remain deprecated adapters. They represent only `infinitygear:pickaxe`; non-pickaxe profiles return empty/null instead of masquerading as pickaxes. Generalized `GearEnchantChangeEvent` fires alongside compatible legacy pickaxe events for migrated pickaxe operations, and cancellation remains authoritative.

## Commands and permissions

- `/igear`: read-only held gear overview (`infinitygear.use`).
- `/igear give <profile> <player> [level]`: create profile gear.
- `/igear artifact <runic_eraser|runic_conduit|runic_rivet> <player>`: issue a tracked singleton.
- `/igear station bind <type>`, `status`, and `unbind`: manage exact proprietary station instances.
- `/igear station <runic-table|fusion-altar|gear-forge>`: administrator/test GUI bypass.
- `/igear xp adopt <player>`: explicitly place one uniquely held, non-quarantined EXPERIENCE item under MariaDB XP authority. Automatic adoption remains disabled.
- `/igear xp reconcile <uuid>` and `/igear xp issues`: retry receipt-only projection after physical custody is corrected and inspect durable fail-closed reconciliation records. Conflicts are never overwritten automatically.
- `/igear reload`, `setlevel`, `addxp`, `duplicate ...`, and `migration`: administration and diagnostics. For adopted items, `setlevel` and `addxp` use atomic ledger receipts and ordered absolute projection rather than legacy item writes.
- `/ipickaxe` and `/infinitypickaxe` remain deprecated aliases with `infinitypickaxes.*` compatibility permissions. Generic `/pickaxe` is intentionally not claimed.

See `plugin.yml` for granular give, artifact, reload, station, migration, duplicate, and station-bypass permissions. Locale/config synchronization only adds missing defaults and does not overwrite administrator edits.

## Build and verification

```bash
./gradlew test
./gradlew build
```

The production artifact is `build/libs/InfinityGear-2.0.0-SNAPSHOT.jar`. Unit tests cover pure enchantment/fusion/cost/transform/socket rules, compensation, inventory capacity, PDC parsing, data-folder idempotency, SQLite record migration, duplicate scanner hardening, GUI cancellation, commands, legacy behavior, XP adoption fencing, custody interaction guards, lifecycle capability fencing, and Paper recovery scheduling. Disposable MariaDB tests cover the XP authority, lifecycle migrations, attachment revisions, deployment epochs, identity claims, the quarantine authority/importer, contention, terminal release, rollback and exact restart recovery.

Task #7's exact `12d0986` build passed 342 tests and its
`0897c987c50b69ba7cecb7bad8ae39ddf588eee1a8b5fd4e0d2eceefe723d9fe`
plugin artifact passed the approved disposable Paper 26.2 build 121 / Java 25
single-server application matrix, including every phase boundary, controlled
process termination, exact value conservation and native player/container/
Ender/drop serialization. The exact evidence is in the
[clean Task #7 acceptance record](../NoxwardArchive/docs/task-7-clean-acceptance-results.md).
This acceptance does not cover Nexo, Vault, arbitrary
virtual inventories, cross-server transfer or production deployment. Production
installation, the offline SQLite backup/import/freeze and `book-application`
enablement remain separately gated; aggregate `book-lifecycle` remains false.
