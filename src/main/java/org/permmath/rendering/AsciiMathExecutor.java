package org.permmath.rendering;

import static java.lang.ClassLoader.getSystemResource;
import static java.nio.file.Files.readString;

import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import javax.validation.constraints.NotNull;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.w3c.dom.Document;

import jakarta.annotation.PostConstruct;

@Validated
@Service
public class AsciiMathExecutor {

    private String asciiMathJs;

    @PostConstruct
    void init() throws URISyntaxException, IOException {
        asciiMathJs = readString(
                Paths.get(
                        getSystemResource("ASCIIMathML.js")
                                .toURI()));
    }

    public void processAsciiMath(@NotNull Document doc) throws IOException {
        try (Context context = Context.enter()) {
            var scope = context.initStandardObjects();
            var wrappedDocument = Context.javaToJS(doc, scope);
            ScriptableObject.putProperty(scope, "document", wrappedDocument);
            context.evaluateReader(scope, new StringReader(asciiMathJs), "ASCIIMathML", 1, null);
            context.evaluateString(scope, "translate", "TranslateCommand", 1, null);
        }
    }
}
