package org.permaformula.rendering;

import static org.permaformula.validation.QuickValidator.notNull;
import static org.permaformula.validation.QuickValidator.validate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.permaformula.contract.ComplexityPolicy;

/** Browser-free AsciiMath conversion using MathJax's lightweight DOM adapter. */
public class AsciiMathRenderer implements AutoCloseable {
    private final ComplexityPolicy policy;
    private Context context;
    private Value render;
    private Engine engine;
    private static Source source;

    private static synchronized Source source() throws IOException {
        if (source == null) {
            try (InputStream input = AsciiMathRenderer.class
                .getResourceAsStream("/svg/renderer.js")) {
                source = Source.newBuilder("js", new String(notNull(input)
                    .readAllBytes(),
                    StandardCharsets.UTF_8), "permaformula-renderer.js")
                    .cached(true)
                    .build();
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
        this.policy = notNull(policy);
    }

    public synchronized String render(String formula) throws IOException {
        if (context == null) {
            engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
            Context created = null;
            try {
                created = Context.newBuilder("js")
                    .engine(engine)
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(_ -> false)
                    .build();
                created.eval("js", "globalThis.PermaFormulaComplexity = %s;".formatted(
                    policy.json()));
                created.eval(source());
                render = created.eval("js", "PermaFormulaSvg.render");
                context = created;
            } catch (RuntimeException | IOException e) {
                if (created != null) {
                    created.close();
                }
                engine.close();
                engine = null;
                throw e;
            }
        }
        // Pass input as a value, never interpolate it into executable JavaScript.
        try {
            return render.execute(formula)
                .asString();
        } catch (PolyglotException invalid) {
            validate(
                !(invalid.isGuestException()),
                () -> new IllegalArgumentException("Invalid math expression"));
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
