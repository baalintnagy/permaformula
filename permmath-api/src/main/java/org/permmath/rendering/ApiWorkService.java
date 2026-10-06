package org.permmath.rendering;

import java.io.IOException;

import org.springframework.stereotype.Component;

@Component
public final class ApiWorkService {
    private final BoundedWork preparation;
    private final BoundedWork generation;
    private final BoundedWork fetch;

    public ApiWorkService(ApiWorkProperties properties) {
        preparation = new BoundedWork("preparation", properties.preparationConcurrency(), properties.timeout());
        generation = new BoundedWork("generation", properties.generationConcurrency(), properties.timeout());
        fetch = new BoundedWork("fetch", properties.fetchConcurrency(), properties.timeout());
    }

    public <T> T prepare(Operation<T> work) throws IOException {
        return preparation.execute(work);
    }
    public <T> T generate(Operation<T> work) throws IOException {
        return generation.execute(work);
    }
    public <T> T fetch(Operation<T> work) throws IOException {
        return fetch.execute(work);
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws IOException;
    }
}
