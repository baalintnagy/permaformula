package org.permaformula.rendering.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.exception.LiquibaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistentCacheProperties.class)
public class PersistentCacheConfiguration {
    @Bean(destroyMethod = "close")
    JdbcImageCache persistentImageCache(PersistentCacheProperties properties,
        ImageCacheProperties cache,
        @Value("${permaformula.image-cache.persistence.pressure-file:}") String pressureFile)
        throws SQLException, IOException, LiquibaseException {
        if (properties.backend() == PersistentCacheProperties.Backend.MEMORY) {
            return null;
        }
        boolean sqlite = properties.backend() == PersistentCacheProperties.Backend.SQLITE;
        HikariConfig config = new HikariConfig();
        if (sqlite) {
            Path path = properties.sqlitePath()
                .toAbsolutePath()
                .normalize();
            Files.createDirectories(path.getParent());
            config.setJdbcUrl("jdbc:sqlite:%s".formatted(path));
            // SQLite has one writer; serialize DB work without busy retry storms.
            config.setMaximumPoolSize(1);
            config.addDataSourceProperty("busy_timeout", "1000");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "FULL");
        } else {
            config.setJdbcUrl(properties.postgresUrl());
            config.setUsername(properties.username());
            config.setPassword(properties.password());
            config.setMaximumPoolSize(properties.maximumPoolSize());
            config.addDataSourceProperty("connectTimeout", "2");
            config.addDataSourceProperty("socketTimeout", "3");
            config.addDataSourceProperty("options",
                "-c statement_timeout=2000 -c lock_timeout=1000");
        }
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(1000);
        config.setPoolName("image-cache");
        HikariDataSource source = new HikariDataSource(config);
        try {
            CacheSchema.migrate(source);
            return new JdbcImageCache(source, sqlite, cache.expireAfterAccess(),
                properties.maximumSize()
                    .toBytes(),
                properties.cleanupInterval(),
                source, new CacheDiskPressure(pressureFile,
                    sqlite ? properties.sqlitePath()
                        .toAbsolutePath()
                        .getParent() : null),
                properties.maximumEntries());
        } catch (SQLException | LiquibaseException | RuntimeException failure) {
            source.close();
            throw failure;
        }
    }
}
