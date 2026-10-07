package org.permmath.rendering.cache;

import java.sql.SQLException;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Apply versioned YAML migrations before the persistent cache is used. */
public final class CacheSchema {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private CacheSchema() { }

    public static void migrate(DataSource source) throws SQLException, LiquibaseException {
        try (var connection = source.getConnection(); var resources = new ClassLoaderResourceAccessor();
                var liquibase = new Liquibase(CHANGELOG, resources, new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        } catch (SQLException | LiquibaseException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new LiquibaseException("Cache schema migration failed", failure);
        }
    }
}
