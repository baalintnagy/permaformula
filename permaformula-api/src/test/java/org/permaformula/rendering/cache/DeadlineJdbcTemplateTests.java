package org.permaformula.rendering.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.permaformula.rendering.WorkDeadline;
import org.springframework.web.server.ResponseStatusException;

class DeadlineJdbcTemplateTests {
    @Test
    void eachStatementUsesItsCallersDeadlineWithoutChangingTheSharedTemplate() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var shortQuery = mock(PreparedStatement.class);
        var defaultQuery = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(shortQuery, defaultQuery);
        var jdbc = new DeadlineJdbcTemplate(source);
        try (var _ = WorkDeadline.open(Duration.ofMillis(500))) {
            jdbc.update("DELETE FROM permaformula_image_cache WHERE cache_key = ?", "short");
        }
        jdbc.update("DELETE FROM permaformula_image_cache WHERE cache_key = ?", "default");
        verify(shortQuery).setQueryTimeout(1);
        verify(defaultQuery).setQueryTimeout(2);
        verify(shortQuery).close();
        verify(defaultQuery).close();
        verify(connection, times(2)).close();
    }

    @Test
    void deadlineExpiringDuringConnectionAcquisitionClosesResourcesWithoutExecuting()
        throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        when(source.getConnection()).thenAnswer(_ -> {
            Thread.sleep(50);
            return connection;
        });
        when(connection.prepareStatement(anyString())).thenReturn(query);
        var jdbc = new DeadlineJdbcTemplate(source);
        try (var _ = WorkDeadline.open(Duration.ofMillis(10))) {
            var failure = assertThrows(ResponseStatusException.class,
                () -> jdbc.update("DELETE FROM permaformula_image_cache WHERE cache_key = ?",
                    "late"));
            assertEquals(504, failure.getStatusCode().value());
        }
        verify(query, never()).executeUpdate();
        verify(query).close();
        verify(connection).close();
    }
}
