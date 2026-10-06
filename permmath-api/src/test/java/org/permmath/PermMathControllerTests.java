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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.permmath.compression.StringCompressionService;
import org.permmath.rendering.GeneratedImageService;
import org.permmath.url.ImageDescriptor;
import org.permmath.url.UrlService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

class PermMathControllerTests {

    private MockMvc mvc;
    private UrlService urlService;
    private GeneratedImageService renderer;

    @BeforeEach
    void setUp() {
        PermMathProperties properties = new PermMathProperties("http://localhost:8086", 800, 2000.0f);
        urlService = new UrlService();
        ReflectionTestUtils.setField(urlService, "permMathProperties", properties);
        ReflectionTestUtils.setField(urlService, "objectMapper", JsonMapper.builder().build());
        ReflectionTestUtils.setField(urlService, "stringCompressionService", new StringCompressionService());
        PermMathController controller = new PermMathController();
        ReflectionTestUtils.setField(controller, "urlService", urlService);
        renderer = mock(GeneratedImageService.class);
        ReflectionTestUtils.setField(controller, "generatedImageService", renderer);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void urlAcceptsCorrectlySpelledImageWidth() throws Exception {
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", "640"))
                .andExpect(status().isOk())
                .andExpect(content().string(urlService.getImageUrl("x", 640, null, null)));
    }

    @Test
    void decodeCompressedPayloadPreservesFormulaAndStyles() throws Exception {
        String formula = "x + y = 50%";
        String style = "width: 50%; color: red;";
        String imageUrl = urlService.getImageUrl(formula, 640,
                List.of("https://example.com/style.css?q=a+b%20c"), style);
        String query = URI.create(imageUrl).getRawQuery();

        mvc.perform(get(URI.create("/decode?" + query)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.forumla").value(formula))
                .andExpect(jsonPath("$.imageWidth").value(640))
                .andExpect(jsonPath("$.style").value(style))
                .andExpect(jsonPath("$.styleRefs[0]").value("https://example.com/style.css?q=a+b%20c"));
    }

    @Test
    void generatedLinksPassTheSameContentToRenderer() throws Exception {
        String formula = "  <pre>α\n\t +  β = 50% & x # y?</pre>  ";
        List<String> refs = List.of("https://example.com/a.css?q=a+b%20c&v=1",
                "https://example.com/b.css");
        String style = "\npre::before { content: 'a  b'; }\n\tpre { width: 50%; }  ";
        String image = "<svg>α</svg>";
        when(renderer.getImage(any())).thenReturn(image);

        String link = mvc.perform(get("/url").param("formula", formula)
                .param("imageWidth", "640").param("styleRefs", refs.toArray(String[]::new))
                .param("style", style))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mvc.perform(followLink(link))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(image))
                .andExpect(content().bytes(image.getBytes(StandardCharsets.UTF_8)));
        verify(renderer).getImage(new ImageDescriptor(formula, 640, refs, style));
    }

    @Test
    void generatedLinksPreserveWhitespaceExactly() throws Exception {
        when(renderer.getImage(any())).thenReturn("<svg/>");
        String link = urlService.getImageUrl("x\n\t +   y", null, List.of(), " \n ");

        mvc.perform(followLink(link)).andExpect(status().isOk());
        verify(renderer).getImage(new ImageDescriptor("x\n\t +   y", null, null, " \n "));
    }

    @Test
    void formulaEndpointPassesUncompressedFormulaExactly() throws Exception {
        String formula = "  x\n\t +   y = 50% & z # w?  ";
        when(renderer.getImg(formula)).thenReturn("<svg/>");
        mvc.perform(get("/img").param("formula", formula))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string("<svg/>"));
        verify(renderer).getImg(formula);
    }

    @Test
    void compressionIsAlwaysUsedAndDeterministic() throws Exception {
        mvc.perform(get("/url").param("formula", "x"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("http://localhost:8086/image?ubcq=")))
                .andExpect(content().string(urlService.getImageUrl("x", null, null, null)));
        mvc.perform(get("/url").param("formula", "x").param("compressUrlQuery", "false"))
                .andExpect(content().string(urlService.getImageUrl("x", null, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/url", "/image", "/decode", "/img" })
    void missingRequiredInputReturnsBadRequest(String endpoint) throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder followLink(String link) {
        URI uri = URI.create(link);
        MockHttpServletRequestBuilder request = get(uri.getPath());
        // MockMvc's URI overload does not apply servlet form-query decoding to '+'.
        java.util.Arrays.stream(uri.getRawQuery().split("&")).forEach(parameter -> {
            String[] parts = parameter.split("=", 2);
            request.param(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        });
        return request;
    }
}
