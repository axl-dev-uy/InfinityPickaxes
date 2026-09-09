# Archive book lifecycle contract — slice 1

Status date: 2026-09-09. This is the first bounded implementation slice of
canonical surface #5. It defines the public transaction vocabulary and recovery
state machine only. It does not persist lifecycle operations, mutate Bukkit
inventories, register a lifecycle service, select a product provenance policy,
or make any lifecycle capability available.

The current source point before this slice was `c77289c`; production behavior
and the installed fixture artifact remain based on `16ae526`.

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

## Verification

`BookLifecycleContractsTest` covers canonical fingerprints, operation-ID
conflicts, all six operation shapes, exact revision advancement, defensive
copies, exact decimal conservation/disposition, ordinary-source isolation and
the recovery phase graph. `ArchiveContractsTest` verifies the new contract does
not expose implementation packages.

The Java 25 offline `test assemble` run discovered 318 tests: 288 executed and
passed, while 30 environment-gated MariaDB tests skipped because the disposable
database was intentionally stopped. Both the API and provider jars assembled,
and the API jar contains the lifecycle contract classes.

## Next bounded slice

Implement a MariaDB lifecycle journal and attachment repository without Bukkit
routing:

1. Add restart-safe numbered migrations for equipment attachment revisions,
   enchantment-keyed attachments, lifecycle operations, physical participants
   and operation output/lineage records.
2. Make preparation atomically validate the full fingerprint, source book
   records, attachment revision and exact policy decision without retiring a
   source or changing an attachment.
3. Make finalization atomically retire consumed book identities, create the
   already-declared output identities/artifacts, apply/replace/clear attachment
   lineage and advance the equipment attachment revision once.
4. Add operation-scoped phase compare-and-set, identical replay and conflicting
   operation-ID behavior. No broad recovery scan or timed inference.
5. Cover migration reruns, concurrent duplicate/conflicting submissions,
   revision fencing, exact totals, rollback and fresh-repository replay against
   disposable MariaDB.

Do not register the journal as a live physical participant in that slice.
`book-lifecycle` and every future per-operation capability remain false until a
server-thread physical participant and its recovery behavior are separately
implemented and accepted.
