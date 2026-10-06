package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import static org.permmath.contract.ComplexityPolicy.Limit.*;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permmath.contract.ComplexityPolicy;

import tools.jackson.databind.json.JsonMapper;

class ComplexityPolicyTests {
    @Test
    void sharesValidatedOverridesWithJavascriptWorkersAndCacheIdentity() throws Exception {
        var properties = new Properties();
        properties.setProperty("permmath.complexity.render-dimension", "12000");
        var policy = ComplexityPolicy.load(properties, Map.of("PERMMATH_COMPLEXITY_RENDER_DIMENSION", "10000",
                "PERMMATH_COMPLEXITY_HTML_ELEMENTS", "15000"));
        assertEquals(12000, policy.get(RENDER_DIMENSION));
        assertEquals(15000, policy.get(HTML_ELEMENTS));
        assertEquals(12000, JsonMapper.builder().build().readTree(policy.json()).get("RENDER_DIMENSION").asInt());
        var child = ComplexityPolicy.load(new Properties(), policy.workerEnvironment());
        assertEquals(policy.json(), child.json());
        assertEquals(policy.fingerprint(), child.fingerprint());
        assertNotEquals(ComplexityPolicy.load(new Properties(), Map.of()).fingerprint(), child.fingerprint());
    }

    @Test
    void rejectsInvalidAndTransportExceedingConfiguration() {
        assertAll(List.of("0", "-1", "NaN", "2147483648").stream()
            .<Executable>map(value -> () -> {
                assertThrows(IllegalArgumentException.class, () -> ComplexityPolicy.load(new Properties(),
                        Map.of("PERMMATH_COMPLEXITY_HTML_DEPTH", value)));
            }));
        assertThrows(IllegalArgumentException.class, () -> ComplexityPolicy.load(new Properties(),
                Map.of("PERMMATH_COMPLEXITY_SVG_BYTES", "16777217")));
    }
}
