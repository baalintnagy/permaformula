package org.permmath.compression;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@Service
public class StringCompressionService {

    public String compress(String str) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes(UTF_8));
        }
        return Base64.getEncoder()
                .encodeToString(out.toByteArray());
    }

    public String decompress(String str) throws IOException {
        var compressed = Base64.getDecoder().decode(str);
        try (var input = new ByteArrayInputStream(compressed);
             var gzip = new GZIPInputStream(input)) {
            return new String(gzip.readAllBytes(), UTF_8);
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
