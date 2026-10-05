package org.permmath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.permmath.rendering.AsciiMathExecutor;

@SpringBootTest
@AutoConfigureMockMvc
class PermMathApplicationTests {

    // Context startup should not download the experimental MathJax script.
    @MockitoBean
    private AsciiMathExecutor asciiMathExecutor;

    @Autowired
    private MockMvc mvc;

	@Test
	void contextLoads() {
	}

    @ParameterizedTest
    @ValueSource(ints = { 219, 4001 })
    void invalidImageWidthsAreRejected(int width) throws Exception {
        mvc.perform(get("/url").param("formula", "x").param("imageWidth", Integer.toString(width)))
                .andExpect(status().isBadRequest());
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
                .andExpect(jsonPath("$.paths['/decode-cq']").exists());
    }

    @Test
    void swaggerUiLoadsTheApiDefinition() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

}
