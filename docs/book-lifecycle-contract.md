# Archive book lifecycle contract — slices 1 and 2

Status date: 2026-09-10. Slice 1 defined the public transaction vocabulary and
recovery state machine. Slice 2 implements its MariaDB journal and attachment
repository. Neither slice mutates Bukkit inventories, registers a lifecycle
service, selects a product provenance policy, or makes a lifecycle capability
available.

## Task #6 source checkpoint

The Task #6 continuation adds the first operation-specific physical
participant for tracked, no-replacement application. `BookApplicationService`
requires a separate production `PolicyAuthority`, retains server-thread Bukkit
access and worker-thread JDBC, uses operation-scoped PDC custody plus a
source-slot escrow token, and recovers only by durable operation ID. Migration
11 serializes active equipment/source claims and releases them only with a safe
terminal database transition. Durable attachment revision/value/operation are
rechecked before custody cleanup and acknowledgement.

`book-application` is reported separately only when both participant and policy
are live. Aggregate `book-lifecycle` remains false; legacy tracked-book routes,
replacement, removal, transfer and fusion remain unavailable. Java 25 `test
assemble` passed 332 tests with zero skips against disposable MariaDB. No live
Paper interruption/serialization acceptance, installed artifact change or
production policy is claimed, so Task #6 remains open.

The source point before this slice was `f006754`; production behavior and the
installed fixture artifact remain based on `16ae526`.

## Journal/attachment implementation — slice 2

Migration 10 now adds the journal-only implementation without changing the
public contract or exposing a live capability. `MariaBookLifecycleTransaction`
stores the canonical recoverable request plus normalized operation, physical
participant, equipment before/after image, output reservation and exact lineage
rows. Equipment attachment revision is independent of mining XP revision, and
attachment records are keyed by equipment identity plus enchantment key.

The migration owns six lifecycle tables: equipment attachment revisions,
enchantment-keyed attachments, operations, physical participants, reserved
outputs and exact source/destination lineage (the last four share the lifecycle
operation identity). The operation row retains the canonical request in a
private versioned binary encoding as well as normalized audit fields. Its
current phase, immediately preceding phase, preparation time and update time are
durable, which lets replay distinguish the identical committed CAS from a
different transition that merely names the same destination phase.

Preparation shares the `infinitygear_book_operations` operation-ID namespace
with issuance and the older transition ledger. It locks and validates source
book records/artifacts and the attachment revision/lineage while leaving source
consumption and attachments unchanged. Finalization revalidates those authorities
and atomically retires tracked sources, writes the already-declared fresh books
and canonical artifacts, applies/replaces/clears the attachment, and performs
one revision compare-and-set. Ordinary physical books remain participant records
only and never become lineage sources.

Phase advancement is fingerprint-bound and monotonic. Identical replay recovers
the stored state, conflicting operation reuse and stale revisions reject, and
applicable physical phases cannot be skipped. `ABORTED` remains preparation-only.
Post-custody `ROLLED_BACK` is recorded only while the journal can positively
verify that all durable source, output, attachment and revision authorities still
match their saved before-state; the future Bukkit participant remains responsible
for positively comparing/restoring the stored physical before-images first.

Java 25 `test assemble` passed 326 tests with zero skipped against a dedicated
disposable MariaDB schema. Eight new integration cases cover migration reruns,
complete request recovery, exact decimal attachment/output values, concurrent
identical/conflicting preparation, operation namespace collisions, phase CAS,
stale attachment revisions, finalization replay and injected pre-commit rollback.
No lifecycle service is registered and `book-lifecycle` remains false.

## Public contract

`com.infinitygear.api.v1.BookLifecycleRequest` is the immutable, versioned
payload for one physical lifecycle operation. Schema version 1 records:

- operation and actor UUIDs;
- apply, replace, remove, transfer, pair-fusion or bulk-fusion operation type;
- stable equipment UUID, expected attachment revision, exactly-next resulting
  revision, exact inventory slot and serialized equipment before/after images;
- exact physical input inventory, slot, expected stack amount, consumed amount,
  tracked Archive identity where present, and serialized before-image;
- intended enchantment key and before/after levels, separately stating whether
  the before/after enchantment has Archive attachment lineage;
- fresh output book UUIDs, enchantment/level, exact destination slots and
  canonical serialized items;
- an explicit policy reference, explanation, exact lineage sources,
  destinations and exact disposed value.

Collections are immutable and canonicalized where order is not semantic. Item
bytes are defensively copied. Every tracked Archive input must be unstacked,
every output identity must be fresh, and every physical participant must belong
to the recorded actor. Output reuse of a consumed input slot is permitted only
when that input is completely consumed.

The request computes a lowercase SHA-256 fingerprint over length-prefixed
canonical binary fields. The fingerprint covers the schema, operation, actor,
equipment revisions and images, inventory locations and amounts, enchantment
change, output identities/items, policy reference/reason and exact value
decision. Equivalent decimal spellings normalize through `DECIMAL(38,18)`
semantics. Reordering set-like inputs or lineage entries does not change the
fingerprint; changing a meaningful field does.

