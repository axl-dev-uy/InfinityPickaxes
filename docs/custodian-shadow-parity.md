# Custodian shadow parity

InfinityGear's legacy duplicate scan and enforcement remain authoritative. The optional Custodian
integration is a read-only soak/debug path controlled by `custodian-shadow.enabled`; production
servers should leave it disabled unless parity diagnostics are intentionally being collected.

The scanner starts one bridge epoch, heartbeats it, and runs only after the legacy debouncer has
settled. It adopts supported profile UUIDs without item metadata writes and submits only `PARTIAL`
identity contributions. It cannot call `COMPLETE`, register or transfer scope ownership, release
authority, quarantine, rekey, replace, or mutate an item or equipment profile.

## Snapshot and scope behavior

Every physical scope in one settled scan shares a strictly increasing observation generation.
Custodian can therefore retire the bridge's previous location when an item moves while retaining
multiple locations observed in the same scan as a real duplicate.

Stable scope IDs cover player inventory and Ender Chest, drops, supported block inventories,
double chests, and supported entity inventories. Double chests use the two actual block-state
coordinates in deterministic sorted order; Paper's shared combined-inventory location is not used
for either half.

Parity diagnostics compare identity sets, distinct physical-instance counts, and duplicate
decisions. Mismatches are logged only and do not alter legacy enforcement.

## Verified behavior

The full InfinityGear suite passed with 368 tests and zero failures:

```text
GRADLE_USER_HOME=/tmp/infinitygear-gradle \
  ./gradlew check shadowJar --rerun-tasks --console=plain
```

Live Paper 26.2 fixture checks demonstrated agreement for ordinary movement, drop and pickup on
both sides of the freshness interval, inventory/Ender Chest duplicates and their removal, block
and corrected double-chest scopes, chest-minecart movement, and rapid bridge restart invalidation.
Two-player checks additionally verified that simultaneous viewers of one double chest retain one
physical instance, while the same UUID in distinct player inventories is confirmed as a duplicate
by both implementations. Normal two-player transfers, disconnect/reconnect, and entity-container
movement continued to report matching identity sets, physical-instance counts, and decisions.
The cursor soak additionally demonstrated agreement for inventory-to-cursor, cursor-to-inventory,
cursor-to-container, click/drag debounce, and a cursor-only duplicate before its second copy was
placed into an Ender Chest. A clean restart does not preserve cursor contents in this Paper fixture;
the same loss occurs with vanilla dirt, so it is a native server/client boundary rather than an
InfinityGear or Custodian lifecycle effect. An in-process `/igear reload` retains shadow parity as
a tracked item moves inventory-to-Ender-Chest-to-inventory, confirming that the reloaded legacy
listener continues using the process-scoped shadow bridge.

## Known boundary

An item held only on the inventory cursor is not included by the current legacy or shadow settled
snapshot. Click and drag events involving a tracked cursor still request the usual debounced scan,
but that scan observes the post-debounce inventory and physical-container slots, never the cursor.
Thus inventory-to-cursor is temporarily unobserved; cursor-to-inventory and cursor-to-container
are observed once the item occupies a scanned slot; and a duplicate whose other copy is cursor-only
does not produce a duplicate decision.

Cursor blindness is accepted for the current soak period because both implementations match the
existing authoritative legacy coverage. Any change requires an explicit legacy-enforcement decision
and must not be folded into shadow parity work.
