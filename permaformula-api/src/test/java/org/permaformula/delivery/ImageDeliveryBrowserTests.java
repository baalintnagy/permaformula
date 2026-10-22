package org.permaformula.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in integration check against the separate running Compose smoke deployment. */
@EnabledIfSystemProperty(named = "delivery.tests", matches = "true")
class ImageDeliveryBrowserTests {
    @Test
    void anonymousSwaggerFetchesFollowImageRedirectsButCredentialedReadsAreDenied() {
        String api = System.getProperty("delivery.api-url", "http://localhost:5017");
        String images = System.getProperty("delivery.image-url", "http://localhost:5018");
        try (var playwright = Playwright.create(new Playwright.CreateOptions()
            .setEnv(Map.of(
                "PLAYWRIGHT_BROWSERS_PATH",
                Path.of("../.runtime/browsers")
                    .toAbsolutePath()
                    .toString(),
                "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            var browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(Path.of(playwright.chromium()
                        .executablePath()))
                    .setHeadless(true))) {
            var page = browser.newPage();
            page.setDefaultTimeout(15000);
            page.navigate("%s/swagger-ui/index.html".formatted(api));
            Object result = page.evaluate(
                """
                async ({api, images}) => {
                    const link = await fetch(api + '/ubcq?formula=' + encodeURIComponent('<p>\
                browser `x^2`</p>'), {credentials: 'omit'});
                    const ubcq = await link.text();
                    const url = api + '/html-svg?ubcq=' + encodeURIComponent(ubcq);
                    if (!link.ok || !ubcq.startsWith('H4sI')) throw new Error('Wrong permalink \
                origin');
                    const formula = await fetch(api + '/exp-svg?formula=x%5E2', {credentials: \
                'omit'});
                    if (!formula.ok || !formula.url.startsWith(images + '/exp-svg?') || \
                formula.type !== 'cors') throw new Error('Anonymous redirect/CORS failed');
                    if (!(await formula.text()).includes('<svg')) throw new Error('Expected \
                formula SVG');
                    const html = await fetch(url, {credentials: 'omit'});
                    if (!html.ok || !(await html.text()).includes('<svg')) throw new \
                Error('Expected HTML SVG');
                    for (const path of ['/exp-jpg?formula=x%5E2', '/html-jpg?' + \
                url.split('?')[1]]) {
                        const jpeg = await fetch(api + path, {credentials: 'omit'});
                        if (!jpeg.ok || !jpeg.url.startsWith(images) || \
                jpeg.headers.get('content-type') !== 'image/jpeg') {
                            throw new Error('JPEG redirect/CORS failed');
                        }
                        const bytes = new Uint8Array(await jpeg.arrayBuffer());
                        if (bytes[0] !== 255 || bytes[1] !== 216) throw new Error('Invalid \
                JPEG');
                    }
                    try {
                        await fetch(images + '/exp-svg?formula=x%5E2', {credentials: \
                'include'});
                        throw new Error('Credentialed image read unexpectedly allowed');
                    } catch (error) {
                        if (!(error instanceof TypeError)) throw error;
                    }
                    return true;
                }
                """
                ,
                Map.of("api", api, "images", images));
            assertEquals(Boolean.TRUE, result);
        }
    }
}
