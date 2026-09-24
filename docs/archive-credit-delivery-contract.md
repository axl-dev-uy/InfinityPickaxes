# Archive mining credit delivery v1

`MiningCreditDeliveryService` is the implemented public `archives-api-v1` entry
point. InfinityGear registers its implementation with Bukkit's services manager
only when `mining-delivery.archive-contract-enabled: true` and migrations 14–15 are
present. The shipped setting is `false`. Archive loads that
service, calls `register(Consumer)` on the server thread, and explicitly calls
`activate()`. Registration supplies a live callback; activation is a separate,
durable enrollment decision. `activate()` returns immediately on the server thread;
its stage completes after database work and commit on the integration worker.
Activation is idempotent. The first enrolled credit is the first new XP receipt
transaction ordered after activation at the subscription lock. There is no
historical scan or legacy import. An absent Archive plugin can
register later and receive already enrolled, unacknowledged credits.

The callback runs on the Bukkit server thread and must return a stage promptly.
Archive performs its SQL work on its own executor, commits a unique credit-ID
decision record (reward or durable no-reward), then completes the stage with
`true`. The decision record must compare all immutable `MiningCredit` fields on
replay. An identical replay returns the saved decision without rerolling. A changed
payload for the same ID fails closed and remains visible for investigation. A
`false` result, exception, timeout, loss of persistence, or shutdown never grants
InfinityGear permission to acknowledge. Acknowledgment means the decision was
committed, not that a player claimed a reward.

InfinityGear keeps separate Archive delivery rows and acknowledgments. Its local
mining inbox and `CreditedBlockEvent` remain observational and cannot acknowledge
Archive delivery. Reads and acknowledgments run on the integration database worker;
only the consumer invocation crosses to the Bukkit server thread. Each Archive poll
delivers at most one credit and has a configured acceptance timeout. Delivery is strictly by the
persisted `delivery_sequence`. A failed or deferred head credit blocks all later
credits until it is durably decided and acknowledged; this is required for Archive's
stateful pity calculation. The sequence is assigned by one worker after enrollment
transactions commit. It is the ordering of committed rows as first observed by
that worker, breaking ties by canonical credit ID; it is not physical break order.
An enrollment transaction that commits later can receive a later sequence even if
its physical break occurred earlier.

`unregister()` immediately prevents new callback scheduling and returns a stage.
The stage is a fence queued on the serial database worker. An acknowledgment that
was executing when unregister began may commit before the fence; no acknowledgment
from that registration can commit after the stage completes. Callers must not wait
for the stage on the server thread. A consumer commit after unregister remains
unacknowledged for replay. Shutdown completes the fence before closing the shared
worker. Re-registration or restart then replays the same credit.

Only a `COMPLETED` journal credit with an atomic XP receipt can be enrolled and
delivered. A reservation, recovery-required record, voided attempt, uncertain
break, or receiptless completion is never a subscription source. No raw Bukkit
break listener is part of this contract.

## Additive migration 14 (approved for disposable fixture testing)

```sql
CREATE TABLE infinitygear_mining_archive_subscription (
  subscription_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  active BOOLEAN NOT NULL,
  activated_at TIMESTAMP(6) NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO infinitygear_mining_archive_subscription(subscription_id, active)
VALUES ('archive-v1', FALSE);
CREATE TABLE infinitygear_mining_archive_deliveries (
  credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  digest_version SMALLINT UNSIGNED NOT NULL,
  payload_sha256 BINARY(32) NOT NULL,
  acknowledged_at TIMESTAMP(6) NULL,
  FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_credits(credit_id)
) ENGINE=InnoDB;
CREATE TABLE infinitygear_mining_archive_order (
  delivery_sequence BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_archive_deliveries(credit_id)
) ENGINE=InnoDB;
INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (14);
```

The source `infinitygear_mining_credits.credit_id` is exactly `CHAR(36) CHARACTER
SET ascii COLLATE ascii_bin NOT NULL UNIQUE`; the two foreign-key columns above
match it. The source's migration methods create their own tables and write their
own `infinitygear_schema_migrations` markers. Version 10 belongs to book
lifecycle; 13 was the highest marker before this contract. The Archive migration
method records 14 after successful DDL and seed insertion. There is no separate
migration runner to take that step.

## Additive migration 15 (polling bound; proposed for review)

Migration 14 remains exactly as approved. Migration 15 adds a pending-only queue
and a durable acknowledgment cursor:

