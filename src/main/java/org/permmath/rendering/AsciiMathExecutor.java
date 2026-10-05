package org.permmath.rendering;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.validation.constraints.NotNull;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.w3c.dom.Document;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@Component
public class AsciiMathExecutor {

    @Autowired
    private JsBeautifier jsBeautifier;

    private static final String CDN_URL = "https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.4/latest.js?config=AM_CHTML";

    // private String asciiMathJs;
    private String cdnJS;

    @PostConstruct
    void init() throws URISyntaxException, IOException, InterruptedException {
        /*
         * asciiMathJs = readString( Paths.get( getSystemResource("cdn.js") .toURI()));
         */
        cdnJS = getCDN();
        if (log.isInfoEnabled()) {
            log.info("CDN: {}", jsBeautifier.beautify(cdnJS));
        }
    }

    private static String getCDN() throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(CDN_URL)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    public void processAsciiMath(@NotNull Document doc) throws IOException {
        try (var context = Context.enter()) {
            var scope = context.initStandardObjects();
            var wrappedDocument = Context.javaToJS(doc, scope);
            ScriptableObject.putProperty(scope, "document", wrappedDocument);
            context.evaluateReader(scope, new StringReader(cdnJS), "ASCIIMathML", 1, null);
        }
    }
}
