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

The full InfinityGear suite passed with 365 tests and zero failures:

```text
GRADLE_USER_HOME=/tmp/infinitygear-gradle \
  ./gradlew check shadowJar --rerun-tasks --console=plain
```

Live Paper 26.2 fixture checks demonstrated agreement for ordinary movement, drop and pickup on
both sides of the freshness interval, inventory/Ender Chest duplicates and their removal, block
and corrected double-chest scopes, chest-minecart movement, and rapid bridge restart invalidation.

## Known boundary

An item held only on the inventory cursor is not included by the current legacy or shadow settled
snapshot. Changing that boundary would change legacy enforcement coverage and is intentionally
separate from this observational integration.
