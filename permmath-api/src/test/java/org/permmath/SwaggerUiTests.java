package org.permmath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.permmath.rendering.RenderGatewayTestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Playwright;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RenderGatewayTestConfiguration.class)
@EnabledIfSystemProperty(named = "swagger.tests", matches = "true")
class SwaggerUiTests {
    @LocalServerPort
    private int port;

    @Test
    void urlAndImageExecuteWithSwaggerDefaults() {
        try (var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_BROWSERS_PATH", Path.of("../.runtime/browsers").toAbsolutePath().toString(),
                "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setExecutablePath(Path.of(playwright.chromium().executablePath())).setHeadless(true))) {
            var page = browser.newPage();
            page.setDefaultTimeout(10000);
            page.navigate("http://localhost:" + port + "/swagger-ui/index.html");
            var operation = page.locator("#operations-perm-math-controller-getUrl");
            operation.locator(".opblock-summary").click();
            operation.getByText("Try it out", new Locator.GetByTextOptions().setExact(true)).click();
            var response = page.waitForResponse(r -> r.url().contains("/url?") && r.status() == 200,
                    () -> operation.getByText("Execute", new Locator.GetByTextOptions().setExact(true)).click());
            assertTrue(response.text().startsWith("http://localhost:8086/image?ubcq="));
            operation.locator(".responses-inner .response-col_status").filter(
                    new Locator.FilterOptions().setHasText("200")).first().waitFor();
            var imageOperation = page.locator("#operations-perm-math-controller-getImage");
            imageOperation.locator(".opblock-summary").click();
            imageOperation.getByText("Try it out", new Locator.GetByTextOptions().setExact(true)).click();
            var imageResponse = page.waitForResponse(r -> r.url().contains("/image?") && r.status() == 200,
                    () -> imageOperation.getByText("Execute", new Locator.GetByTextOptions().setExact(true)).click());
            assertTrue(imageResponse.text().startsWith("<svg"));
            assertTrue(imageResponse.text().contains("width=\"220\""));
            assertTrue(imageResponse.text().contains("<path"));
        }
    }
}
