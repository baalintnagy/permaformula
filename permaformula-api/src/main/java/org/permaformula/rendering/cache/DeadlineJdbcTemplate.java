package org.permaformula.rendering.cache;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;

import org.permaformula.rendering.WorkDeadline;
import org.springframework.jdbc.core.JdbcTemplate;

/** Apply the current caller's deadline to each statement without changing shared settings. */
final class DeadlineJdbcTemplate extends JdbcTemplate {
    DeadlineJdbcTemplate(DataSource source) {
        super(source);
    }

    @Override
    protected void applyStatementSettings(Statement statement) throws SQLException {
        super.applyStatementSettings(statement);
        long remaining = WorkDeadline.remainingNanos(Duration.ofSeconds(2));
        statement.setQueryTimeout((int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
    }
}
