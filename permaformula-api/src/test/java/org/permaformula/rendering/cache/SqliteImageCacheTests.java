package org.permaformula.rendering.cache;

import java.nio.file.Path;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.io.TempDir;

class SqliteImageCacheTests extends JdbcImageCacheContract {
    @TempDir
    Path directory;

    @Override
    boolean sqlite() {
        return true;
    }

    @Override
    HikariDataSource openDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + directory.resolve("cache.sqlite"));
        config.setMaximumPoolSize(1);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "1000");
        return new HikariDataSource(config);
    }
}
