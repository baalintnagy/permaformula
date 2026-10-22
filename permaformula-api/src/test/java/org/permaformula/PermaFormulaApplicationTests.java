package org.permaformula;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.permaformula.compression.StringCompressionService;
import org.permaformula.rendering.RenderGatewayTestConfiguration;
import org.permaformula.url.ImageDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(RenderGatewayTestConfiguration.class)
@AutoConfigureMockMvc
class PermaFormulaApplicationTests {
    @Test
    void invalidPresentationInputIsRejectedForBothFormats() throws Exception {
        var compression = new StringCompressionService();
        for (String html : List.of("<p onclick='alert(1)'>hello</p>",
            "<p>hello</p><script>alert(1)</script>",
            "<p style='background:url(https://example.com/x)'>hello</p>")) {
            String token = compression.compress(JsonMapper.builder()
                .build()
                .writeValueAsString(new ImageDescriptor(html, 220, null, null)));
            for (String route : List.of("/html-svg", "/html-jpg")) {
                mvc.perform(get(route)
                    .param("ubcq", token))
                    .andExpect(status()
                        .isBadRequest())
                    .andExpect(jsonPath("$.violations[0].message")
                        .isString())
                    .andExpect(jsonPath("$.violations[0].invalidValue")
                        .doesNotExist());
            }
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void contextLoads() {
    }

    @ParameterizedTest
    @ValueSource(ints = { 219, 4001 })
    void invalidImageWidthsAreRejected(int width) throws Exception {
        mvc.perform(get("/ubcq")
            .param("formula", "x")
            .param("imageWidth", Integer.toString(width)))
            .andExpect(status()
                .isBadRequest())
            .andExpect(content()
                .contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status")
                .value(400))
            .andExpect(jsonPath("$.instance")
                .value("/ubcq"))
            .andExpect(jsonPath("$.violations[0].property")
                .exists())
            .andExpect(jsonPath("$.id")
                .isString())
            .andExpect(jsonPath("$.timestamp")
                .isNumber())
            .andExpect(jsonPath("$.stacktrace")
                .doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(ints = { 220, 4000 })
    void boundaryImageWidthsAreAccepted(int width) throws Exception {
        mvc.perform(get("/ubcq")
            .param("formula", "x")
            .param("imageWidth", Integer.toString(width)))
            .andExpect(status()
                .isOk());
    }

    @Test
    void blankFormulaIsRejected() throws Exception {
        mvc.perform(get("/ubcq")
            .param("formula", " "))
            .andExpect(status()
                .isBadRequest());
    }

    @Test
    void openApiDocumentIsAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status()
                .isOk())
            .andExpect(jsonPath("$.openapi")
                .value("3.0.1"))
            .andExpect(jsonPath("$.paths['/exp-svg'].get.responses['200'].content['image/svg+xml']")
                .exists())
            .andExpect(
                jsonPath("$.paths['/ubcq'].get.responses['200'].content['text/plain']")
                    .exists())
            .andExpect(jsonPath("$.paths['/decode']")
                .doesNotExist());
    }

    @Test
    void htmlSwaggerExampleContainsOnlyASimpleFormula() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
            .andExpect(status()
                .isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        var mapper = JsonMapper.builder()
            .build();
        var parameters = mapper.readTree(json)
            .at("/paths/~1html-svg/get/parameters");
        var parameter = parameters.get(0);
        Assertions.assertEquals("ubcq", parameter.get("name")
            .asString());
        var decoded = TestServices.validated(new StringCompressionService())
            .decompress(parameter.get("example")
                .asString());
        var descriptor = mapper.readValue(decoded, ImageDescriptor.class);
        Assertions.assertEquals(
            new ImageDescriptor("`x^2`", 220, null, null), descriptor);
    }

    @Test
    void everyEndpointHasDocumentedParametersExamplesAndResponseTypes() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
            .andExpect(status()
                .isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        var document = JsonMapper.builder()
            .build()
            .readTree(json);
        for (String path : List.of("/exp-svg", "/exp-jpg", "/html-svg", "/html-jpg",
            "/ubcq", "/ubcq-decode")) {
            var operation = document.path("paths")
                .path(path)
                .path("get");
            Assertions.assertFalse(operation.path("summary")
                .asString()
                .isBlank(), path);
            Assertions.assertFalse(operation.path("description")
                .asString()
                .isBlank(), path);
            for (var parameter : operation.path("parameters")) {
                Assertions.assertFalse(parameter.path("description")
                    .asString()
                    .isBlank(),
                    "%s %s".formatted(path, parameter.path("name")));
                Assertions.assertFalse(parameter.path("example")
                    .isMissingNode(),
                    "%s %s".formatted(path, parameter.path("name")));
            }
            for (String status : List.of("400", "429", "503", "504")) {
                Assertions.assertFalse(operation.path("responses")
                    .path(status)
                    .path("content")
                    .path("application/problem+json")
                    .isMissingNode(),
                    "%s %s".formatted(
                        path,
                        status));
            }
        }
        for (String path : List.of("/exp-jpg", "/html-jpg")) {
            var schema = document.path("paths")
                .path(path)
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("image/jpeg")
                .path("schema");
            Assertions.assertEquals("binary", schema.path("format")
                .asString());
        }
        var descriptorSchema = document.path("components")
            .path("schemas")
            .path("ImageDescriptor")
            .path("properties");
        for (String field : List.of("forumla", "imageWidth", "styleRefs", "style")) {
            Assertions.assertFalse(
                descriptorSchema.path(field)
                    .path("description")
                    .asString()
                    .isBlank(),
                field);
        }
        String decoded = mvc.perform(get("/ubcq-decode")
            .param("ubcq", ApiExamples.UBCQ))
            .andExpect(status()
                .isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Assertions.assertEquals(
            document.path("paths")
                .path("/ubcq-decode")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples")
                .path("simpleFormula")
                .path("value"),
            JsonMapper.builder()
                .build()
                .readTree(decoded));
    }

    @Test
    void librarySwaggerDocumentsExamplesPaginationAndImageDelivery() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
            .andExpect(status()
                .isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        var document = JsonMapper.builder()
            .build()
            .readTree(json);
        for (String path : List.of("/library-items", "/library-item")) {
            var operation = document.path("paths")
                .path(path)
                .path("get");
            Assertions.assertFalse(operation.path("summary")
                .asString()
                .isBlank(), path);
            Assertions.assertFalse(operation.path("description")
                .asString()
                .isBlank(), path);
            for (var parameter : operation.path("parameters")) {
                Assertions.assertFalse(parameter.path("description")
                    .asString()
                    .isBlank(), path);
                Assertions.assertFalse(parameter.path("example")
                    .isMissingNode(), path);
            }
            for (String status : List.of("400", "429", "500")) {
                Assertions.assertFalse(operation.path("responses")
                    .path(status)
                    .path("content")
                    .path("application/problem+json")
                    .isMissingNode(),
                    "%s %s".formatted(
                        path,
                        status));
            }
        }
        var page = document.at(
            """
            /paths/~1library-items/get/responses/200/content/application~1json/examples/\
            mixedPage/value\
            """
        );
        Assertions.assertEquals(2, page.path("items")
            .size());
        Assertions.assertEquals("formula", page.path("items")
            .get(0)
            .path("kind")
            .asString());
        Assertions.assertEquals("html", page.path("items")
            .get(1)
            .path("kind")
            .asString());
        Assertions.assertEquals(500,
            page.path("items")
                .get(1)
                .path("descriptor")
                .path("imageWidth")
                .asInt());
        Assertions.assertEquals(0, document.at(
            """
            /paths/~1library-items/get/responses/200/content/application~1json/examples/\
            emptyPage/value/items\
            """
        )
            .size());
        var fields = document.at("/components/schemas/Item/properties");
        Assertions.assertEquals("#/components/schemas/ImageDescriptor",
            fields.path("descriptor")
                .path("$ref")
                .asString());
        Assertions.assertFalse(
            document.at("/components/schemas/ImageDescriptor/description")
                .asString()
                .isBlank());
        for (String field : List.of("id", "kind", "createdAt")) {
            Assertions.assertFalse(fields.path(field)
                .path("description")
                .asString()
                .isBlank(),
                field);
        }
    }

    @Test
    void swaggerUiLoadsTheApiDefinition() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
            .andExpect(status()
                .isOk())
            .andExpect(jsonPath("$.url")
                .value("/v3/api-docs"));
        mvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status()
                .isOk());
    }

    @Test
    void invalidCompressedInputReturnsBusinessRuleProblem() throws Exception {
        mvc.perform(get("/html-svg")
            .param("ubcq", "%%%"))
            .andExpect(status()
                .isBadRequest())
            .andExpect(content()
                .contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status")
                .value(400))
            .andExpect(jsonPath("$.errorType")
                .value("BUSINESS_RULE_VIOLATION"))
            .andExpect(jsonPath("$.instance")
                .value("/html-svg"))
            .andExpect(jsonPath("$.id")
                .isString())
            .andExpect(jsonPath("$.timestamp")
                .isNumber());
    }

    @Test
    void missingInputReturnsEnrichedMvcProblem() throws Exception {
        mvc.perform(get("/ubcq"))
            .andExpect(status()
                .isBadRequest())
            .andExpect(content()
                .contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status")
                .value(400))
            .andExpect(jsonPath("$.instance")
                .value("/ubcq"))
            .andExpect(jsonPath("$.id")
                .isString())
            .andExpect(jsonPath("$.timestamp")
                .isNumber());
    }

    @Test
    void invalidWidthTypeReturnsMvcProblem() throws Exception {
        mvc.perform(get("/ubcq")
            .param("formula", "x")
            .param("imageWidth", "wide"))
            .andExpect(status()
                .isBadRequest())
            .andExpect(content()
                .contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status")
                .value(400))
            .andExpect(jsonPath("$.instance")
                .value("/ubcq"));
    }

    @Test
    void formulaEndpointReturnsSvgWithoutStartingABrowser() throws Exception {
        mvc.perform(get("/exp-svg")
            .param("formula", "sqrt(x^2 + y^2)"))
            .andExpect(status()
                .isOk())
            .andExpect(content()
                .contentTypeCompatibleWith("image/svg+xml"))
            .andExpect(content()
                .string(Matchers.startsWith("<svg")));
    }

    @Test
    void blankFormulaEndpointInputReturnsProblem() throws Exception {
        mvc.perform(get("/exp-svg")
            .param("formula", " "))
            .andExpect(status()
                .isBadRequest())
            .andExpect(content()
                .contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void missingFormulaEndpointInputReturnsProblem() throws Exception {
        mvc.perform(get("/exp-svg"))
            .andExpect(status()
                .isBadRequest())
            .andExpect(content()
                .contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void renamedRoutesRequireCompressedInputAndOldRoutesAreRemoved() throws Exception {
        mvc.perform(get("/exp")
            .param("formula", "x"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/html")
            .param("ubcq", "legacy-payload"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/html-svg")
            .param("formula", "x"))
            .andExpect(status()
                .isBadRequest());
        mvc.perform(get("/decode")
            .param("url", "http://localhost/html-svg?formula=x"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/img")
            .param("formula", "x"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/image")
            .param("ubcq", "legacy-payload"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/image-cq"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/decode-cq"))
            .andExpect(status()
                .isNotFound());
        mvc.perform(get("/exp-svg")
            .param("ubcq", "legacy-payload"))
            .andExpect(status()
                .isBadRequest());
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['/html-svg']")
                .exists())
            .andExpect(jsonPath("$.paths['/exp-svg']")
                .exists())
            .andExpect(jsonPath("$.paths['/exp']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/html']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/img']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/image']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/url-encode']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/url']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/decode']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/image-cq']")
                .doesNotExist())
            .andExpect(jsonPath("$.paths['/decode-cq']")
                .doesNotExist())
            .andExpect(content()
                .string(Matchers.not(Matchers.containsString("compressUrlQuery"))));
    }

}
