# Archives prerequisite contract, version 1

## Audit and implementation decisions

Baseline already matches Java 25 and Paper 26.2 (`26.2.build.112-stable`). Keep existing service consumers compatible; publish a separate `com.infinitygear.api.v1` surface containing only public immutable DTOs, Java and Bukkit types. No Archives gameplay belongs here.

Mining currently funnels through `BlockBreakListener.onBlockBreak` at MONITOR into `LevelManager.addXp`. The other caller is the legacy admin add-XP command; the newer gear admin command writes gear XP directly. `addXp` currently increments mined counts even for admin XP. Separate the mined-count operation from generic XP. `PickaxeLevelUpEvent` is not a credit event.

The locally inspected libreforge 2026.33 shared `MineBlockEffect.breakBlocksSafely` calls `Player.breakBlock` normally, sets AIR for `effects.use-setblock-break`, and calls `breakNaturally` for `prevent_trigger`. Current local configuration disables both flags. Blast Mining uses mine_radius (excluding original), Dynamite uses mine_radius plus break_block, and Vein Miner uses mine_vein including the original. Libreforge dispatches at HIGH; InfinityGear at MONITOR can observe both nested and outer events for the original. Its old placement check removes an LRU marker; nested checks, restart and eviction lose provenance. eco's existing chunk-PDC `BlockUtils.isPlayerPlaced` is preferable to a competing placement ledger, but its lifecycle/reset behavior is not an authoritative generation contract.

The required optional provider must supply a stable physical-instance ID before effects, placement legitimacy, source attribution and successful-break confirmation. It must distinguish a reset block at the same coordinates, including same-tick resets. Bukkit events alone cannot prove physical success or distinguish a replacement identical to the original. No timestamp/coordinate blacklist can satisfy that contract. Missing provider means strict credits are unavailable, never guessed. The normal XP adapter may retain legacy behavior without claiming strict notifications. setblock/natural-break paths require explicit completion from that provider; third-party binaries will not be patched.

Books: `CanonicalBookFactory` intentionally discards arbitrary PDC. Pair and bulk fusion generate fresh canonical outputs in previews. `EnchantmentItemTransforms` clones for removal and creates a new book for transfer. Service application consumes the book and stores enchantments without lineage. Existing stations have no durable external-operation journal. Therefore previews must not commit provenance transitions, and tracked items must not enter these legacy mutation paths until a transaction-aware lifecycle is available.

Policies needing product input: unequal Archive source values, mixed ordinary/Archive fusion, replacement value disposition, removal versus destruction, and bulk allocation of source values to outputs. The default policy rejects unresolved transitions. A configured policy returns exact output values; the ledger validates conservation, source retirement and no value minted from ordinary inputs. A mixed operation cannot upgrade ordinary value into Archive value. This policy is separate from Archives grade/category/rarity rules.

Persistence: legacy duplicate quarantine uses local SQLite and unprefixed tables in its own file; station bindings use their existing file store. Keep those systems untouched in this focused change. New integration state uses MariaDB-owned `infinitygear_*` tables and numbered migrations, exact DECIMAL values, stable external operation/reward IDs and unique UUIDs. No other plugin modifies these tables. Bootstrap supplies connection details only. MariaDB driver is resolved by Paper `libraries`, never shaded. Automatic migration of existing SQLite quarantine data is a separate deployment operation, not silently performed.

Issuance returns a recoverable representation, not permission to insert another physical copy on each retry. Delivery must use a claim/transfer protocol in the caller's journal. A UUID alone cannot prevent cloning; ledger validation and duplicate quarantine remain required. Database and Bukkit inventory do not share one transaction. No crash-safe inventory mutation claim is made until participant recovery is implemented.

## Review steps

1. This audit and policy/integration boundaries.
2. Versioned discovery and capability API, independent API jar.
3. Runtime MariaDB dependency and owned migrations/repositories.
4. Idempotent issuance and provenance ledger; legacy mutation guards.
5. Canonical credit state machine, immutable notification and normal XP fixes.
6. Tests and explicit readiness/blocker report.

## Implemented surface and readiness

