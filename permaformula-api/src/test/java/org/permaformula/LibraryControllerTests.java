package org.permaformula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.boava.exception.LocalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.permaformula.compression.StringCompressionService;
import org.permaformula.rendering.cache.JdbcImageCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class LibraryControllerTests {
    @Test
    void detailIncludesStoredImageDescriptorForEditorRestoration() throws Exception {
        var cache = mock(JdbcImageCache.class);
        String id = "a".repeat(64);
        String token = "stored-image-token";
        when(cache.libraryItem(id))
            .thenReturn(new JdbcImageCache.LibraryItem(id, "html",
                "{\"forumla\":\"<p>Saved</p>\"}", 1, token));
        var item = new LibraryController(provider(cache), new ObjectMapper())
            .detail(id)
            .getBody();
        assertEquals(token, item.ubcq());
        assertEquals("<p>Saved</p>", item.descriptor()
            .forumla());
    }

    @Test
    void shortByteLimitedPageUsesLastVisibleCursorAndHidesLookahead() throws Exception {
        var cache = mock(JdbcImageCache.class);
        String first = "b".repeat(64),
            next = "a".repeat(64);
        when(cache.library(null, 12))
            .thenReturn(List.of(
                new JdbcImageCache.LibrarySummary(first, "html", "x", 1000, null, "token",
                    false),
                new JdbcImageCache.LibrarySummary(next, "html", "x", 1000, null, null, true)));
        var page = new LibraryController(provider(cache), new ObjectMapper())
            .list(null, 0, 12)
            .getBody();
        assertEquals(1, page.items()
            .size());
        assertEquals("token", page.items()
            .getFirst()
            .ubcq());
        assertEquals("1000.%s".formatted(first),
            page.nextCursor());
    }

    @Test
    void listServesStoredUbcqWithoutExtraLinkPreparationAndOldImageRouteIsGone() throws Exception {
        var cache = mock(JdbcImageCache.class);
        String id = "a".repeat(64);
        String token = TestServices.validated(new StringCompressionService())
            .compress("{\"forumla\":\"x\"}");
        when(cache.library(null, 12))
            .thenReturn(
                List.of(new JdbcImageCache.LibrarySummary(id, "formula", "x", 1, null, token)));
        var controller = new LibraryController(provider(cache), new ObjectMapper());
        assertEquals(token, controller.list(null, 0, 12)
            .getBody()
            .items()
            .getFirst()
            .ubcq());
        verify(cache, never())
            .libraryItem(anyString());
        verify(cache, never())
            .find(anyString());
        MockMvcBuilders.standaloneSetup(controller)
            .build()
            .perform(MockMvcRequestBuilders.get("/library-svg")
                .param("id", id))
            .andExpect(MockMvcResultMatchers.status()
                .isNotFound());
    }

    private ObjectProvider<JdbcImageCache> provider(JdbcImageCache cache) {
        Map<String, Object> beans = cache == null ? Map.of()
            : Map.of("cache", cache);
        return new StaticListableBeanFactory(beans)
            .getBeanProvider(JdbcImageCache.class);
    }

    @Test
    void listsSummariesWithCursorAndRejectsUnboundedPages() throws Exception {
        var cache = mock(JdbcImageCache.class);
        String id = "a".repeat(64);
        when(cache.library(null, 12))
            .thenReturn(Collections.nCopies(13,
                new JdbcImageCache.LibrarySummary(id, "html", "<p>x</p>", 1)));
        var controller = new LibraryController(provider(cache), new ObjectMapper());
        var response = controller.list(null, 0, 12);
        assertEquals("no-store", response.getHeaders()
            .getFirst("Cache-Control"));
        assertEquals(12, response.getBody()
            .items()
            .size());
        assertEquals("<p>x</p>", response.getBody()
            .items()
            .getFirst()
            .excerpt());
        assertEquals("1.%s".formatted(id),
            response.getBody()
                .nextCursor());
        controller.list("1.%s".formatted(id), 0, 12);
        verify(cache)
            .library(new JdbcImageCache.LibraryCursor(1, id), 12);
        for (String cursor : List.of("", "-1.%s".formatted(id),
            "9999999999999999999.%s".formatted(id),
            "1.bad",
            "x".repeat(1000))) {
            assertThrows(ResponseStatusException.class, () -> controller.list(cursor, 0, 12));
        }
        var mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new LocalExceptionHandler())
            .build();
        for (String offset : List.of("-1", "1")) {
            mvc.perform(MockMvcRequestBuilders.get("/library-items")
                .param("offset", offset))
                .andExpect(MockMvcResultMatchers.status()
                    .isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.violations[0].property")
                    .value("offset"));
        }
        for (String limit : List.of("0", "25")) {
            mvc.perform(MockMvcRequestBuilders.get("/library-items")
                .param("limit", limit))
                .andExpect(MockMvcResultMatchers.status()
                    .isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.violations[0].property")
                    .value("limit"));
        }
        verify(cache, never())
            .libraryItem(anyString());
    }

    @Test
    void memoryOnlyLibraryIsEmptyAndMissingItemsReturn404() throws Exception {
        var controller = new LibraryController(provider(null), new ObjectMapper());
        assertTrue(controller.list(null, 0, 12)
            .getBody()
            .items()
            .isEmpty());
        assertNull(controller.list(null, 0, 12)
            .getBody()
            .nextCursor());
        assertEquals(404,
            assertThrows(ResponseStatusException.class, () -> controller.detail("a".repeat(64)))
                .getStatusCode()
                .value());
        var mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new LocalExceptionHandler())
            .build();
        mvc.perform(MockMvcRequestBuilders.get("/library-item")
            .param("id", "invalid"))
            .andExpect(MockMvcResultMatchers.status()
                .isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.violations[0].code")
                .value("CacheId"));
    }

    @Test
    void smallPagesIncludeDecodedSourcesAndLargePagesOmitTheDescriptorField() throws Exception {
        var cache = mock(JdbcImageCache.class);
        var mapper = new ObjectMapper();
        var controller = new LibraryController(provider(cache), mapper);
        String id = "a".repeat(64);
        when(cache.library(null, 12))
            .thenReturn(List.of(new JdbcImageCache.LibrarySummary(
                id, "html", "short excerpt", 1,
                "{\"forumla\":\"complete source\",\"style\":\"p {}\"}")));
        assertEquals("complete source",
            controller.list(null, 0, 12)
                .getBody()
                .items()
                .getFirst()
                .descriptor()
                .forumla());
        when(cache.library(null, 12))
            .thenReturn(List.of(new JdbcImageCache.LibrarySummary(id, "html", "short excerpt", 1)));
        assertFalse(mapper.writeValueAsString(controller.list(null, 0, 12)
            .getBody())
            .contains("descriptor"));
    }
}
