package org.permaformula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BuilderPageControllerTests {
    @Test
    void crawlerDiscoveryUsesConfiguredOrigin() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BuilderPageController(
            new PermaFormulaProperties("https://math.example.com", 500, 2000)))
            .build();
        String robots = mvc.perform(get("/robots.txt")
            .header("Host", "untrusted.example"))
            .andExpect(status()
                .isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(robots)
            .contains("Disallow: /admin",
                "Sitemap: https://math.example.com/sitemap.xml")
            .doesNotContain("untrusted.example");
        String sitemap = mvc.perform(get("/sitemap.xml")
            .header("Host", "untrusted.example"))
            .andExpect(status()
                .isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(sitemap)
            .contains("<loc>https://math.example.com/</loc>")
            .doesNotContain("untrusted.example", "#library", "/admin");
    }

    @Test
    void metadataEscapesAttributeCharacters() {
        String result = BuilderPageController.withPublicMetadata(
            "<!-- PERMAFORMULA_PUBLIC_METADATA -->",
            "https://example.com/\"<&");
        assertThat(result)
            .contains("&quot;", "&lt;", "&amp;")
            .doesNotContain("<!-- PERMAFORMULA_PUBLIC_METADATA -->");
    }

    @Test
    void crawlerReceivesPublicMetadataWithoutJavascriptAtBothEntryPoints() throws Exception {
        // Frontend resources are packaged by Maven when frontend/dist is present.
        org.junit.jupiter.api.Assumptions.assumeTrue(new ClassPathResource("static/index.html")
            .exists());
        var mvc = MockMvcBuilders.standaloneSetup(new BuilderPageController(
            new PermaFormulaProperties("https://math.example.com", 500, 2000)))
            .build();
        for (String path : new String[] { "/", "/index.html" }) {
            String html = mvc.perform(get(path)
                .header("Host", "untrusted.example"))
                .andExpect(status()
                    .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(html)
                .contains("<title>PermaFormula - Formulae with style</title>",
                    "property=\"og:url\" content=\"https://math.example.com/\"",
                    "property=\"og:image\" content=\"https://math.example.com/assets/og.jpg\"",
                    "name=\"twitter:card\" content=\"summary_large_image\"")
                .doesNotContain("PERMAFORMULA_PUBLIC_METADATA", "untrusted.example");
        }
    }
}