| Area | Implemented | Activation/remaining work |
| --- | --- | --- |
| Discovery | `ArchiveIntegrationService` registered through ServicesManager, immutable DTOs, content revision per requested gear level, native caps/targets/conflicts, effective policy and disabled-profile visibility | Consumers poll the revision on the server thread. Active native metadata needs an actual Paper/EcoEnchants runtime test. |
| API artifact | `archivesApiJar`, classifier `archives-api-v1`, contains only `com.infinitygear.api.v1` | Compile-only consumer dependency; provider owns runtime classes. Existing service remains compatible. Treat these new contracts as prerelease until the remaining provider decisions are resolved. |
| Issuance | `BookIssuanceService`, canonical tracked book, MariaDB operation/reward uniqueness, immutable receipts, provenance-authority validation, persisted source values, ledger-based validation | `database.yml` must enable MariaDB and an external `ProvenanceAuthority` must register. Returned bytes are recovery data, not repeat-delivery permission. Driver `org.mariadb.jdbc:mariadb-java-client:3.5.6` loads through Paper libraries. |
| Deduplication | `ARCHIVE_BOOK` uses existing tracked UUID/scanner facility; malformed/consumed ledger identities reject | Existing quarantine store remains local SQLite. Cross-server physical custody and cross-server quarantine synchronization are not yet implemented. |
| Lifecycle | Transactional MariaDB source retirement, exact-value conservation, grouped parent lineage, fresh output UUIDs, operation fingerprint/replay, explicit policy interface | This is a ledger participant, **not** live inventory application/removal/transfer. Service capability remains unavailable. Legacy mutation routes reject marked books before consumption. |
| Normal XP | LOWEST material/placement snapshot, AIR checks at MONITOR, original-material XP lookup, mining count separated from admin XP | Fixes the traced nested Vein original/AIR path. This still follows legacy event acceptance, not authoritative physical completion. It emits no strict mining notification. |
| Placement | Removed InfinityGear's destructive, evicting LRU; delegates read-only to eco chunk PDC | eco owns placement/movement/cleanup. Existing `anti-exploit.prevent-placed-blocks` and `placed-blocks-cache-size` no longer control this adapter. Reset cleanup and physical-generation identity still need provider integration. |
| Strict mining | `MiningAuthority` boundary, begin/complete coordinator, durable MariaDB instance reservation/recovery state, immutable non-cancellable `CreditedBlockEvent` | **Not wired to raw event dispatch or registered as an available strict capability.** Requires actual-success/reset authority plus async XP/journal participant recovery. No post-credit event is emitted in production yet. |

### Explicit blockers and product decisions

1. A supported producer must report actual successful break completion and durable block-instance/reset identities, including two identical regenerations at one location in one tick. `MiningAuthority` names this boundary. Libreforge's normal, setblock and natural-break branches need coverage; no binary patches or inferred credit from explosions have been added. Strict capability is unavailable for all modes until this is supplied; changing bypass flags cannot silently enable it.
2. MariaDB commit, Bukkit PDC XP, and inventory mutations are not a single transaction. The mining journal reserves before XP and marks ambiguous failures for recovery, but production async orchestration, replayable notification outbox and XP participant reconciliation remain to implement. Do not invoke its blocking JDBC methods on the server thread. Legacy mining still awards XP from accepted events; this is explicitly not the new guarantee.
3. A provenance policy must decide unequal-value fusion, mixed sources, replacement disposition, removal/destruction and bulk output allocation. `ProvenancePolicy.unresolved()` rejects all such operations. Test policies do not establish product defaults. The ledger currently accepts registered Archive source book IDs only; ordinary items are not assigned value or admitted as counterfeit ledger sources.
4. Application needs enchantment-associated attachment lineage on equipment, and removal/transfer need the corresponding recoverable inventory transaction. Those are not implemented by the book-output transition ledger. Its generic operation enum is a contract proposal, not evidence of those adapters working. No live lifecycle capability is exposed until these participants exist. Ordinary non-Archive book behavior is unchanged.
5. MariaDB runtime library resolution must be exercised on the target Paper deployment; local integration tests use the same driver as a test-only dependency. A real Paper/EcoEnchants fixture is still required for native book serialization/Nexo replacement, authoritative AoE completion, piston/reset event behavior and full crash/disconnect recovery. Local mocks only verify the adapter boundaries, not third-party implementations.

### Verification and reproducibility

Run `JAVA_HOME=<Java 25> ./gradlew test assemble`. MariaDB tests require `INFINITYGEAR_TEST_JDBC_URL` pointing at a **disposable** database, with local test credentials `igear_test` / `igear_test`; absent URL explicitly skips those tests. They never read the plugin's database.yml. Tests create owned schema tables but do not delete deployment data.

MariaDB coverage includes concurrent issuance/retries, differing-payload rejection, separate reward UUIDs, policy rejection without retirement, pair/bulk source retirement, exact decimal conservation, rollback on inflation, replay across repository instances, operation-ID namespace conflicts, durable mining instance deduplication and a new generation at identical coordinates.

Unit coverage includes coordinator normal/AoE labels, repeated/nested instance completion, rejected/placed/failed/AIR inputs, ambiguous XP recovery, non-cancellable notifications, same-position generation changes, the concrete legacy nested Vein/AIR path, immutable placement snapshots, non-destructive authority delegation, public DTO isolation/defensive copies, profile revisions and missing native entries, service registration, unresolved policy rejection and guards on legacy tracked-book mutation. Source labels in coordinator tests are not live Libreforge reproductions.

Verified 2026-09-05: Java 25 `test assemble` passed all 226 tests, zero skipped, including four MariaDB integration tests against an isolated local MariaDB 12.3.3 instance using driver 3.5.6. Both the plugin and API jars built. Jar inspection confirmed the Paper libraries declaration and no MariaDB driver classes or MariaDB server binaries in the shaded plugin. Real-server verification and remaining capability blockers above are still outstanding; this is not a declaration that Archives can start consuming strict credits or live book lifecycle mutations.
