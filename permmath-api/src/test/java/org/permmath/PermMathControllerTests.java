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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
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
    void jpegRoutesReturnBinaryImagesAndValidateParameters() throws Exception {
        byte[] jpeg = { (byte) 255, (byte) 216, (byte) 255, (byte) 217 };
        when(renderer.getExpJpg("x+y")).thenReturn(jpeg);
        mvc.perform(get("/exp-jpg").param("formula", "x+y"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpeg))
                .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"));
        String url = urlService.getImageUrl("<p>hello</p>", 500, null, null);
        String ubcq = URLDecoder.decode(URI.create(url).getRawQuery().substring(5), StandardCharsets.UTF_8);
        when(renderer.getHtmlJpg(any())).thenReturn(jpeg);
        mvc.perform(get("/html-jpg").param("ubcq", ubcq)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(jpeg));
        mvc.perform(get("/exp-jpg")).andExpect(status().isBadRequest());
        mvc.perform(get("/exp-jpg").param("formula", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/html-jpg")).andExpect(status().isBadRequest());
        mvc.perform(get("/html-jpg").param("ubcq", "invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void encodingPreservesTextWithoutRenderingOrCompression() throws Exception {
        var compression = mock(StringCompressionService.class);
        ReflectionTestUtils.setField(urlService, "stringCompressionService", compression);
        mvc.perform(get("/url-encode").param("text", "α + 50% & x/y?#=\n\t<script>"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("%CE%B1+%2B+50%25+%26+x%2Fy%3F%23%3D%0A%09%3Cscript%3E"))
                .andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"));
        Mockito.verifyNoInteractions(renderer, compression);
    }

    @Test
    void encodingAllowsEmptyTextAndDecodesTheIncomingQueryOnlyOnce() throws Exception {
        mvc.perform(get("/url-encode").param("text", "")).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(get("/url-encode").param("text", "  ")).andExpect(content().string("++"));
        mvc.perform(get(URI.create("/url-encode?text=%252F%20%2B%20%F0%9F%98%80")))
                .andExpect(status().isOk()).andExpect(content().string("%252F+%2B+%F0%9F%98%80"));
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void encodingBoundsUtf8InputAndWorstCaseOutputExpansion() throws Exception {
        int maximum = InputLimits.MAX_CONTENT_BYTES;
        var result = mvc.perform(get("/url-encode").param("text", "%".repeat(maximum)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Assertions.assertEquals(3 * maximum, result.length());
        assertTrue(result.startsWith("%25") && result.endsWith("%25"));
        assertAll(List.of("x".repeat(maximum + 1), "漢".repeat(maximum / 3 + 1)).stream()
            .<Executable>map(text -> () -> {
                mvc.perform(get("/url-encode").param("text", text)).andExpect(status().isBadRequest());
            }));
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void removedDecodeRoutesAreNotAvailable() throws Exception {
        assertAll(List.of("/decode", "/decode/", "/decode;ignored", "/decode-cq").stream()
            .<Executable>map(path -> () -> {
                mvc.perform(get(path).param("ubcq", "bad")).andExpect(status().isNotFound());
            }));
        Mockito.verifyNoInteractions(renderer);
    }

    @Test
    void encodingHonoursTheExistingRequestDeadline() throws Exception {
        try (var _ = WorkDeadline.open(Duration.ofNanos(1))) {
            mvc.perform(get("/url-encode").param("text", "x")).andExpect(status().isGatewayTimeout());
        }
        mvc.perform(get("/url-encode").param("text", "x")).andExpect(status().isOk());
        Mockito.verifyNoInteractions(renderer);
    }

    @BeforeEach
    void setUp() {
        PermMathProperties properties = new PermMathProperties("http://localhost:8086", 500, 2000.0f);
        urlService = new UrlService();
        ReflectionTestUtils.setField(urlService, "permMathProperties", properties);
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
        String link = urlService.getImageUrl("x", null, null, null);
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
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", "640"))
                .andExpect(status().isOk())
                .andExpect(content().string(urlService.getImageUrl("x", 640, null, null)));
    }


    @Test
    void generatedLinksPassTheSameContentToRenderer() throws Exception {
        String formula = "  <pre>α\n\t +  β = 50% & x # y?</pre>  ";
        List<String> refs = List.of("https://example.com/a.css?q=a+b%20c&v=1",
                "https://example.com/b.css");
        String style = "\npre::before { content: 'a  b'; }\n\tpre { width: 50%; }  ";
        String image = "<svg>α</svg>";
        when(renderer.getHtml(any())).thenReturn(image);

        String link = mvc.perform(get("/url").param("formula", formula)
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
        String link = urlService.getImageUrl("x\n\t +   y", null, List.of(), " \n ");

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
        mvc.perform(get("/url").param("formula", "x"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.startsWith("http://localhost:8086/html?ubcq=")))
                .andExpect(content().string(urlService.getImageUrl("x", null, null, null)));
        mvc.perform(get("/url").param("formula", "x").param("compressUrlQuery", "false"))
                .andExpect(content().string(urlService.getImageUrl("x", null, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/url", "/html", "/url-encode", "/exp" })
    void missingRequiredInputReturnsBadRequest(String endpoint) throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder followLink(String link) {
        URI uri = URI.create(link);
        MockHttpServletRequestBuilder request = get(uri.getPath());
        // MockMvc's URI overload does not apply servlet form-query decoding to '+'.
        Arrays.stream(uri.getRawQuery().split("&")).forEach(parameter -> {
            String[] parts = parameter.split("=", 2);
            request.param(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        });
        return request;
    }

    @Test
    void urlAndEncodingReturn503BeforePreparationWhenCapacityIsOccupied() throws Exception {
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
                mvc.perform(get("/url").param("formula", "x")).andExpect(status().isServiceUnavailable());
                mvc.perform(get("/url-encode").param("text", "x"))
                        .andExpect(status().isServiceUnavailable());
            } finally {
                release.countDown();
            }
            active.get(2, TimeUnit.SECONDS);
        }
        mvc.perform(get("/url").param("formula", "x")).andExpect(status().isOk());
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
        timedMvc.perform(get("/url").param("formula", "x")).andExpect(status().isOk());
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
        mvc.perform(get("/url").param("formula", "x".repeat(
                InputLimits.MAX_CONTENT_BYTES + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/url").param("formula", "x").param("style", "x".repeat(
                InputLimits.MAX_STYLE_BYTES + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/url").param("formula", "x").param("styleRefs",
                Collections.nCopies(9, "https://example.com/a.css").toArray(String[]::new)))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(renderer);
    }
}
