package org.permmath.compression;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.validation.constraints.NotBlank;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
public class StringCompressionService {

    public String compress(@NotBlank String input) throws IOException {
        ByteArrayOutputStream obj = new ByteArrayOutputStream();

        GZIPOutputStream gzip = new GZIPOutputStream(obj);
        gzip.write(input.getBytes(UTF_8));
        gzip.flush();
        gzip.close();

        return new String(
            getEncoder()
                .encode(
                    obj.toByteArray()));
    }

    public String decompress(@NotBlank String input) throws IOException {
        byte[] compressed = getDecoder().decode(input.getBytes());
        GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
        BufferedReader br = new BufferedReader(new InputStreamReader(gis, UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        gis.close();
        return sb.toString();
    }

}
