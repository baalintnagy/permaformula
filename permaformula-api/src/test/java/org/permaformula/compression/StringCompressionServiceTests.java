package org.permaformula.compression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.permaformula.TestServices;
import org.permaformula.validation.InputLimits;

class StringCompressionServiceTests {

    @Test
    void decompressionStopsAtByteLimitIncludingConcatenatedMembers() throws Exception {
        int limit = InputLimits.MAX_JSON_BYTES;
        String boundary = "x".repeat(limit);
        assertEquals(boundary, service.decompress(service.compress(boundary)));
        var bytes = new ByteArrayOutputStream();
        // Two legal members must not each receive a separate expansion allowance.
        bytes.write(Base64.getDecoder().decode(service.compress(boundary)));
        bytes.write(Base64.getDecoder().decode(service.compress("x")));
        assertThrows(ConstraintViolationException.class,
            () -> service.decompress(Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }

    @Test
    void encodedAndCompressedLimitsAreIndependent() {
        int limit = InputLimits.MAX_COMPRESSED_BYTES;
        assertThrows(ConstraintViolationException.class, () -> service.decompress(
            "!".repeat(InputLimits.MAX_ENCODED_CHARS + 1)));
        String oversized = Base64.getEncoder().encodeToString(new byte[limit + 1]);
        assertThrows(ConstraintViolationException.class, () -> service.decompress(oversized));
    }

    @Test
    void compressionCountsUtf8Bytes() {
        assertThrows(ConstraintViolationException.class, () -> service.compress(
            "漢".repeat(InputLimits.MAX_JSON_BYTES / 3 + 1)));
    }

    private final StringCompressionService service = TestServices
        .validated(new StringCompressionService());

    @ParameterizedTest
    @ValueSource(
        strings = { "", "x + y = 50%", "α ∑ ∞ ő 漢字 🧮", "line 1\nline 2\twith whitespace" })
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

    @Test
    void truncatedGzipIsRejected() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(service.compress("valid content"));
        String truncated = Base64.getEncoder()
            .encodeToString(Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> service.decompress(truncated));
    }
}
