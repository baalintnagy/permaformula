package org.permmath.rendering;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

/**
 * One rendering page, one initial document navigation, no additional execution contexts.
 */
final class BrowserChannelPolicy {

    private static final String DISABLE_CHANNELS = loadGuards();

    private static String loadGuards() {
        try (var input = BrowserChannelPolicy.class.getResourceAsStream("/browser-guards.js")) {
            if (input == null) {
                throw new IllegalStateException("Missing browser guards resource");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to load browser guards", error);
        }
    }

    static BrowserContext newContext(Browser browser, int width) {
        var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(width, 600)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK).setAcceptDownloads(false));
        context.addInitScript(DISABLE_CHANNELS);
        context.routeWebSocket("**/*", socket -> {
            // Never connect to the server or forward close events to a nonexistent server socket.
            socket.onClose((_, _) -> {
            });
            socket.close(new WebSocketRoute.CloseOptions().setCode(1008)
                    .setReason("WebSockets are disabled in the renderer"));
        });
        return context;
    }

    static Page newPage(BrowserContext context, String documentUrl) {
        // The initial about:blank page has no content. Install all guards before navigating it.
        var page = context.newPage();
        var documentAdmitted = new AtomicBoolean();
        context.onPage(extra -> {
            if (extra != page) {
                extra.close();
            }
        });
        // Fail the render even for srcdoc/data/about:blank frames.
        page.onFrameAttached(_ -> page.close());
        page.onFrameNavigated(frame -> {
            if (frame != page.mainFrame() || (!documentUrl.equals(frame.url()) && !("about:blank".equals(frame.url()) && !documentAdmitted.get()))) {
                page.close();
            }
        });
        context.route("**/*", route -> {
            var request = route.request();
            boolean allowed = false;
            try {
                // Popup/worker requests may have no frame yet: fail closed in that case.
                allowed = request.frame() == page.mainFrame() && request.redirectedFrom() == null
                        && ChromiumRenderer.allowedRequest(documentUrl, request.url(), request.method(), request.resourceType());
                if (allowed && "document".equals(request.resourceType())) {
                    allowed = request.isNavigationRequest() && documentAdmitted.compareAndSet(false, true);
                } else if (request.isNavigationRequest()) {
                    allowed = false;
                }
            } catch (PlaywrightException unavailableFrame) {
                /* No approved frame, no request. */
            }
            if (!allowed) {
                route.abort();
                return;
            }
            // Inspect trusted asset responses without following redirects. Simply resuming a
            // browser request can follow redirects outside the original interception callback.
            var response = route.fetch(new Route.FetchOptions().setMaxRedirects(0).setMaxRetries(0).setTimeout(5000));
            try {
                if (response.status() == 200) {
                    route.fulfill(new Route.FulfillOptions().setResponse(response));
                } else {
                    route.abort();
                }
            } finally {
                response.dispose();
            }
        });
        return page;
    }

    private BrowserChannelPolicy() {
    }
}
