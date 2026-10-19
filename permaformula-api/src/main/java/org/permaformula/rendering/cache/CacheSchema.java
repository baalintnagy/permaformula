package org.permaformula.rendering.cache;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Apply pending Liquibase changesets without resetting cache data or migration history. */
public final class CacheSchema {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private CacheSchema() {
    }

    @SuppressWarnings("try")
    public static void migrate(DataSource source) throws SQLException, LiquibaseException {
        try (Connection connection = source.getConnection();
            ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
            Liquibase liquibase = new Liquibase(CHANGELOG, resources,
                new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        } catch (SQLException | LiquibaseException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new LiquibaseException("Interrupted while closing cache schema resources",
                failure);
        } catch (Exception failure) {
            throw new LiquibaseException("Cache schema migration failed", failure);
        }
    }
}
