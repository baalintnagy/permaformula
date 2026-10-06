package org.permmath.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.permmath.contract.ComplexityPolicy;

/** Browser-free ASCIIMath conversion using MathJax's lightweight DOM adapter. */
public class AsciiMathRenderer implements AutoCloseable {
    private final ComplexityPolicy policy;
    private Context context;
    private Value render;

    public AsciiMathRenderer() {
        this(ComplexityPolicy.CURRENT);
    }
    AsciiMathRenderer(ComplexityPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    public synchronized String render(String formula) throws IOException {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("formula must not be blank");
        }
        policy.checkMathInput(formula);
        if (context == null) {
            var created = Context.newBuilder("js").allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(_ -> false)
                    // Stock JDK operation intentionally uses GraalJS's interpreter.
                    .option("engine.WarnInterpreterOnly", "false").build();
            try (var input = getClass().getResourceAsStream("/svg/renderer.js")) {
                created.eval("js", "globalThis.PermMathComplexity = " + policy.json() + ";");
                created.eval("js", new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
                render = created.eval("js", "PermMathSvg.render");
                context = created;
            } catch (RuntimeException | IOException e) {
                created.close();
                throw e;
            }
        }
        // Pass input as a value, never interpolate it into executable JavaScript.
        try {
            return render.execute(formula).asString();
        } catch (PolyglotException invalid) {
            if (invalid.isGuestException()) {
                throw new IllegalArgumentException("Invalid math expression");
            }
            throw invalid;
        }
    }

    @Override
    public synchronized void close() {
        if (context != null) {
            context.close();
            context = null;
            render = null;
        }
    }
}
