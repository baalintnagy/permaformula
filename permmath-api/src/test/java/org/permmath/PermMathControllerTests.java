package org.permmath;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.boava.exception.LocalExceptionHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.permmath.compression.StringCompressionService;
import org.permmath.rendering.ApiDeadlineFilter;
import org.permmath.rendering.ApiWorkProperties;
import org.permmath.rendering.ApiWorkService;
import org.permmath.rendering.GeneratedImageService;
import org.permmath.rendering.SvgOutputPolicy;
import org.permmath.rendering.WorkDeadline;
import org.permmath.url.ImageDescriptor;
import org.permmath.url.UrlService;
import org.permmath.validation.InputLimits;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

class PermMathControllerTests {

    private MockMvc mvc;
    private UrlService urlService;
    private GeneratedImageService renderer;

    @Test
    void ubcqReturnsOnlyRawBase64WithSafePlainTextHeaders() throws Exception {
        String formula = "<p>α + 50% &amp; x/y</p>";
        String value = mvc.perform(get("/ubcq").param("formula", formula))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(value.matches("[A-Za-z0-9+/]+={0,2}"));
        Assertions.assertEquals(formula, urlService.decodeUbcq(value).forumla());
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void jpegRoutesReturnBinaryImagesAndValidateParameters() throws Exception {
        byte[] jpeg = { (byte) 255, (byte) 216, (byte) 255, (byte) 217 };
        when(renderer.getExpJpg("x+y")).thenReturn(jpeg);
        mvc.perform(get("/exp-jpg").param("formula", "x+y"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpeg))
                .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"));
        String ubcq = urlService.getUbcq("<p>hello</p>", 500, null, null);
        when(renderer.getHtmlJpg(any())).thenReturn(jpeg);
        mvc.perform(get("/html-jpg").param("ubcq", ubcq)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(jpeg));
        mvc.perform(get("/exp-jpg")).andExpect(status().isBadRequest());
        mvc.perform(get("/exp-jpg").param("formula", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/html-jpg")).andExpect(status().isBadRequest());
        mvc.perform(get("/html-jpg").param("ubcq", "invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void removedDecodeRoutesAreNotAvailable() throws Exception {
        assertAll(List.of("/decode", "/decode/", "/decode;ignored", "/decode-cq", "/url", "/url-encode").stream()
            .<Executable>map(path -> () -> {
                mvc.perform(get(path).param("ubcq", "bad")).andExpect(status().isNotFound());
            }));
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void ubcqHonoursTheExistingRequestDeadline() throws Exception {
        try (var _ = WorkDeadline.open(Duration.ofNanos(1))) {
            mvc.perform(get("/ubcq").param("formula", "x")).andExpect(status().isGatewayTimeout());
        }
        mvc.perform(get("/ubcq").param("formula", "x")).andExpect(status().isOk());
        Mockito.verifyNoInteractions(renderer);
    }

    @BeforeEach
    void setUp() {
        urlService = new UrlService();
        ReflectionTestUtils.setField(urlService, "objectMapper", JsonMapper.builder().build());
        ReflectionTestUtils.setField(urlService, "stringCompressionService", new StringCompressionService());
        PermMathController controller = new PermMathController();
        ReflectionTestUtils.setField(controller, "urlService", urlService);
        renderer = mock(GeneratedImageService.class);
        ReflectionTestUtils.setField(controller, "generatedImageService", renderer);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new LocalExceptionHandler()).build();
    }

    @Test
    void bothSvgEndpointsSendRestrictiveHeaders() throws Exception {
        when(renderer.getExp("x")).thenReturn("<svg/>");
        when(renderer.getHtml(any())).thenReturn("<svg/>");
        String link = urlService.getUbcq("x", null, null, null);
        assertAll(List.of(get("/exp").param("formula", "x"), followLink(link)).stream()
            .<Executable>map(request -> () -> {
                mvc.perform(request).andExpect(status().isOk())
                        .andExpect(MockMvcResultMatchers.header()
                                .string("Content-Security-Policy", SvgOutputPolicy.CSP))
                        .andExpect(MockMvcResultMatchers.header()
                                .string("X-Content-Type-Options", "nosniff"))
                        .andExpect(MockMvcResultMatchers.header()
                                .string("Referrer-Policy", "no-referrer"));
            }));
    }

    @Test
    void urlAcceptsCorrectlySpelledImageWidth() throws Exception {
        mvc.perform(get("/ubcq").param("formula", "x").param("imageWidth", "640"))
                .andExpect(status().isOk())
                .andExpect(content().string(urlService.getUbcq("x", 640, null, null)));
    }


    @Test
    void generatedLinksPassTheSameContentToRenderer() throws Exception {
        String formula = "  <pre>α\n\t +  β = 50% & x # y?</pre>  ";
        List<String> refs = List.of("https://example.com/a.css?q=a+b%20c&v=1",
                "https://example.com/b.css");
        String style = "\npre::before { content: 'a  b'; }\n\tpre { width: 50%; }  ";
        String image = "<svg>α</svg>";
        when(renderer.getHtml(any())).thenReturn(image);

        String link = mvc.perform(get("/ubcq").param("formula", formula)
                .param("imageWidth", "640").param("styleRefs", refs.toArray(String[]::new))
                .param("style", style))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mvc.perform(followLink(link))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(image))
                .andExpect(content().bytes(image.getBytes(StandardCharsets.UTF_8)));
        verify(renderer).getHtml(new ImageDescriptor(formula, 640, refs, style));
    }

    @Test
    void generatedLinksPreserveWhitespaceExactly() throws Exception {
        when(renderer.getHtml(any())).thenReturn("<svg/>");
        String link = urlService.getUbcq("x\n\t +   y", null, List.of(), " \n ");

        mvc.perform(followLink(link)).andExpect(status().isOk());
        verify(renderer).getHtml(new ImageDescriptor("x\n\t +   y", null, null, " \n "));
    }

    @Test
    void formulaEndpointPassesUncompressedFormulaExactly() throws Exception {
        String formula = "  x\n\t +   y = 50% & z # w?  ";
        when(renderer.getExp(formula)).thenReturn("<svg/>");
        mvc.perform(get("/exp").param("formula", formula))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string("<svg/>"));
        verify(renderer).getExp(formula);
    }

    @Test
    void compressionIsAlwaysUsedAndDeterministic() throws Exception {
        mvc.perform(get("/ubcq").param("formula", "x"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.startsWith("H4sI")))
                .andExpect(content().string(urlService.getUbcq("x", null, null, null)));
        mvc.perform(get("/ubcq").param("formula", "x").param("compressUrlQuery", "false"))
                .andExpect(content().string(urlService.getUbcq("x", null, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/ubcq", "/html", "/html-jpg", "/exp", "/exp-jpg" })
    void missingRequiredInputReturnsBadRequest(String endpoint) throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder followLink(String ubcq) {
        return get(java.net.URI.create("/html?ubcq="
                + java.net.URLEncoder.encode(ubcq, StandardCharsets.UTF_8)));
    }

    @Test
    void ubcqReturns503BeforePreparationWhenCapacityIsOccupied() throws Exception {
        var work = new ApiWorkService(new ApiWorkProperties(
                1, 1, 1, Duration.ofSeconds(5)));
        ReflectionTestUtils.setField(urlService, "work", work);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var active = caller.submit(() -> work.prepare(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    throw new IOException(interrupted);
                }
                return "done";
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                mvc.perform(get("/ubcq").param("formula", "x")).andExpect(status().isServiceUnavailable());
            } finally {
                release.countDown();
            }
            active.get(2, TimeUnit.SECONDS);
        }
        mvc.perform(get("/ubcq").param("formula", "x")).andExpect(status().isOk());
    }

    @Test
    void requestDeadlineCoversDecodingAndSubsequentRequestsRecover() throws Exception {
        var compression = mock(StringCompressionService.class);
        var worker = new AtomicReference<Thread>();
        when(compression.decompress(any())).thenAnswer(_ -> {
            worker.set(Thread.currentThread());
            new CountDownLatch(1).await();
            return "never";
        });
        ReflectionTestUtils.setField(urlService, "stringCompressionService", compression);
        var controller = new PermMathController();
        ReflectionTestUtils.setField(controller, "urlService", urlService);
        ReflectionTestUtils.setField(controller, "generatedImageService", renderer);
        var properties = new ApiWorkProperties(1, 1, 1, Duration.ofMillis(100));
        var timedMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new LocalExceptionHandler())
                .addFilters(new ApiDeadlineFilter(properties)).build();
        timedMvc.perform(get("/html").param("ubcq", "valid-but-stalled"))
                .andExpect(status().isGatewayTimeout());
        Assertions.assertNotNull(worker.get());
        worker.get().join(2000);
        ReflectionTestUtils.setField(urlService, "stringCompressionService", new StringCompressionService());
        timedMvc.perform(get("/ubcq").param("formula", "x")).andExpect(status().isOk());
    }

    @Test
    void imageRejectsMalformedAndInvalidDescriptorsBeforeRendering() throws Exception {
        assertAll(List.of("null", "{}", "{\"forumla\":\" \"}",
                "{\"forumla\":\"x\",\"imageWidth\":4001}",
                "{\"forumla\":\"x\",\"styleRefs\":[\"\"]}",
                "{\"forumla\":\"x\",\"styleRefs\":[" + String.join(",", Collections.nCopies(9, "\"a.css\"")) + "]}",
                "{\"forumla\":\"x\",\"style\":\"" + "s".repeat(InputLimits.MAX_STYLE_BYTES + 1) + "\"}",
                "{\"forumla\":\"" + "x".repeat(InputLimits.MAX_CONTENT_BYTES + 1) + "\"}",
                "{\"forumla\":\"x\"} {}",
                "{\"forumla\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}").stream()
            .<Executable>map(json -> () -> {
                mvc.perform(get("/html").param("ubcq", new StringCompressionService().compress(json)))
                        .andExpect(status().isBadRequest());
            }));
        assertAll(List.of("%%%", "AQIDBA==").stream()
            .<Executable>map(payload -> () -> {
                mvc.perform(get("/html").param("ubcq", payload)).andExpect(status().isBadRequest());
            }));
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void compressionBombReturnsBadRequestWithoutRendering() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write("x".repeat(InputLimits.MAX_JSON_BYTES + 1)
                    .getBytes(StandardCharsets.UTF_8));
        }
        mvc.perform(get("/html").param("ubcq", Base64.getEncoder().encodeToString(bytes.toByteArray())))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void directEndpointsRejectOversizedUtf8ContentAndStyles() throws Exception {
        mvc.perform(get("/exp").param("formula", "漢".repeat(InputLimits.MAX_FORMULA_BYTES / 3 + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/ubcq").param("formula", "x".repeat(
                InputLimits.MAX_CONTENT_BYTES + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/ubcq").param("formula", "x").param("style", "x".repeat(
                InputLimits.MAX_STYLE_BYTES + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/ubcq").param("formula", "x").param("styleRefs",
                Collections.nCopies(9, "https://example.com/a.css").toArray(String[]::new)))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(renderer);
    }
}
