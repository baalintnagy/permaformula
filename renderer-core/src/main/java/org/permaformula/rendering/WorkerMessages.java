package org.permaformula.rendering;

import java.io.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

/** Standard length-delimited protobuf, with a bound checked before allocation. */
final class WorkerMessages {
    static <T extends MessageLite> T read(InputStream input, Parser<T> parser) throws IOException {
        int first = input.read();
        if (first == -1) {
            throw new EOFException("Worker pipe closed");
        }
        int size = CodedInputStream.readRawVarint32(first, input);
        if (size < 0
            || size > RenderLimits.MAX_MESSAGE_BYTES) {
            throw new IOException("Worker message exceeds limit");
        }
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) {
            throw new EOFException("Truncated worker message");
        }
        return parser.parseFrom(bytes);
    }

    static void write(OutputStream output, MessageLite message) throws IOException {
        if (message.getSerializedSize() > RenderLimits.MAX_MESSAGE_BYTES) {
            throw new IOException("Worker message exceeds limit");
        }
        message.writeDelimitedTo(output);
        output.flush();
    }

    private WorkerMessages() {
    }
}
