# Archive credit probe (disposable Paper fixture only)

This is a test subscriber for `MiningCreditDeliveryService`. It never grants a
reward. A successful callback means it fsynced a unique `creditId` decision file
containing the full immutable payload and the fixed `NO_REWARD` decision. An
identical replay reads the same file; a changed payload fails closed. The jar
contains no InfinityGear API classes and must never be used as the Archive
consumer or installed on a production server.

Build after `./gradlew assemble` from the InfinityGear repository root:

```bash
./gradlew -p tools/acceptance/archive-credit-probe build --offline
```

The jar is `build/libs/archive-credit-probe-1.0.0.jar` under this directory.
The Noxward fixture copy and completed live test evidence are preserved outside
active `plugins`; see [the acceptance record](../../../docs/archive-credit-delivery-live-acceptance.md).
The plugin's own
`config.yml` defaults to `enabled: false` and checks the exact disposable world
UUID. Registration and activation are separate commands.

Console commands, after the plugin and InfinityGear have loaded:

```text
creditprobe register
creditprobe activate
creditprobe status
creditprobe mode accept
creditprobe mode hold
creditprobe mode defer
creditprobe mode error
creditprobe mode hang
creditprobe release
creditprobe unregister
```

`hold` commits the no-reward decision file, then withholds its acceptance stage;
the file should exist while InfinityGear's Archive acknowledgment remains NULL.
After a clean restart, use `register` and `mode accept` to replay the same credit
without another decision file. `defer`, `error`, and `hang` test false, exception,
and timeout paths. Run only one mode at a time and wait for the bounded dispatcher
poll between steps. Do not use `/reload` to simulate process restart.

For a live physical credit, the server fixture additionally needs reviewed
migration 14, `mining-delivery.archive-contract-enabled: true`, an explicitly
approved ordinary guarded mining fingerprint, and an adopted XP pickaxe. These
changes are not part of installing this inactive jar. The 2026-09-24 live pass
restored the pretest configurations byte-for-byte and removed the probe from
active plugins afterward. Preserve the fixture's pretest database and configs;
restore guarded mining, approval, enabled paths, and the probe to disabled after
any future pass.
