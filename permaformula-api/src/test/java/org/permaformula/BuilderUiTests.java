package org.permaformula;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.permaformula.rendering.RenderGatewayTestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Opt-in UI checks using packaged assets, real endpoint responses, and both rendering engines. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.application.name=permaformula-builder-tests")
@Import(RenderGatewayTestConfiguration.class)
@EnabledIfSystemProperty(named = "ui.tests", matches = "true")
class BuilderUiTests {
    @LocalServerPort
    private int port;

    private String origin;

    @BeforeEach
    void useTestServerOrigin() {
        origin = "http://localhost:%s".formatted(port);
    }

    @Test
    void servesPackagedBuilderAndItsMathJaxAssets() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var index = client.send(
                HttpRequest.newBuilder(URI.create("%s/".formatted(origin)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertTrue(index.body()
                .contains("PermaFormula"));
            assertTrue(index.body()
                .contains("/assets/"));
            for (String asset : java.util.List.of("/mathjax/startup.js",
                "/mathjax/input/asciimath.js", "/mathjax/ui/safe.js")) {
                var response = client.send(
                    HttpRequest.newBuilder(URI.create("%s%s".formatted(origin, asset)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body()
                    .length() > 1000);
            }
        }
    }

    @Test
    void builderGeneratesRealFormulaAndStyledHtmlImages() throws Exception {
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
            page.navigate(origin);
            page.frameLocator("iframe[title='Formula live preview']")
                .locator("svg")
                .waitFor();
            page.frameLocator("iframe[title='HTML live preview']")
                .locator("svg")
                .waitFor();
            // Simple expressions are preview-only; their image links still use the real API.
            assertEquals(0, page.locator("#formula")
                .getByRole(AriaRole.BUTTON,
                    new com.microsoft.playwright.Locator.GetByRoleOptions()
                        .setName("Render")
                        .setExact(true))
                .count());
            var formulaTab = page.waitForPopup(() -> page.locator("#formula")
                .getByRole(AriaRole.LINK,
                    new com.microsoft.playwright.Locator.GetByRoleOptions()
                        .setName("Open SVG")
                        .setExact(true))
                .click());
            formulaTab.waitForLoadState(LoadState.LOAD);
            assertTrue(formulaTab.url()
                .startsWith("%s/exp-svg?".formatted(origin)));
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(
                    HttpRequest.newBuilder(URI.create(formulaTab.url()))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body()
                    .contains("<path"));
            }
            formulaTab.close();

            page.locator("#composer")
                .getByRole(AriaRole.BUTTON,
                    new com.microsoft.playwright.Locator.GetByRoleOptions()
                        .setName("Render"))
                .click();
            // The SVG response CSP prohibits Playwright's injected selector script.
            assertThat(page.locator("iframe[title='Renered SVG']"))
                .isVisible();
            assertTrue(page.locator("iframe[title='Renered SVG']")
                .getAttribute("src")
                .startsWith("blob:"));
            page.locator(".generated-viewer")
                .screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions()
                    .setPath(Path.of("target/builder/generated-viewer.png")));
            var imageTab = page.waitForPopup(() -> page.locator("#composer")
                .getByRole(AriaRole.LINK,
                    new com.microsoft.playwright.Locator.GetByRoleOptions()
                        .setName("Open SVG")
                        .setExact(true))
                .click());
            imageTab.waitForURL("%s/html-svg?**".formatted(origin));
            imageTab.waitForLoadState(LoadState.LOAD);
            // Inspect the actual response rather than injecting a DOM selector into a sandboxed SVG
            // document.
            try (var client = HttpClient.newHttpClient()) {
                var imageResponse = client.send(
                    HttpRequest.newBuilder(URI.create(imageTab.url()))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, imageResponse.statusCode());
                assertTrue(imageResponse.headers()
                    .firstValue("content-type")
                    .orElse("")
                    .contains("image/svg+xml"));
                assertTrue(imageResponse.body()
                    .contains("foreignObject"));
                assertTrue(imageResponse.body()
                    .contains("The sum of cubes"));
                assertTrue(imageResponse.body()
                    .contains("<path"));
            }
            assertThat(page.getByLabel("Renered SVG URL", new Page.GetByLabelOptions()
                .setExact(true)))
                .hasValue(imageTab.url());
            for (String section : java.util.List.of("#formula", "#composer")) {
                String jpgUrl = page.locator(section)
                    .getByRole(AriaRole.LINK,
                        new com.microsoft.playwright.Locator.GetByRoleOptions()
                            .setName("Open JPG")
                            .setExact(true))
                    .getAttribute("href");
                try (var client = HttpClient.newHttpClient()) {
                    var response = client.send(HttpRequest.newBuilder(URI.create(jpgUrl))
                        .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, response.statusCode());
                    assertTrue(response.headers()
                        .firstValue("content-type")
                        .orElse("")
                        .contains("image/jpeg"));
                    assertTrue(javax.imageio.ImageIO
                        .read(new java.io.ByteArrayInputStream(response.body())) != null);
                }
            }
            Files.createDirectories(Path.of("target/builder"));
            page.bringToFront();
            page.evaluate("window.scrollTo({top:0,behavior:'instant'})");
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target/builder/builder.png"))
                .setFullPage(true));
        }
    }
}
