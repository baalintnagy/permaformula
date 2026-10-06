package org.permmath.compression;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.permmath.validation.InputLimits;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Validated
@Service
public class StringCompressionService {

    private static final Logger log = LoggerFactory.getLogger(StringCompressionService.class);

    public String compress(String str) throws IOException {
        InputLimits.text(str, InputLimits.MAX_JSON_BYTES, "Uncompressed payload");
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes(UTF_8));
        }
        if (out.size() > InputLimits.MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Compressed payload is too large");
        }
        return Base64.getEncoder()
                .encodeToString(out.toByteArray());
    }

    public String decompress(String str) throws IOException {
        if (str.length() > InputLimits.MAX_ENCODED_CHARS) {
            throw new IllegalArgumentException("Encoded payload is too large");
        }
        var compressed = Base64.getDecoder().decode(str);
        if (compressed.length > InputLimits.MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Compressed payload is too large");
        }
        try (var input = new ByteArrayInputStream(compressed);
             var gzip = new GZIPInputStream(input)) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = gzip.read(buffer)) != -1) {
                if (count > InputLimits.MAX_JSON_BYTES - out.size()) {
                    throw new IllegalArgumentException("Decompressed payload is too large");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(UTF_8);
        }
    }

    static void main() {
        try {
            var s = new StringCompressionService();
            String c = s.compress(
                    "aabaacaadaahaaaakattajjaahahavvaavabnacacacabbacvakattajjaahahavvaavabnacacacaaabaacakattajjaahahavvaavabnacaadaahakattajjaahahavvaavabnacaaaakattajjaahahavvaavabnacacacabbacvacacaaabaacaadaahaaaakattajjaahahavvaavabnacacacabbacvacacaaabaacaadaahaaaakattajjaahahavvaavabnacacacabbaakattajjaahahavvaavabnaccvacacaaabaacaadaahaaaakattajjaahahavvaavabnacacacabbacvacaca");
            log.info("Compressed: {}", c);
            String d = s.decompress(c);
            log.info("Decompressed: {}", d);
        } catch (Exception e) {
            log.error("Error: {}", e);
        }
    }

}
