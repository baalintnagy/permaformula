package org.permaformula.url;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.permaformula.TestServices;
import org.permaformula.compression.StringCompressionService;
import org.permaformula.rendering.ApiWorkProperties;
import org.permaformula.rendering.ApiWorkService;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class ImageDescriptorTests {

    @Test
    void previouslySerializedPermalinkContentStillDecodes() throws Exception {
        // This is the JSON shape emitted before the record conversion.
        String json =
            """
            {"forumla":"x + y = 50%","imageWidth":640,
             "styleRefs":["https://example.com/a.css"],"style":"width: 50%;"}
            """;
        StringCompressionService compression = TestServices
            .validated(new StringCompressionService());
        UrlService service = TestServices.validated(new UrlService(JsonMapper.builder()
            .build(),
            new StringCompressionService(), new ApiWorkService(ApiWorkProperties.defaults())));
        ReflectionTestUtils.setField(service, "stringCompressionService", compression);
        ReflectionTestUtils.setField(service, "objectMapper", JsonMapper.builder()
            .build());

        assertEquals(new ImageDescriptor("x + y = 50%", 640,
            List.of("https://example.com/a.css"),
            "width: 50%;"),
            service.decodeUbcq(compression.compress(json)));
    }

    @Test
    void stylesCannotBeChangedThroughTheOriginalListOrAccessor() {
        List<String> refs = new ArrayList<>(List.of("https://example.com/a.css"));
        ImageDescriptor descriptor = new ImageDescriptor("x", null, refs, null);
        refs.clear();

        assertEquals(List.of("https://example.com/a.css"),
            descriptor.styleRefs());
        assertThrows(UnsupportedOperationException.class,
            () -> descriptor.styleRefs()
                .add("https://example.com/b.css"));
    }

    @Test
    void nullFormulaIsStillRejected() {
        assertThrows(ConstraintViolationException.class,
            () -> new ImageDescriptor(null, null, null, null));
    }
}
