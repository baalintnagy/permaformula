package org.permmath.rendering;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

import javax.validation.constraints.NotBlank;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@Service
public class AsciiMathExecutor {

    private static final String ASCII_MATH_JS_NAME = "ASCIIMathML.js";

    private ScriptableObject sharedScope;

    @PostConstruct
    void init() throws IOException {
        try (final InputStream parserJavaScriptStream = getClass().getClassLoader().getResourceAsStream(ASCII_MATH_JS_NAME)) {
            final Reader parserJSFileReader = new InputStreamReader(parserJavaScriptStream, UTF_8);

            /* Evaluate the parser script and store away the results */
            final Context context = Context.enter();
            sharedScope = context.initStandardObjects();
            context.evaluateReader(sharedScope, parserJSFileReader, ASCII_MATH_JS_NAME, 1, null);
            Context.exit();
        }
    }

    public String applyAsciiMathJs(@NotBlank String input) throws IOException {
        final Context context = Context.enter();
        try {
            final Scriptable newScope = context.newObject(sharedScope);
            final Object result = ScriptableObject.callMethod(newScope, "translate", new Object[] { input });
            log.info("!!!processed: {}", result);
            return input;
        } finally {
            Context.exit();
        }
    }

}
