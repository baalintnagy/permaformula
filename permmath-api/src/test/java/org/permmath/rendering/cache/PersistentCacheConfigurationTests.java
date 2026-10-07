package org.permmath.rendering.cache;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PersistentCacheConfigurationTests {
    @TempDir Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersistentCacheConfiguration.class, CaffeineImageStore.class, CacheProperties.class);

    @Test void localDefaultCreatesAnEmbeddedSqliteCacheWithoutDatabaseCredentials() {
        Path file = directory.resolve("nested/cache.sqlite");
        runner.withPropertyValues("permmath.image-cache.persistence.sqlite-path=" + file).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JdbcImageCache.class);
            assertThat(context.getBean(PersistentCacheProperties.class).backend())
                    .isEqualTo(PersistentCacheProperties.Backend.SQLITE);
            assertThat(file).exists();
            assertThat(context.getBean(GeneratedImageStore.class).getOrGenerate("a".repeat(64),
                    () -> "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
                    .contains("<svg");
        });
    }

    @Test void memoryModeNeedsNoDatabaseOrWritablePath() {
        runner.withPropertyValues("permmath.image-cache.persistence.backend=memory").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanProvider(JdbcImageCache.class).getIfAvailable()).isNull();
            assertThat(context.getBean(GeneratedImageStore.class).getOrGenerate("x", () -> "memory"))
                    .isEqualTo("memory");
        });
    }

    @Test void postgresRequiresAnExplicitApplicationPassword() {
        runner.withPropertyValues("permmath.image-cache.persistence.backend=postgres")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void invalidBackendAndNonPositiveLimitsFailStartup() {
        for (String property : new String[] {"backend=unknown", "maximum-size=0B", "cleanup-interval=0s",
                "maximum-pool-size=0"}) {
            runner.withPropertyValues("permmath.image-cache.persistence." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImageCacheProperties.class)
    static class CacheProperties { }
}
