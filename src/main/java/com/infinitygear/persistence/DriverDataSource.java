package com.infinitygear.persistence;

import javax.sql.DataSource;
import java.sql.*;
import java.io.PrintWriter;
import java.util.logging.Logger;

/** Driver is loaded by Paper libraries, never linked into the shaded artifact. */
public final class DriverDataSource implements DataSource {
    private final String url, username, password;
    public DriverDataSource(String url, String username, String password) {
        if (url == null || !url.startsWith("jdbc:mariadb://")) throw new IllegalArgumentException("MariaDB JDBC URL required");
        this.url = url; this.username = username; this.password = password;
    }
    @Override public Connection getConnection() throws SQLException { return getConnection(username, password); }
    @Override public Connection getConnection(String user, String secret) throws SQLException {
        try { Class.forName("org.mariadb.jdbc.Driver"); }
        catch (ClassNotFoundException missing) { throw new SQLException("Paper MariaDB runtime library unavailable", missing); }
        return DriverManager.getConnection(url, user, secret);
    }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
    @Override public int getLoginTimeout() { return 0; }
    @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException("Set connectTimeout in JDBC URL"); }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.infinitygear.persistence"); }
    @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        if (!isWrapperFor(type)) throw new SQLException("Not a wrapper"); return type.cast(this);
    }
}
