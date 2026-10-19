package org.permaformula;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/** Supplies absolute public metadata before social crawlers receive the page. */
@Controller
public class BuilderPageController {
    private final PermaFormulaProperties origins;

    public BuilderPageController(PermaFormulaProperties origins) {
        this.origins = origins;
    }

    @GetMapping(value = { "/", "/index.html" }, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> builder() throws IOException {
        ClassPathResource page = new ClassPathResource("static/index.html");
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
            .body(withPublicMetadata(page.getContentAsString(StandardCharsets.UTF_8),
                origins.host()));
    }

    static String withPublicMetadata(String html, String origin) {
        String url = HtmlUtils.htmlEscape(origin + "/");
        String image = HtmlUtils.htmlEscape(origin + "/assets/og.jpg");
        String metadata = """
            <link rel="canonical" href="%s" />
            <meta property="og:url" content="%s" />
            <meta property="og:image" content="%s" />
            <meta name="twitter:image" content="%s" />
            """.formatted(url, url, image, image);
        return html.replace("<!-- PERMAFORMULA_PUBLIC_METADATA -->", metadata);
    }
}