```sql
CREATE TABLE infinitygear_mining_archive_unsequenced (
  credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_archive_deliveries(credit_id)
) ENGINE=InnoDB;
INSERT IGNORE INTO infinitygear_mining_archive_unsequenced(credit_id)
SELECT d.credit_id FROM infinitygear_mining_archive_deliveries d
LEFT JOIN infinitygear_mining_archive_order o ON o.credit_id=d.credit_id
WHERE o.credit_id IS NULL;
CREATE TABLE infinitygear_mining_archive_cursor (
  subscription_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  next_delivery_sequence BIGINT UNSIGNED NOT NULL,
  FOREIGN KEY (subscription_id) REFERENCES infinitygear_mining_archive_subscription(subscription_id)
) ENGINE=InnoDB;
INSERT IGNORE INTO infinitygear_mining_archive_cursor(subscription_id,next_delivery_sequence)
SELECT 'archive-v1',COALESCE((SELECT MIN(o.delivery_sequence)
  FROM infinitygear_mining_archive_order o JOIN infinitygear_mining_archive_deliveries d
  ON d.credit_id=o.credit_id WHERE d.acknowledged_at IS NULL),
  (SELECT COALESCE(MAX(delivery_sequence),0)+1 FROM infinitygear_mining_archive_order));
INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (15);
```

Apply migration 15 only during an offline provider maintenance window; the
backfill must not race an older provider's acknowledgments. The one-time
backfill examines only existing Archive delivery/order rows. It
does not enroll earlier mining credits or change any receipt, XP notification,
custody, provenance, profile, or legacy record. The queue row is inserted in the
same receipt transaction as a new Archive delivery, and is removed in the same
transaction that assigns its order row. A crash on either side leaves exactly
one durable state. An idle poll probes the pending-only queue's primary key and
does not take the exclusive subscription lock. A nonempty poll takes that lock
and sequences at most 100 queued IDs by the established canonical credit-ID
tie break. Concurrent receipt transactions keep compatible shared locks.

The cursor points to the first not-yet-acknowledged sequence, or to one past
the largest assigned sequence when none is pending. Acknowledgment locks and
advances the cursor in its own transaction without locking the activation row.
The next-pending query seeks on the order table's primary key from this cursor;
it does not scan an acknowledged prefix. Sequence gaps remain valid. An
acknowledged row at the cursor or a changed payload fails closed. Migration 15's
backfill may scan retained Archive history once during an explicitly scheduled
upgrade; normal polling and acknowledgment do not.

The provider bootstrap checks for both migrations but does not apply either.
Migration 14 was applied only to the named disposable MariaDB fixture for live
acceptance. Migration 15 is under review. Their future application to a
persistent database requires separate authorization; the feature remains
disabled by default until then. The API jar has been built locally but is not
published for Archive integration yet. See the
[live fixture record](archive-credit-delivery-live-acceptance.md).

The receipt transaction takes `LOCK IN SHARE MODE` on the singleton subscription
row and inserts a delivery row only if active. Concurrent receipt transactions can
hold shared locks together. Activation takes `FOR UPDATE` on the same primary-key
row before enabling it, so it waits for older enrollment transactions and excludes
older credits without timestamps. Both locks must be acquired inside transactions;
the receipt path already uses `READ COMMITTED` and `autocommit=false`. The worker
briefly takes an exclusive lock on that row only when the pending-only queue has
work, to sequence a bounded set of committed deliveries. This can temporarily
wait behind receipts, but receipts do not take exclusive locks against each
other. The sequence worker commits its batch before dispatching the first credit.
It never assigns sequence numbers from inside concurrent XP transactions.

MariaDB documents shared and exclusive InnoDB row locks under
[`LOCK IN SHARE MODE`](https://mariadb.com/docs/server/reference/sql-statements/data-manipulation/selecting-data/for-update)
and [InnoDB lock modes](https://mariadb.com/docs/server/server-usage/storage-engines/innodb/innodb-lock-modes).
It also notes that [auto-increment values can be lost on rollback](https://mariadb.com/docs/server/reference/data-types/auto_increment),
which is why the sequence is assigned after receipt commit and is not interpreted
as a gapless counter or physical break timestamp.

Digest version 1 is SHA-256 over the ASCII domain tag
`InfinityGear/MiningCredit/v1` followed by every `MiningCredit` field in record
order. UUIDs are 16 big-endian bytes, integers are signed 32-bit big-endian,
booleans are one byte (`0` or `1`), and strings and the source enum name are UTF-8
with signed 32-bit big-endian byte-length prefixes. There are no nullable fields.
The digest includes `creditId`, `legitimate`, and `successful`. An unknown digest
version or a mismatch against the journal at read or acknowledgment fails closed
and remains pending for investigation. The Archive acknowledgment updates only
`acknowledged_at`, after consumer success and a second digest/eligibility check.
No existing XP receipts, notification acknowledgments, custody, profile data, or
legacy rows are rewritten. The new shared lock and insertion add per-credit
database cost when delivery is enabled.
