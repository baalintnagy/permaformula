package org.permmath.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.permmath.contract.ComplexityPolicy;

/** Browser-free AsciiMath conversion using MathJax's lightweight DOM adapter. */
public class AsciiMathRenderer implements AutoCloseable {
    private final ComplexityPolicy policy;
    private Context context;
    private Value render;
    private Engine engine;
    private static Source source;

    private static synchronized Source source() throws IOException {
        if (source == null) {
            try (var input = AsciiMathRenderer.class.getResourceAsStream("/svg/renderer.js")) {
                source = Source.newBuilder("js", new String(Objects.requireNonNull(input).readAllBytes(),
                        StandardCharsets.UTF_8), "permmath-renderer.js").cached(true).build();
            }
        }
        return source;
    }

    /** Force engine/bundle initialization and one typesetting pass before announcing readiness. */
    public synchronized void warmUp() throws IOException {
        render("x");
    }

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
            engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
            Context created = null;
            try {
                created = Context.newBuilder("js").engine(engine).allowHostAccess(HostAccess.NONE)
                        .allowHostClassLookup(_ -> false).build();
                created.eval("js", "globalThis.PermMathComplexity = " + policy.json() + ";");
                created.eval(source());
                render = created.eval("js", "PermMathSvg.render");
                context = created;
            } catch (RuntimeException | IOException e) {
                if (created != null) created.close();
                engine.close();
                engine = null;
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
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
