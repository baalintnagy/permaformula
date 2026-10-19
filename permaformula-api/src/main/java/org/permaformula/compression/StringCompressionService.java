package org.permaformula.compression;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.permaformula.validation.InputLimits;
import org.permaformula.validation.QuickValidator;
import org.permaformula.validation.ValidEncoded;
import org.permaformula.validation.ValidJson;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
public class StringCompressionService {

    public String compress(@ValidJson String str) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes(UTF_8));
        }
        QuickValidator.validate(
            !(out.size() > InputLimits.MAX_COMPRESSED_BYTES),
            () -> QuickValidator.violation("Compressed payload is too large"));
        return Base64.getEncoder()
            .encodeToString(out.toByteArray());
    }

    public String decompress(@ValidEncoded String str) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(str);
        QuickValidator.validate(
            !(compressed.length > InputLimits.MAX_COMPRESSED_BYTES),
            () -> QuickValidator.violation("Compressed payload is too large"));
        try (ByteArrayInputStream input = new ByteArrayInputStream(compressed);
            GZIPInputStream gzip = new GZIPInputStream(input)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = gzip.read(buffer)) != -1) {
                QuickValidator.validate(
                    !(count > InputLimits.MAX_JSON_BYTES - out.size()),
                    () -> QuickValidator.violation("Decompressed payload is too large"));
                out.write(buffer, 0, count);
            }
            return out.toString(UTF_8);
        }
    }

}
