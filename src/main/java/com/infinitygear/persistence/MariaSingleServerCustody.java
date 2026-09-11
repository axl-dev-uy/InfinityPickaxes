package com.infinitygear.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable single-backend authority for Task #7's initial topology.
 *
 * <p>This is intentionally not a lease or transfer protocol. The first explicitly
 * configured server ID owns the deployment scope permanently; another server ID
 * sharing the database cannot acquire or mutate an identity.</p>
 */
public final class MariaSingleServerCustody {
    private static final String SCOPE = "tracked-items";
    private final DataSource source;
    private final String serverId;
    private volatile long epoch = -1;

    public MariaSingleServerCustody(DataSource source, String serverId) {
        this.source = Objects.requireNonNull(source, "source");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        if (!serverId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid stable server ID");
        }
    }

    public String serverId() { return serverId; }
    public long epoch() { return epoch; }

    /** Restart-safe migration 12 plus a new monotonically fenced server-process session. */
    public void migrateAndClaimDeployment() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_deployment_custody ("
                    + "authority_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "topology VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "epoch BIGINT NOT NULL, state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_identity_custody ("
                    + "tracked_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "tracked_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "epoch BIGINT NOT NULL, state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "last_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                    + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            statement.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (12)");
        }
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var insert = connection.prepareStatement("INSERT IGNORE INTO infinitygear_deployment_custody"
                        + "(authority_scope,server_id,topology,epoch,state) VALUES (?,?, 'SINGLE_SERVER',0,'ACTIVE')")) {
                    insert.setString(1, SCOPE); insert.setString(2, serverId); insert.executeUpdate();
                }
                try (var select = connection.prepareStatement("SELECT server_id,topology,state FROM infinitygear_deployment_custody"
                        + " WHERE authority_scope=? FOR UPDATE")) {
                    select.setString(1, SCOPE);
                    try (var row = select.executeQuery()) {
                        if (!row.next() || !serverId.equals(row.getString(1))
                                || !"SINGLE_SERVER".equals(row.getString(2))
                                || !"ACTIVE".equals(row.getString(3))) {
                            throw new IllegalStateException("Configured server does not own the single-server custody scope");
                        }
                    }
                }
                try (var advance = connection.prepareStatement("UPDATE infinitygear_deployment_custody"
                        + " SET epoch=epoch+1 WHERE authority_scope=? AND server_id=? AND topology='SINGLE_SERVER'"
                        + " AND state='ACTIVE'")) {
                    advance.setString(1, SCOPE); advance.setString(2, serverId);
                    if (advance.executeUpdate() != 1) throw new IllegalStateException("Could not advance server fencing epoch");
                }
                try (var current = connection.prepareStatement("SELECT epoch FROM infinitygear_deployment_custody"
                        + " WHERE authority_scope=? AND server_id=?")) {
                    current.setString(1, SCOPE); current.setString(2, serverId);
                    try (var row = current.executeQuery()) {
                        if (!row.next() || row.getLong(1) < 1) throw new IllegalStateException("Missing server fencing epoch");
                        epoch = row.getLong(1);
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                epoch = -1;
                connection.rollback(); throw failure;
            }
        }
    }

    /** Claim or verify an identity for this server. No reassignment or transfer is possible. */
    public void claim(UUID trackedId, String trackedKind, UUID operationId) throws Exception {
        Objects.requireNonNull(trackedId, "trackedId");
        Objects.requireNonNull(operationId, "operationId");
        if (trackedKind == null || !trackedKind.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("Invalid tracked kind");
        }
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                requireDeployment(connection);
                try (var insert = connection.prepareStatement("INSERT IGNORE INTO infinitygear_identity_custody"
                        + "(tracked_id,tracked_kind,server_id,epoch,state,last_operation_id) VALUES (?,?,?,?, 'ACTIVE',?)")) {
                    insert.setString(1, trackedId.toString()); insert.setString(2, trackedKind);
                    insert.setString(3, serverId); insert.setLong(4, epoch);
                    insert.setString(5, operationId.toString()); insert.executeUpdate();
                }
                try (var select = connection.prepareStatement("SELECT tracked_kind,server_id,epoch,state FROM infinitygear_identity_custody"
                        + " WHERE tracked_id=? FOR UPDATE")) {
                    select.setString(1, trackedId.toString());
                    try (var row = select.executeQuery()) {
                        if (!row.next() || !trackedKind.equals(row.getString(1))
                                || !serverId.equals(row.getString(2))
                                || !"ACTIVE".equals(row.getString(4))) {
                            throw new IllegalStateException("Tracked identity is not mutable on this server");
                        }
                    }
                }
                try (var update = connection.prepareStatement("UPDATE infinitygear_identity_custody SET last_operation_id=?"
                        + ",epoch=? WHERE tracked_id=? AND server_id=? AND state='ACTIVE'")) {
                    update.setString(1, operationId.toString()); update.setLong(2, epoch);
                    update.setString(3, trackedId.toString()); update.setString(4, serverId);
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Custody changed during claim");
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback(); throw failure;
            }
        }
    }

    /** Verify current-session mutation authority without changing the identity row. */
    public void verify(UUID trackedId, String trackedKind) throws Exception {
        Objects.requireNonNull(trackedId, "trackedId");
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireDeployment(connection);
                try (var select = connection.prepareStatement("SELECT tracked_kind,server_id,epoch,state"
                        + " FROM infinitygear_identity_custody WHERE tracked_id=? FOR UPDATE")) {
                    select.setString(1, trackedId.toString());
                    try (var row = select.executeQuery()) {
                        if (!row.next() || !trackedKind.equals(row.getString(1))
                                || !serverId.equals(row.getString(2)) || row.getLong(3) != epoch
                                || !"ACTIVE".equals(row.getString(4))) {
                            throw new IllegalStateException("Tracked identity custody is stale or unavailable");
                        }
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback(); throw failure;
            }
        }
    }

    private void requireDeployment(Connection connection) throws Exception {
        try (var select = connection.prepareStatement("SELECT server_id,topology,epoch,state FROM infinitygear_deployment_custody"
                + " WHERE authority_scope=? FOR UPDATE")) {
            select.setString(1, SCOPE);
            try (var row = select.executeQuery()) {
                if (epoch < 1 || !row.next() || !serverId.equals(row.getString(1))
                        || !"SINGLE_SERVER".equals(row.getString(2)) || row.getLong(3) != epoch
                        || !"ACTIVE".equals(row.getString(4))) {
                    throw new IllegalStateException("Single-server custody authority is unavailable");
                }
            }
        }
    }
}
