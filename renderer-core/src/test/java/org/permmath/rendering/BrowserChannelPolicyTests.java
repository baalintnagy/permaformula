package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exercise the browser guards directly, without the input sanitizer or CSP hiding a missing guard. */
@EnabledIfSystemProperty(named = "rendering.tests", matches = "true")
class BrowserChannelPolicyTests {
    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll static void launch() {
        var browsers = Path.of("../.runtime/browsers").toAbsolutePath();
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_BROWSERS_PATH", browsers.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setExecutablePath(Path.of(playwright.chromium().executablePath())).setHeadless(true));
    }

    @AfterAll static void close() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test void blocksWebSocketsAndServiceWorkersWithoutContactingTheirServer() throws Exception {
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            var page = fixture.page(context);
            assertEquals("closed", page.evaluate("""
                    url => new Promise(resolve => {
                      const socket = new WebSocket(url);
                      socket.onclose = () => resolve('closed');
                      socket.onerror = () => resolve('closed');
                    })
                    """, fixture.base.replace("http:", "ws:") + "/socket"));
            assertEquals(java.util.List.of("SecurityError", "SecurityError"), page.evaluate("""
                    async () => {
                      const results = [];
                      for (const register of [navigator.serviceWorker.register, ServiceWorkerContainer.prototype.register]) {
                        try { await register.call(navigator.serviceWorker, '/export-svg.js'); results.push('registered'); }
                        catch (error) { results.push(error.name); }
                      }
                      return results;
                    }
                    """));
            assertEquals(0, ((Number) page.evaluate("async () => (await navigator.serviceWorker.getRegistrations()).length")).intValue());
            assertEquals(0, fixture.otherHits.get());
            assertEquals(0, fixture.scriptHits.get(), "Even an otherwise allowed script must not load as a worker");
        }
    }

    @Test void disablesWorkersAndWebRtcBeforeAnyDocumentScriptExecutes() throws Exception {
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            fixture.html = """
                    <script>
                    window.results = ['Worker','SharedWorker','RTCPeerConnection','webkitRTCPeerConnection',
                                      'WebTransport','WebSocketStream'].map(name => {
                      try { new window[name]('/export-svg.js'); return 'allowed'; }
                      catch (error) { return error.name; }
                    });
                    </script>
                    """;
            var page = fixture.page(context);
            assertEquals(java.util.Collections.nCopies(6, "SecurityError"),
                    page.evaluate("window.results"));
            assertEquals(Boolean.TRUE, page.evaluate("""
                    () => {
                      try { Object.defineProperty(window, 'Worker', {value: function () {}}); return false; }
                      catch (error) { return true; }
                    }
                    """));
            assertEquals(0, fixture.scriptHits.get());
        }
    }

    @Test void deniesPopupApisAndClosesUnexpectedPages() throws Exception {
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            var page = fixture.page(context);
            assertNull(page.evaluate("url => window.open(url)", fixture.url));
            // Closing a just-created page can dispose its protocol object before newPage returns.
            attempt(context::newPage);
            context.waitForCondition(() -> context.pages().size() == 1);
            page.evaluate("url => {const link=document.createElement('a');link.id='native-popup';link.href=url;link.target='_blank';link.textContent='open';document.body.appendChild(link);}", fixture.url);
            var popup = context.waitForPage(() -> page.locator("#native-popup").click());
            context.waitForCondition(popup::isClosed);
            assertFalse(page.isClosed());
            assertEquals(1, context.pages().size());
            assertEquals(1, fixture.documentHits.get());
        }
    }

    @Test void rejectsEvenNonNetworkChildFrames() throws Exception {
        for (var source : java.util.List.of("about:blank", "srcdoc", "data:text/html,child", "same-document")) {
            try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
                var page = fixture.page(context);
                attempt(() -> page.evaluate("""
                        source => {
                          const frame = document.createElement('iframe');
                          if (source === 'srcdoc') frame.srcdoc = '<p>child</p>';
                          else frame.src = source === 'same-document' ? location.href : source;
                          document.body.appendChild(frame);
                        }
                        """, source));
                context.waitForCondition(page::isClosed);
                assertEquals(0, fixture.otherHits.get());
                assertEquals(1, fixture.documentHits.get());
            }
        }
    }

    @Test void blocksReloadAndNavigationOutsideTheInitialDocument() throws Exception {
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            var page = fixture.page(context);
            attempt(page::reload);
            assertEquals(1, fixture.documentHits.get(), "Same-URL reload is still an additional navigation");
        }
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            var page = fixture.page(context);
            attempt(() -> page.navigate("data:text/html,unexpected"));
            context.waitForCondition(page::isClosed);
            assertEquals(0, fixture.otherHits.get());
        }
    }

    @Test void neverFollowsDocumentOrScriptRedirects() throws Exception {
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            fixture.redirectDocument = true;
            var page = BrowserChannelPolicy.newPage(context, fixture.url);
            page.setDefaultNavigationTimeout(5000);
            assertThrows(PlaywrightException.class, () -> page.navigate(fixture.url));
            assertEquals(1, fixture.documentHits.get());
            assertEquals(0, fixture.otherHits.get());
        }
        try (var fixture = new Fixture(); var context = BrowserChannelPolicy.newContext(browser, 320)) {
            fixture.html = "<script src='/export-svg.js'></script><p>safe</p>";
            fixture.redirectScript = true;
            var page = fixture.page(context);
            assertEquals("safe", page.locator("p").textContent());
            assertEquals(1, fixture.scriptHits.get());
            assertEquals(0, fixture.otherHits.get());
        }
    }

    private static void attempt(Runnable action) {
        try { action.run(); }
        catch (PlaywrightException abortedOrClosed) { /* Expected when a policy aborts or closes the page. */ }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger documentHits = new AtomicInteger(), scriptHits = new AtomicInteger(), otherHits = new AtomicInteger();
        final String base, url;
        volatile String html = "<!doctype html><html><body><p>safe</p></body></html>";
        volatile boolean redirectDocument, redirectScript;

        Fixture() throws Exception {
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            url = base + "/document/test";
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                boolean document = path.equals("/document/test"), script = path.equals("/export-svg.js");
                (document ? documentHits : script ? scriptHits : otherHits).incrementAndGet();
                if ((document && redirectDocument) || (script && redirectScript)) {
                    exchange.getResponseHeaders().set("Location", base + "/unexpected");
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    var bytes = (document ? html : "self.addEventListener('fetch', () => {});").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", document ? "text/html" : "application/javascript");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.start();
        }

        Page page(BrowserContext context) {
            var page = BrowserChannelPolicy.newPage(context, url);
            page.setDefaultTimeout(5000);
            page.setDefaultNavigationTimeout(5000);
            page.navigate(url);
            return page;
        }

        @Override public void close() { server.stop(0); }
    }
}
