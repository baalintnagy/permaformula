package org.permmath;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new org.boava.exception.LocalExceptionHandler()).build();
    }

    @Test
    void bothSvgEndpointsSendRestrictiveHeaders() throws Exception {
        when(renderer.getImg("x")).thenReturn("<svg/>");
        when(renderer.getImage(any())).thenReturn("<svg/>");
        String link = urlService.getImageUrl("x", null, null, null);
        for (var request : List.of(get("/img").param("formula", "x"), followLink(link))) {
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Content-Security-Policy", org.permmath.rendering.SvgOutputPolicy.CSP))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Content-Type-Options", "nosniff"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Referrer-Policy", "no-referrer"));
        }
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

    @Test
    void urlAndDecodeReturn503BeforePreparationWhenCapacityIsOccupied() throws Exception {
        var work = new org.permmath.rendering.ApiWorkService(new org.permmath.rendering.ApiWorkProperties(
                1, 1, 1, java.time.Duration.ofSeconds(5)));
        ReflectionTestUtils.setField(urlService, "work", work);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var caller = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var active = caller.submit(() -> work.prepare(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
                return "done";
            }));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                mvc.perform(get("/url").param("formula", "x")).andExpect(status().isServiceUnavailable());
                mvc.perform(get("/decode").param("ubcq", "%%%"))
                        .andExpect(status().isServiceUnavailable());
            } finally { release.countDown(); }
            active.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        mvc.perform(get("/url").param("formula", "x")).andExpect(status().isOk());
    }

    @Test
    void requestDeadlineCoversDecodingAndSubsequentRequestsRecover() throws Exception {
        var compression = mock(StringCompressionService.class);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(compression.decompress(any())).thenAnswer(_ -> {
            worker.set(Thread.currentThread());
            new java.util.concurrent.CountDownLatch(1).await();
            return "never";
        });
        ReflectionTestUtils.setField(urlService, "stringCompressionService", compression);
        var controller = new PermMathController();
        ReflectionTestUtils.setField(controller, "urlService", urlService);
        ReflectionTestUtils.setField(controller, "generatedImageService", renderer);
        var properties = new org.permmath.rendering.ApiWorkProperties(1, 1, 1, java.time.Duration.ofMillis(100));
        var timedMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new org.boava.exception.LocalExceptionHandler())
                .addFilters(new org.permmath.rendering.ApiDeadlineFilter(properties)).build();
        timedMvc.perform(get("/decode").param("ubcq", "valid-but-stalled"))
                .andExpect(status().isGatewayTimeout());
        org.junit.jupiter.api.Assertions.assertNotNull(worker.get());
        worker.get().join(2000);
        ReflectionTestUtils.setField(urlService, "stringCompressionService", new StringCompressionService());
        timedMvc.perform(get("/url").param("formula", "x")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/image", "/decode" })
    void compressedEndpointsRejectMalformedAndInvalidDescriptorsBeforeRendering(String endpoint) throws Exception {
        for (String json : List.of("null", "{}", "{\"forumla\":\" \"}",
                "{\"forumla\":\"x\",\"imageWidth\":4001}",
                "{\"forumla\":\"x\",\"styleRefs\":[\"\"]}",
                "{\"forumla\":\"x\",\"styleRefs\":[" + String.join(",", java.util.Collections.nCopies(9, "\"a.css\"")) + "]}",
                "{\"forumla\":\"x\",\"style\":\"" + "s".repeat(org.permmath.validation.InputLimits.MAX_STYLE_BYTES + 1) + "\"}",
                "{\"forumla\":\"" + "x".repeat(org.permmath.validation.InputLimits.MAX_CONTENT_BYTES + 1) + "\"}",
                "{\"forumla\":\"x\"} {}",
                "{\"forumla\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")) {
            mvc.perform(get(endpoint).param("ubcq", new StringCompressionService().compress(json)))
                    .andExpect(status().isBadRequest());
        }
        for (String payload : List.of("%%%", "AQIDBA==")) {
            mvc.perform(get(endpoint).param("ubcq", payload)).andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/image", "/decode" })
    void compressionBombReturnsBadRequestWithoutRendering(String endpoint) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write("x".repeat(org.permmath.validation.InputLimits.MAX_JSON_BYTES + 1)
                    .getBytes(StandardCharsets.UTF_8));
        }
        mvc.perform(get(endpoint).param("ubcq", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void directEndpointsRejectOversizedUtf8ContentAndStyles() throws Exception {
        mvc.perform(get("/img").param("formula", "漢".repeat(org.permmath.validation.InputLimits.MAX_FORMULA_BYTES / 3 + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/url").param("formula", "x".repeat(
                org.permmath.validation.InputLimits.MAX_CONTENT_BYTES + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/url").param("formula", "x").param("style", "x".repeat(
                org.permmath.validation.InputLimits.MAX_STYLE_BYTES + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/url").param("formula", "x").param("styleRefs",
                java.util.Collections.nCopies(9, "https://example.com/a.css").toArray(String[]::new)))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(renderer);
    }
}
