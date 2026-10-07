package org.permmath.rendering.cache;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Run only against a disposable database: the contract clears its cache table before each test. */
@EnabledIfSystemProperty(named = "postgres.tests", matches = "true")
class PostgresImageCacheTests extends JdbcImageCacheContract {
    @Override boolean sqlite() { return false; }
    @Override HikariDataSource openDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("postgres.test-url", "jdbc:postgresql://localhost:55432/permmath"));
        config.setUsername("permmath");
        String password = System.getenv("PERMMATH_POSTGRES_TEST_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Missing disposable test DB password");
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }
}
