package com.memora.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Stamps each borrowed connection with the current {@link TenantContext} so PostgreSQL RLS policies
 * ({@code app.tenant_id}, {@code app.rls_bypass}) see the right scope. Pooled connections are
 * re-stamped on every checkout, so a scope can never leak between requests.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String STAMP_SQL =
            "SELECT set_config('app.tenant_id', ?, false), set_config('app.rls_bypass', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamp(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamp(super.getConnection(username, password));
    }

    private Connection stamp(Connection connection) throws SQLException {
        UUID tenantId = TenantContext.tenantId();
        boolean system = TenantContext.isSystem();
        try (PreparedStatement ps = connection.prepareStatement(STAMP_SQL)) {
            ps.setString(1, tenantId == null ? "" : tenantId.toString());
            ps.setString(2, system ? "on" : "off");
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
