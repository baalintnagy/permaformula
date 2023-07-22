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

@Validated
@Service
public class StringCompressionService {

    public String compress(String str) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(str.getBytes(UTF_8));
        gzip.close();
        return Base64.getEncoder()
                .encodeToString(out.toByteArray());
    }

    public String decompress(String str) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(str);
        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        GZIPInputStream gis = new GZIPInputStream(bis);
        var result = new String(gis.readAllBytes(), UTF_8);
        gis.close();
        bis.close();
        return result;
    }

    public static void main(String[] args) {
        try {
            var s = new StringCompressionService();
            String c = s.compress(
                    "aabaacaadaahaaaakattajjaahahavvaavabnacacacabbacvakattajjaahahavvaavabnacacacaaabaacakattajjaahahavvaavabnacaadaahakattajjaahahavvaavabnacaaaakattajjaahahavvaavabnacacacabbacvacacaaabaacaadaahaaaakattajjaahahavvaavabnacacacabbacvacacaaabaacaadaahaaaakattajjaahahavvaavabnacacacabbaakattajjaahahavvaavabnaccvacacaaabaacaadaahaaaakattajjaahahavvaavabnacacacabbacvacaca");
            System.out.println(c);
            String d = s.decompress(c);
            System.out.println(d);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
