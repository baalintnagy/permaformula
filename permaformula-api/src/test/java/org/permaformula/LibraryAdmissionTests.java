package org.permaformula;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.permaformula.rendering.cache.JdbcImageCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

class LibraryAdmissionTests {
    @RestController
    static class Ping {
        @GetMapping("/ping")
        String ping() {
            return "ok";
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void holdsPermitsThroughResponseWritingAndReleasesOnFailure() throws Exception {
        var provider = (ObjectProvider<JdbcImageCache>) mock(ObjectProvider.class);
        var writing = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        HttpMessageConverter<Object> converter = new HttpMessageConverter<>() {
            public boolean canRead(Class<?> type, MediaType media) {
                return false;
            }

            public boolean canWrite(Class<?> type, MediaType media) {
                return type == LibraryController.Page.class;
            }

            public List<MediaType> getSupportedMediaTypes() {
                return List.of(MediaType.APPLICATION_JSON);
            }

            public Object read(Class<?> type, HttpInputMessage message) {
                throw new UnsupportedOperationException();
            }

            public void write(Object value, MediaType media, HttpOutputMessage message)
                throws IOException {
                if (writes.incrementAndGet() <= 2) {
                    writing.countDown();
                    try {
                        assertTrue(finish.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        throw new IOException(failure);
                    }
                    throw new IOException("Simulated client disconnect during serialization");
                }
                message.getBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        var mvc = MockMvcBuilders
            .standaloneSetup(new LibraryController(provider, new ObjectMapper()), new Ping())
            .addInterceptors(new LibraryAdmissionConfiguration.Admission())
            .setMessageConverters(converter, new StringHttpMessageConverter()).build();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> mvc.perform(get("/library-items")));
            var second = workers.submit(() -> mvc.perform(get("/library-items")));
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                mvc.perform(get("/library-items")).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("Cache-Control", "no-store"));
                mvc.perform(get("/library-item").param("id", "a".repeat(64)))
                    .andExpect(status().isTooManyRequests());
                mvc.perform(get("/ping")).andExpect(status().isOk())
                    .andExpect(content().string("ok"));
            } finally {
                finish.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> first.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> second.get(5, TimeUnit.SECONDS));
        }
        mvc.perform(get("/library-items")).andExpect(status().isOk());
        mvc.perform(get("/library-items").param("cursor", "bad"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/library-items")).andExpect(status().isOk());
    }
}
