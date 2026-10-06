package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permmath.url.ImageDescriptor;

class RenderContentPolicyTests {
    @Test
    void preservesExternalCascadeVariablesResponsiveStylesAndInlineOverrides() throws Exception {
        var descriptor = new ImageDescriptor("<p class='title'>hello</p>", 320,
                List.of("https://example.com/first.css", "https://example.com/second.css"), "p {color:green}");
        var html = RenderContentPolicy.document("<html><head></head><body></body></html>", descriptor,
                List.of(":root {--accent:255,0,0;} @media (min-width:200px) {.title {color:rgba(var(--accent),1)}}",
                        "p {color:blue;behavior:url(https://evil.example/x);background-image:url(https://evil.example/y)}"));
        var styles = Jsoup.parse(html).select("style");
        assertEquals(3, styles.size());
        assertTrue(styles.get(0).data().contains("var(--accent)"));
        assertTrue(styles.get(0).data().contains("@media"));
        assertTrue(styles.get(1).data().contains("color:blue"));
        assertTrue(styles.get(2).data().contains("color:green"));
        assertFalse(html.contains("evil.example"));
    }

    @Test
    void rejectsCssNetworkFunctionsEvenWhenNestedEscapedOrStoredInVariables() throws Exception {
        var css = RenderContentPolicy.css("div {--evil:url(https://evil.example/a); background-image:var(--evil);"
                + "color:expression(alert(1)); background:image-set('https://evil.example/b' 1x);"
                + "list-style-image:u\\72l(https://evil.example/c); width:calc(1px + url(https://evil.example/d));}"
                + "@font-face {font-family:evil;src:url(https://evil.example/e)}");
        assertFalse(css.contains("evil.example"));
        assertFalse(css.contains("expression("));
        assertFalse(css.contains("@font-face"));
    }

    @Test
    void removesExecutableAndNetworkMarkupBeforeParsing() throws Exception {
        var html = RenderContentPolicy.document("<html><head></head><body></body></html>",
                new ImageDescriptor("<div class='math' style='color:red'>`x^2`</div>"
                        + "<script>window.PermMathRenderer.state='ready'</script><img src='http://127.0.0.1' onerror='alert(1)'>"
                        + "<iframe srcdoc='<script>alert(1)</script>'></iframe><svg onload='alert(1)'></svg>"
                        + "<form action='http://localhost'><input></form><a href='javascript:alert(1)'>link</a>",
                        320, null, null));
        var body = Jsoup.parse(html).body();
        assertEquals(0, body.select("script,img,iframe,svg,form,input,a,[onerror],[onload],[href],[src]").size());
        assertTrue(body.text().contains("`x^2`"));
        assertEquals("math", body.selectFirst("div").className());
        assertTrue(body.selectFirst("div").attr("style").contains("color:red"));
    }

    @Test
    void stylesheetCannotEscapeOrLoadResources() throws Exception {
        var html = RenderContentPolicy.document("<html><head></head><body></body></html>",
                new ImageDescriptor("<p>hello</p>", 320, null,
                        "@import url(http://localhost/x); body {color:red;background-image:url(http://localhost/y)}"));
        var document = Jsoup.parse(html);
        assertEquals(0, document.select("script").size());
        assertFalse(document.select("style").text().contains("localhost"));
        assertTrue(html.contains("color:red"));
        assertThrows(IllegalArgumentException.class, () -> RenderContentPolicy.css(
                "body {color:red}</style><script>alert(1)</script>"));
        var boundary = RenderContentPolicy.css("p::before {content:'</style><script>alert(1)</script>'}");
        assertFalse(boundary.contains("<"));
        assertThrows(IllegalArgumentException.class, () -> RenderContentPolicy.document("",
                new ImageDescriptor("hello", null, List.of("http://127.0.0.1/x.css"), null)));
    }

    @Test
    void networkPolicyOnlyPermitsCurrentDocumentAndBundledScripts() {
        var url = "http://127.0.0.1:1234/document/abc";
        assertTrue(ChromiumRenderer.allowedRequest(url, url, "GET", "document"));
        assertTrue(ChromiumRenderer.allowedRequest(url, "http://127.0.0.1:1234/mathjax/input/asciimath.js", "GET", "script"));
        assertAll(List.of("http://169.254.169.254/latest/meta-data/", "http://localhost:1234/",
                "http://127.0.0.1:8086/", "https://example.com/", "file:///etc/passwd",
                "http://127.0.0.1:1234/document/other", "http://127.0.0.1:1234/mathjax/../document/abc").stream()
            .<Executable>map(target -> () -> {
                assertFalse(ChromiumRenderer.allowedRequest(url, target, "GET", "document"), target);
                assertFalse(ChromiumRenderer.allowedRequest(url, target, "GET", "script"), target);
            }));
        assertFalse(ChromiumRenderer.allowedRequest(url, url, "POST", "document"));
        assertFalse(ChromiumRenderer.allowedRequest(url, url, "GET", "fetch"));
    }
}
