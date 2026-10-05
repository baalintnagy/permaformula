package org.permmath;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.permmath.compression.StringCompressionService;
import org.permmath.rendering.ImageRendererService;
import org.permmath.url.ImageDescriptor;
import org.permmath.url.UrlService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

class PermMathControllerTests {

    private MockMvc mvc;
    private UrlService urlService;
    private ImageRendererService renderer;

    @BeforeEach
    void setUp() {
        PermMathProperties properties = new PermMathProperties();
        ReflectionTestUtils.setField(properties, "host", "http://localhost:8086");
        urlService = new UrlService();
        ReflectionTestUtils.setField(urlService, "permMathProperties", properties);
        ReflectionTestUtils.setField(urlService, "objectMapper", JsonMapper.builder().build());
        ReflectionTestUtils.setField(urlService, "stringCompressionService", new StringCompressionService());
        PermMathController controller = new PermMathController();
        ReflectionTestUtils.setField(controller, "urlService", urlService);
        renderer = mock(ImageRendererService.class);
        ReflectionTestUtils.setField(controller, "imageRendererService", renderer);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void urlAcceptsCorrectlySpelledImageWidth() throws Exception {
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", "640"))
                .andExpect(status().isOk())
                .andExpect(content().string("http://localhost:8086/image?formula=x&imageWidth=640"));
    }

    @Test
    void decodeCompressedPayloadPreservesFormulaAndStyles() throws Exception {
        String formula = "x + y = 50%";
        String style = "width: 50%; color: red;";
        String imageUrl = urlService.getImageUrl(formula, 640,
                List.of("https://example.com/style.css?q=a+b%20c"), style, true);
        String query = URI.create(imageUrl).getRawQuery();

        mvc.perform(get(URI.create("/decode-cq?" + query)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.forumla").value(formula))
                .andExpect(jsonPath("$.imageWidth").value(640))
                .andExpect(jsonPath("$.style").value(style))
                .andExpect(jsonPath("$.styleRefs[0]").value("https://example.com/style.css?q=a+b%20c"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void generatedLinksPassTheSameContentToRenderer(boolean compressed) throws Exception {
        String formula = "<p>`α + β = 50% & x # y?`</p>";
        List<String> refs = List.of("https://example.com/a.css?q=a+b%20c&v=1",
                "https://example.com/b.css");
        String style = "p { width: 50%; }";
        byte[] image = { 1, 2, 3 };
        when(renderer.getImage(any())).thenReturn(
                new InputStreamResource(new ByteArrayInputStream(image)));

        String link = mvc.perform(get("/url").param("formula", formula)
                .param("imageWidth", "640").param("styleRefs", refs.toArray(String[]::new))
                .param("style", style).param("compressUrlQuery", Boolean.toString(compressed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mvc.perform(followLink(link))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"))
                .andExpect(content().bytes(image));
        verify(renderer).getImage(new ImageDescriptor(formula, 640, refs, style));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void generatedLinksOmitAbsentOptionsAndNormalizeWhitespace(boolean compressed) throws Exception {
        when(renderer.getImage(any())).thenReturn(
                new InputStreamResource(new ByteArrayInputStream(new byte[] { 1 })));
        String link = urlService.getImageUrl("x\n\t +   y", null, List.of(), " \n ", compressed);

        mvc.perform(followLink(link)).andExpect(status().isOk());
        // Optional fields should have the same meaning on both URL paths.
        verify(renderer).getImage(new ImageDescriptor("x + y", null, null, null));
    }

    @Test
    void compressionIsOptIn() throws Exception {
        mvc.perform(get("/url").param("formula", "x"))
                .andExpect(status().isOk())
                .andExpect(content().string("http://localhost:8086/image?formula=x"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/url", "/image", "/image-cq", "/decode", "/decode-cq" })
    void missingRequiredInputReturnsBadRequest(String endpoint) throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder followLink(String link) {
        URI uri = URI.create(link);
        MockHttpServletRequestBuilder request = get(uri.getPath());
        // MockMvc's URI overload does not apply servlet form-query decoding to '+'.
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] parts = parameter.split("=", 2);
            request.param(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return request;
    }
}
