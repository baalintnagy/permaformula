package org.permaformula.rendering;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.contract.RenderProtocol;

/** Validate JPEG metadata without allocating a decoded pixel buffer. */
public final class JpegOutputPolicy {
    public static final String PREFIX = "jpeg-v1:";

    public static String encode(byte[] bytes) throws IOException {
        validate(bytes);
        return PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] decode(String stored) throws IOException {
        try {
            if (!stored.startsWith(PREFIX)
                || stored.length() > 4L * RenderProtocol.MAX_CONTENT_BYTES / 3 + 16) {
                throw new IOException("Invalid cached JPEG");
            }
            byte[] bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            validate(bytes);
            return bytes;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid cached JPEG", invalid);
        }
    }

    private static void validate(byte[] bytes) throws IOException {
        if (bytes.length < 4
            || bytes.length > RenderProtocol.MAX_CONTENT_BYTES
            || (bytes[0] & 255) != 255
            || (bytes[1] & 255) != 216
            || (bytes[bytes.length - 2] & 255) != 255
            || (bytes[bytes.length - 1] & 255) != 217) {
            throw new IOException("Invalid JPEG output");
        }
        try (ImageInputStream input = ImageIO
            .createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                ComplexityPolicy policy = ComplexityPolicy.CURRENT;
                if (width < 1
                    || height < 1
                    || width > policy.get(ComplexityPolicy.Limit.RENDER_DIMENSION)
                    || height > policy.get(ComplexityPolicy.Limit.RENDER_DIMENSION)
                    || (long) width * height > policy.get(ComplexityPolicy.Limit.RENDER_AREA)) {
                    throw new IOException("JPEG exceeds geometry limits");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private JpegOutputPolicy() {
    }
}
