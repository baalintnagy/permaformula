package org.permmath.compression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StringCompressionServiceTests {

    private final StringCompressionService service = new StringCompressionService();

    @ParameterizedTest
    @ValueSource(strings = { "", "x + y = 50%", "α ∑ ∞ ő 漢字 🧮", "line 1\nline 2\twith whitespace" })
    void roundTripPreservesTextExactly(String text) throws IOException {
        assertEquals(text, service.decompress(service.compress(text)));
    }

    @Test
    void roundTripPreservesLargePayload() throws IOException {
        String text = "<p>`sum_(i=1)^n i`</p> α + β 50%\n".repeat(10000);
        assertEquals(text, service.decompress(service.compress(text)));
    }

    @Test
    void invalidBase64IsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.decompress("%%%"));
    }

    @Test
    void nonGzipPayloadIsRejected() {
        String payload = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4 });
        assertThrows(IOException.class, () -> service.decompress(payload));
    }
}
