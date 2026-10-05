package io.github.markusluisflores.frontrow.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

class PostgresErrorsTest {

    @Test
    void findsTheStateAndConstraintOnADirectException() {
        PSQLException thrown = uniqueViolation("uq_claimed_seat");
        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(PostgresErrors.UNIQUE_VIOLATION);
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("uq_claimed_seat");
    }

    @Test
    void walksTheCauseChain() {
        Throwable wrapped =
                new RuntimeException("service", new IllegalStateException("jpa", uniqueViolation("uq_sold_once")));
        assertThat(PostgresErrors.constraintName(wrapped)).isEqualTo("uq_sold_once");
    }

    @Test
    void looksInsideABatchUpdateExceptionsNextException() {
        BatchUpdateException batch = new BatchUpdateException("batch failed", new int[] {1});
        batch.setNextException(uniqueViolation("uq_claimed_seat"));
        Throwable wrapped = new RuntimeException("service", batch);
        assertThat(PostgresErrors.constraintName(wrapped)).isEqualTo("uq_claimed_seat");
        assertThat(PostgresErrors.sqlState(wrapped)).isEqualTo(PostgresErrors.UNIQUE_VIOLATION);
    }

    @Test
    void returnsNullWhenNoPostgresExceptionIsPresent() {
        Throwable unrelated = new IllegalStateException("no database involved");
        assertThat(PostgresErrors.sqlState(unrelated)).isNull();
        assertThat(PostgresErrors.constraintName(unrelated)).isNull();
    }

    @Test
    void survivesAPostgresExceptionWithNoServerMessage() {
        PSQLException noServerMessage = new PSQLException("connection reset", PSQLState.CONNECTION_FAILURE);
        assertThat(PostgresErrors.sqlState(noServerMessage)).isEqualTo("08006");
        assertThat(PostgresErrors.constraintName(noServerMessage)).isNull();
    }

    @Test
    void doesNotLoopForeverOnASelfReferencingCause() {
        SQLException first = new SQLException("first");
        SQLException second = new SQLException("second", first);
        first.initCause(second);
        assertThat(PostgresErrors.constraintName(first)).isNull();
    }

    private static PSQLException uniqueViolation(String constraint) {
        String serverMessage = "SERROR\u0000C23505\u0000Mduplicate key value violates unique constraint\u0000n"
                + constraint + "\u0000";
        return new PSQLException(new ServerErrorMessage(serverMessage));
    }
}
