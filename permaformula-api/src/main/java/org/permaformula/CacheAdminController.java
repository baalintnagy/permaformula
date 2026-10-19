package org.permaformula;

import java.sql.SQLException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.permaformula.rendering.cache.CaffeineImageStore;
import org.permaformula.validation.CacheId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityScheme(name = "adminJwt", type = SecuritySchemeType.HTTP, scheme = "bearer",
    bearerFormat = "JWT")
@SecurityRequirement(name = "adminJwt")
@RequiredArgsConstructor
public class CacheAdminController {
    private final CaffeineImageStore store;

    @Operation(summary = "Delete a cached image",
        description = """
            Requires an admin JWT obtained from /admin/login. Idempotent; later rendering can \
            recreate the entry.\
            """)
    @DeleteMapping("/admin/cache-item")
    public ResponseEntity<Void> delete(@CacheId @RequestParam String id) throws SQLException {
        store.delete(id);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
