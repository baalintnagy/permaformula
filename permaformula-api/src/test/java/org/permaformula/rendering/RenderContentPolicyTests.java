package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.validation.ConstraintViolationException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permaformula.url.ImageDescriptor;

class RenderContentPolicyTests {
    @Test
    void preservesExternalCascadeVariablesResponsiveStylesAndInlineOverrides() throws Exception {
        var descriptor = new ImageDescriptor("<p class='title'>hello</p>", 320,
            List.of("https://example.com/first.css", "https://example.com/second.css"),
            "p {color:green}");
        var html = RenderContentPolicy.document("<html><head></head><body></body></html>",
            descriptor,
            List.of(
                """
                :root {--accent:255,0,0;} @media (min-width:200px) {.title {color:rgba(var(--\
                accent),1)}}\
                """
                ,
                "p {color:blue}"));
        var styles = Jsoup.parse(html)
            .select("style");
        assertEquals(3, styles.size());
        assertTrue(styles.get(0)
            .data()
            .contains("var(--accent)"));
        assertTrue(styles.get(0)
            .data()
            .contains("@media"));
        assertTrue(styles.get(1)
            .data()
            .contains("color:blue"));
        assertTrue(styles.get(2)
            .data()
            .contains("color:green"));
        assertFalse(html.contains("evil.example"));
    }

    @Test
    void rejectsInvalidCssWithoutDroppingDeclarations() {
        for (String css : List.of(
            "div {--evil:url(https://evil.example/a)}",
            "div {color:expression(alert(1))}",
            "div {background:image-set('https://evil.example/b' 1x)}",
            "div {list-style-image:u\\72l(https://evil.example/c)}",
            "div {width:calc(1px + url(https://evil.example/d))}",
            "@font-face {font-family:evil;src:url(https://evil.example/e)}",
            "@import url(http://localhost/x); body {color:red}",
            "body {color:red}</style><script>alert(1)</script>")) {
            assertThrows(ConstraintViolationException.class, () -> RenderContentPolicy.css(css),
                css);
        }
    }

    @Test
    void rejectsExecutableAndNetworkMarkupInsteadOfSanitizing() {
        for (String html : List.of(
            "<p>hello</p><script>alert(1)</script>",
            "<img src='http://127.0.0.1' onerror='alert(1)'>",
            "<iframe srcdoc='<script>alert(1)</script>'></iframe>",
            "<svg onload='alert(1)'></svg>",
            "<form action='http://localhost'><input></form>",
            "<a href='javascript:alert(1)'>link</a>",
            "<p onclick='alert(1)'>hello</p>",
            "<p style='background:url(https://evil.example/x)'>hello</p>")) {
            assertThrows(ConstraintViolationException.class, () -> RenderContentPolicy.document(
                "<html><head></head><body></body></html>",
                new ImageDescriptor(html, 320, null, null), List.of()),
                html);
        }
    }

    @Test
    void cssLiteralCannotEscapeStyleBoundary() {
        var boundary = RenderContentPolicy.css(
            "p::before {content:'</style><script>alert(1)</script>'}");
        assertFalse(boundary.contains("<"));
    }

    @Test
    void networkPolicyOnlyPermitsCurrentDocumentAndBundledScripts() {
        var url = "http://127.0.0.1:1234/document/abc";
        assertTrue(ChromiumRenderer.allowedRequest(url, url, "GET", "document"));
        assertTrue(ChromiumRenderer.allowedRequest(url,
            "http://127.0.0.1:1234/mathjax/input/asciimath.js", "GET", "script"));
        assertAll(List.of("http://169.254.169.254/latest/meta-data/", "http://localhost:1234/",
            "http://127.0.0.1:8086/", "https://example.com/", "file:///etc/passwd",
            "http://127.0.0.1:1234/document/other",
            "http://127.0.0.1:1234/mathjax/../document/abc")
            .stream()
            .<Executable>map(target -> () -> {
                assertFalse(ChromiumRenderer.allowedRequest(url, target, "GET", "document"),
                    target);
                assertFalse(ChromiumRenderer.allowedRequest(url, target, "GET", "script"), target);
            }));
        assertFalse(ChromiumRenderer.allowedRequest(url, url, "POST", "document"));
        assertFalse(ChromiumRenderer.allowedRequest(url, url, "GET", "fetch"));
    }
}
