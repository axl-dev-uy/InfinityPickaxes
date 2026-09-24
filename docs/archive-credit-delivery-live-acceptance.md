# Archive mining credit delivery v1: disposable fixture acceptance

## Scope and artifacts

On 2026-09-24, the upstream InfinityGear contract was tested on the disposable
NoxwardArchive Paper 26.2 build 121 / Java 25 fixture and its dedicated MariaDB
schema `archives_acceptance_task7_clean_20260911` on loopback port 33318.
InfinityGear source was the uncommitted `task/archive-credit-delivery-v1` branch
at base `2d0f6c613bb794d26c2ffadb6a20605027985c9f`. The installed provider
jar SHA-256 was
`09accb94e02572983d79adf07ebec281b4ebbcb3efc4f4c3bf5c01ab870a1641`;
the acceptance-only probe jar SHA-256 was
`111d646a1d05bf91c92eabdf4190b79b0b791aed66b2674acfaf52f28c6a8058`.

The probe is a small subscriber under
[`tools/acceptance/archive-credit-probe`](../tools/acceptance/archive-credit-probe/README.md).
Its successful callback fsyncs a unique credit-ID file containing the full
immutable payload and a fixed `NO_REWARD` decision. An identical replay reads
the existing file; changed payload is rejected. The probe never grants an
Archive reward or exercises NoxwardArchive's future consumer.

Before any mutation, Paper and MariaDB were stopped and the entire 165 MiB
fixture data directory plus both plugin configuration files were copied under
`run/acceptance-backups/credit-delivery-v1-live-20260924/` in the NoxwardArchive
workspace. Source credit IDs were verified as `CHAR(36) CHARACTER SET ascii
COLLATE ascii_bin`; the schema marker was 13. Migration 14 was manually applied
to **only** that fixture schema, then its marker and three additive tables were
verified. The subscription began inactive. No persistent or production database
was touched.

The probe and InfinityGear's Archive service flag were enabled only in the
fixture. AxMines' ordinary guarded path was approved for the exact complete
candidate fingerprint
`2f37ae8d28d3a3dbf0a13cc5a39d6c713abc2b9a1a92ccbf6c4c3af04e523799`;
only `ordinary` was enabled. The probe was registered, then `activate()`
committed asynchronously at 17:39:33 local time. The pre-activation schema had
zero mining credits and zero Archive deliveries.

## Live results

Officer_Ray initially mined with a test pickaxe that had no explicit XP
adoption in this clean schema. The journal recorded 180 `No matching explicitly
adopted XP account` and 98 `UNCONFIRMED_CANCELLED` attempts, with zero credits,
XP receipts, Archive deliveries, or probe callbacks. `/igear xp adopt
Officer_Ray` committed revision 0 at 17:44:10. None of those rejected attempts
was replayed as a credit.

| Sequence | Credit ID | Observed consumer and acknowledgment result |
| ---: | --- | --- |
| 1 | `84eca522-90b1-41af-a188-8db7c35b70b1` | Probe committed one new fsynced no-reward decision, then Archive acknowledgment committed at 17:46:09.448357. |
| 2 | `0e3826a5-d661-4645-9ac6-69fc8e46b28e` | `HOLD` committed the decision but withheld success. Repeated delivery read the saved decision with `inserted=false`; acknowledgment remained NULL. The asynchronous unregister fence completed while it was pending. After clean Paper restart, the probe remained absent until explicit registration. Registration replayed the saved decision with `inserted=false`; acknowledgment committed at 17:49:12.745923. |
| 3 | `67338aa6-aa66-4098-9d2f-596a67d93749` | Enrolled with an XP receipt while sequence 2 was held. No callback advanced past the head. After sequence 2 was acknowledged, its first callback committed a new decision; acknowledgment committed at 17:49:17.751346. |
| 4 | `040dbab2-fa65-49c8-b2d4-545bcc5d4490` | `DEFER` returned false repeatedly. The credit retained its XP receipt and sequence 4, with no probe decision file or Archive acknowledgment. Switching to `ACCEPT` retried that same ID, committed one decision, and acknowledged at 17:51:32.750859. |

Final SQL showed exactly four `COMPLETED` credits, four XP receipts, four
local inbox rows, four original XP notifications, four Archive delivery rows,
and four stable sequence rows. Each Archive row had exactly one acknowledgment
timestamp. The probe had exactly four decision files. The local inbox and
original notification did not acknowledge Archive delivery: sequence 2's local
rows existed throughout its Archive-pending interval.

The earlier full automated suite passed **378/378**, zero skipped, against a
separate disposable MariaDB fixture. Those tests cover activation boundaries,
atomic receipt eligibility, payload digest conflict, sequence recovery after an
enrollment/sequence gap, callback failure/exception/timeout, and unregister
fencing. The live pass above additionally verifies actual Paper/Bukkit service
registration, worker-to-server callback handoff, a real guarded break, durable
decision timing, ordered head blocking, false-return retry, and clean restart.
It was a clean restart, not a forced-crash test; the real Archive reward
consumer and claim path remain untested and unimplemented here.

## PR review follow-up: bounded polling

PR #1 review found that the original idle poll scanned all retained delivery
rows while holding the subscription's exclusive lock, and that next-pending
reads could scan an acknowledged prefix. Migration 15 and the matching store
change add a pending-only sequencing queue and a durable sequence cursor. The
schema and one-time backfill are specified in the
[contract](archive-credit-delivery-contract.md#additive-migration-15-polling-bound-proposed-for-review).
Migration 15 was applied only to separate empty disposable MariaDB scratch
schemas, not to the earlier live acceptance schema. The MariaDB test seeds
2,000 acknowledged deliveries, verifies that the next-pending plan uses the
sequence primary-key range, and checks that an idle poll finishes while one
receipt transaction holds a shared activation lock and another acquires one.
It also checks upgrade recovery of an unsequenced delivery and cursor.
After isolating its synthetic history rows from other tests, the complete
fixture-backed suite passed **380/380, zero skipped**. A fresh Paper live pass
with migration 15 has not been run; the first live pass above predates this
polling improvement.

## Restored state and handoff

After the test, Paper was stopped cleanly; the exact pretest AxMines and
InfinityGear configuration files were restored byte-for-byte. Guarded breaks
are false, the approved fingerprint is blank, enabled paths are empty, and
`mining-delivery.archive-contract-enabled` is absent/default false. The probe
jar and data were moved out of active plugins; Paper and fixture MariaDB were
stopped. The disposable schema retains migration 14 and four test credits for
inspection. Decision files and the local machine's detailed result record are
preserved in ignored acceptance artifacts. No NoxwardArchive source was edited,
and nothing was merged or deployed.

Archive integration may begin only after the `archives-api-v1` artifact is
published and the consumer independently implements a durable unique credit-ID
reward-or-no-reward decision. The Archive consumer must compare immutable
payloads on replay, return success only after its decision commits, and treat
delivery acknowledgment as distinct from player claiming. Production migration
14–15 and feature enablement still require separate approval.
