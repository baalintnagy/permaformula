package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.permaformula.contract.ComplexityPolicy.Limit.HTML_ELEMENTS;
import static org.permaformula.contract.ComplexityPolicy.Limit.RENDER_DIMENSION;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permaformula.contract.ComplexityPolicy;
import tools.jackson.databind.json.JsonMapper;

class ComplexityPolicyTests {
    @Test
    void sharesValidatedOverridesWithJavascriptWorkersAndCacheIdentity() throws Exception {
        var properties = new Properties();
        properties.setProperty("permaformula.complexity.render-dimension", "12000");
        var policy = ComplexityPolicy.load(properties,
            Map.of("PERMAFORMULA_COMPLEXITY_RENDER_DIMENSION", "10000",
                "PERMAFORMULA_COMPLEXITY_HTML_ELEMENTS", "15000"));
        assertEquals(12000, policy.get(RENDER_DIMENSION));
        assertEquals(15000, policy.get(HTML_ELEMENTS));
        assertEquals(12000,
            JsonMapper.builder()
                .build()
                .readTree(policy.json())
                .get("RENDER_DIMENSION")
                .asInt());
        var child = ComplexityPolicy.load(new Properties(), policy.workerEnvironment());
        assertEquals(policy.json(),
            child.json());
        assertEquals(policy.fingerprint(),
            child.fingerprint());
        assertNotEquals(ComplexityPolicy.load(new Properties(), Map.of())
            .fingerprint(),
            child.fingerprint());
    }

    @Test
    void rejectsInvalidAndTransportExceedingConfiguration() {
        assertAll(List.of("0", "-1", "NaN", "2147483648")
            .stream()
            .<Executable>map(value -> () -> {
                assertThrows(IllegalArgumentException.class,
                    () -> ComplexityPolicy.load(new Properties(),
                        Map.of("PERMAFORMULA_COMPLEXITY_HTML_DEPTH", value)));
            }));
        assertThrows(IllegalArgumentException.class, () -> ComplexityPolicy.load(new Properties(),
            Map.of("PERMAFORMULA_COMPLEXITY_SVG_BYTES", "16777217")));
    }
}
