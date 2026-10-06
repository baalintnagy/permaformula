package org.permmath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PermMathApplicationTests {

    @Autowired
    private MockMvc mvc;


	@Test
	void contextLoads() {
	}

    @ParameterizedTest
    @ValueSource(ints = { 219, 4001 })
    void invalidImageWidthsAreRejected(int width) throws Exception {
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", Integer.toString(width)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/url"))
                .andExpect(jsonPath("$.violations[0].property").exists())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.stacktrace").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(ints = { 220, 4000 })
    void boundaryImageWidthsAreAccepted(int width) throws Exception {
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", Integer.toString(width)))
                .andExpect(status().isOk());
    }

    @Test
    void blankFormulaIsRejected() throws Exception {
        mvc.perform(get("/url").param("formula", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openApiDocumentIsAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.0.1"))
                .andExpect(jsonPath("$.paths['/img'].get.responses['200'].content['image/svg+xml']").exists())
                .andExpect(jsonPath("$.paths['/decode']").exists());
    }

    @Test
    void swaggerUiLoadsTheApiDefinition() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidCompressedInputReturnsBusinessRuleProblem() throws Exception {
        mvc.perform(get("/decode").param("ubcq", "%%%"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorType").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.instance").value("/decode"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void missingInputReturnsEnrichedMvcProblem() throws Exception {
        mvc.perform(get("/url"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/url"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void invalidWidthTypeReturnsMvcProblem() throws Exception {
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", "wide"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/url"));
    }

    @Test
    void formulaEndpointReturnsSvgWithoutStartingABrowser() throws Exception {
        mvc.perform(get("/img").param("formula", "sqrt(x^2 + y^2)"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("<svg")));
    }

    @Test
    void blankFormulaEndpointInputReturnsProblem() throws Exception {
        mvc.perform(get("/img").param("formula", " "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void missingFormulaEndpointInputReturnsProblem() throws Exception {
        mvc.perform(get("/img"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void renamedRoutesRequireCompressedInputAndOldRoutesAreRemoved() throws Exception {
        mvc.perform(get("/image").param("formula", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/decode").param("url", "http://localhost/image?formula=x")).andExpect(status().isBadRequest());
        mvc.perform(get("/image-cq")).andExpect(status().isNotFound());
        mvc.perform(get("/decode-cq")).andExpect(status().isNotFound());
        mvc.perform(get("/img").param("ubcq", "legacy-payload")).andExpect(status().isBadRequest());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/image']").exists())
                .andExpect(jsonPath("$.paths['/decode']").exists())
                .andExpect(jsonPath("$.paths['/image-cq']").doesNotExist())
                .andExpect(jsonPath("$.paths['/decode-cq']").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("compressUrlQuery"))));
    }

}
