package com.infinitygear.persistence;

import java.nio.file.Path;

/** Source-tree operator entry point. It is never invoked by plugin startup. */
public final class LegacyQuarantineMigrationCli {
    private LegacyQuarantineMigrationCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) usage();
        String action = args[0];
        Path source = Path.of(args[1]);
        var snapshot = LegacyQuarantineImporter.preflight(source);
        printSnapshot(snapshot);
        if ("preflight".equals(action)) {
            if (args.length != 2) usage();
            return;
        }
        if (!"dry-run".equals(action) && !"apply".equals(action)) usage();
        if (args.length != ("apply".equals(action) ? 3 : 2)) usage();

        String url = requiredEnvironment("INFINITYGEAR_MIGRATION_JDBC_URL");
        String username = requiredEnvironment("INFINITYGEAR_MIGRATION_JDBC_USER");
        String password = requiredEnvironment("INFINITYGEAR_MIGRATION_JDBC_PASSWORD");
        var importer = new LegacyQuarantineImporter(new DriverDataSource(url, username, password));
        if ("apply".equals(action)) {
            if (!"YES".equals(System.getenv("INFINITYGEAR_MIGRATION_APPROVED"))) {
                throw new IllegalStateException("Apply requires INFINITYGEAR_MIGRATION_APPROVED=YES");
            }
            if (!snapshot.sourceId().equals(args[2])) {
                throw new IllegalStateException("Approved source ID does not match the current offline backup");
            }
            Path pluginDirectory = Path.of(requiredEnvironment("INFINITYGEAR_LEGACY_PLUGIN_DIRECTORY"))
                    .toAbsolutePath().normalize();
            if (snapshot.database().startsWith(pluginDirectory)) {
                throw new IllegalStateException("Apply must read the operator backup outside the plugin data directory");
            }
        }
        printReport(importer.importSnapshot(snapshot, "apply".equals(action)));
    }

    private static void printSnapshot(LegacyQuarantineImporter.Snapshot snapshot) {
        System.out.println("source-id=" + snapshot.sourceId());
        System.out.println("database=" + snapshot.database());
        System.out.println("db-sha256=" + snapshot.db().sha256());
        System.out.println("wal-sha256=" + snapshot.wal().map(LegacyQuarantineImporter.FileEvidence::sha256).orElse("ABSENT"));
        System.out.println("shm-sha256=" + snapshot.shm().map(LegacyQuarantineImporter.FileEvidence::sha256).orElse("ABSENT"));
        System.out.println("schema-version=" + snapshot.schemaVersion());
        System.out.println("identities=" + snapshot.identities().size());
        System.out.println("sightings=" + snapshot.sightings().size());
        snapshot.identities().stream().collect(java.util.stream.Collectors.groupingBy(
                        row -> row.status() + "|" + row.trackedKind() + "|" + row.trackedType(),
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((key, count) -> System.out.println("identity-group=" + key + "|" + count));
        System.out.println("structural-issues=" + snapshot.structuralIssues().size());
        snapshot.structuralIssues().forEach(issue -> System.out.println("issue=" + issue));
    }

    private static void printReport(LegacyQuarantineImporter.Report report) {
        System.out.println("state=" + report.state());
        System.out.println("dry-run=" + report.dryRun());
        System.out.println("inserted=" + report.inserted());
        System.out.println("matched=" + report.matched());
        System.out.println("conflicts=" + report.conflicts());
        System.out.println("malformed=" + report.malformed());
        report.details().forEach(detail -> System.out.println("detail=" + detail));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required environment: " + name);
        return value;
    }

    private static void usage() {
        throw new IllegalArgumentException("Usage: preflight <absolute-backup-db> | dry-run <absolute-backup-db>"
                + " | apply <absolute-backup-db> <approved-source-id>");
    }
}