`com.infinitygear.api.v1.BookLifecycleTransaction` is the blocking durable
journal contract. Implementations must run away from the server thread:

- `prepare` is idempotent only for the same operation UUID and fingerprint;
- reuse of an operation UUID with a different payload rejects;
- `find` is the only authority for recovery and is operation-scoped;
- `advance` is a fingerprint-bound phase compare-and-set;
- repeating an already committed identical transition may recover the existing
  state, while a conflicting expected/current transition rejects.

The contract never authorizes scanning inventories, inferring completion from
an absent item, recreating value from an uncommitted request, or treating an
exceptional response as proof of rollback.

## Durable phases

The contract names the physical interruption boundaries before their MariaDB
or Bukkit implementations exist:

1. `PREPARED`: exact request and images are durable; no physical mutation.
2. `CUSTODY_MARKED`: operation-scoped physical custody evidence is present.
3. `SOURCES_REMOVED`: prepared sources were removed with positive custody
   evidence; absence alone cannot establish this phase.
4. `EQUIPMENT_MUTATED`: exact equipment after-image and attachment revision are
   physically present.
5. `OUTPUTS_INSERTED`: every prepared output identity is physically present at
   its recorded destination.
6. `FINALIZED`: durable ledger, attachment and physical disposition agree.
7. `ACKNOWLEDGED`: the caller durably accepted the finalized result.

`ABORTED` is allowed only before physical mutation. Once custody is marked, a
failed operation can reach terminal `ROLLED_BACK` only after the exact physical
before-images have been positively restored and custody markers cleared.
Finalized operations cannot be relabelled as aborted or rolled back.

Applicable phases may be skipped when the immutable request has no participant
of that kind. The future journal implementation must validate each skip against
the stored request rather than trusting a caller-supplied phase alone.

## Provenance boundary

The DTO can represent unresolved cases so a future authorized policy can make
an explicit decision, but representation is not authorization:

- replacement may begin with an ordinary installed enchantment or an existing
  Archive attachment; the two are distinct facts;
- unequal Archive inputs and mixed Archive/ordinary fusion can be represented;
- ordinary physical inputs are never lineage sources and cannot acquire or
  contribute Archive value;
- replacement outputs, removal/transfer outputs, bulk allocations and explicit
  value disposal are recorded exactly;
- destination value plus explicitly disposed value must equal source value.

`ProvenancePolicy.unresolved()` remains the production default and rejects all
unresolved transitions before durable or physical mutation. A test policy
reference in a request is mechanics evidence only and must never activate a
capability.

## Contract verification history

`BookLifecycleContractsTest` covers canonical fingerprints, operation-ID
conflicts, all six operation shapes, exact revision advancement, defensive
copies, exact decimal conservation/disposition, ordinary-source isolation and
the recovery phase graph. `ArchiveContractsTest` verifies the new contract does
not expose implementation packages.

The slice-1 Java 25 offline `test assemble` run discovered 318 tests: 288
executed and passed, while 30 environment-gated MariaDB tests skipped because
the disposable database was intentionally stopped. The slice-2 run superseding
that checkpoint passed all 326 tests with zero skips against disposable MariaDB.
Both the API and provider jars assembled; only the implementation-independent
contract classes are present in the API jar.

## Next bounded slice

Implement the server-thread physical lifecycle participant while retaining the
journal as the only recovery authority:

1. Capture exact player inventory/equipment locations, UUIDs, stack amounts,
   quarantine state, attachment revision and serialized before/after images on
   the server thread; perform no Bukkit access on a database worker.
2. Persist `PREPARED` off-thread, return to the server thread, re-resolve unique
   custody recursively and compare every participant before marking custody or
   mutating anything.
3. Apply each physical boundary exactly once and advance its matching phase only
   after positive observation: source removal, equipment after-image, then all
   output insertions. Absence is never evidence.
4. Recover only by an explicit operation ID and stored request. For a failure
   after custody, restore and positively compare every recorded before-image and
   clear custody before requesting `ROLLED_BACK`; use `ABORTED` only before the
   first physical mutation.
5. Route the mechanically unambiguous tracked application case first. Preserve
   ordinary-book behavior and keep tracked books rejected by every legacy path.
   Replacement, removal, transfer, pair fusion and bulk fusion remain unavailable
   unless both their physical adapter and an explicit authorized policy exist.
6. Fence reload, disconnect, death, close, full inventory, item movement,
   duplicates, malformed/quarantined items, worker completion after shutdown and
   every persisted interruption phase. Add disposable Paper/MariaDB acceptance.

Do not infer a production provenance decision from the test policy references
used by journal tests. Register no aggregate lifecycle capability until at least
one routed operation has accepted physical recovery evidence; report only the
operation-specific paths actually proven.
