package com.gptlibrary.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDatabaseSafetyGuardTest {

    @Test
    void rejectsConnectionWhenCatalogIsDevelopmentDatabase() throws SQLException {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.getCatalog()).thenReturn("gptlibrary");
        when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getURL()).thenReturn("jdbc:postgresql://localhost:5432/gptlibrary");

        assertThatThrownBy(() -> TestDatabaseSafetyGuard.validateIsolatedTestDatabase(mockDs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL TEST SAFETY VIOLATION")
                .hasMessageContaining("development database 'gptlibrary'");
    }

    @Test
    void rejectsConnectionWhenUrlTargetsDevelopmentDatabaseEvenWithNullCatalog() throws SQLException {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.getCatalog()).thenReturn(null);
        when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getURL()).thenReturn("jdbc:postgresql://localhost:5432/gptlibrary");

        assertThatThrownBy(() -> TestDatabaseSafetyGuard.validateIsolatedTestDatabase(mockDs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL TEST SAFETY VIOLATION");
    }

    @Test
    void rejectsConnectionWhenTargetDatabaseIsNeitherDevNorTest() throws SQLException {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.getCatalog()).thenReturn("random_db");
        when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getURL()).thenReturn("jdbc:postgresql://localhost:5432/random_db");

        assertThatThrownBy(() -> TestDatabaseSafetyGuard.validateIsolatedTestDatabase(mockDs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected integration test database 'gptlibrary_test' but connected to 'random_db'");
    }

    @Test
    void allowsConnectionWhenCatalogIsIsolatedTestDatabase() throws SQLException {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.getCatalog()).thenReturn("gptlibrary_test");
        when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getURL()).thenReturn("jdbc:postgresql://localhost:5432/gptlibrary_test");

        // Should execute smoothly without throwing any exception
        TestDatabaseSafetyGuard.validateIsolatedTestDatabase(mockDs);
    }

    @Test
    void assertSafeForTestCleanupFailsIfConnectedToDevDb() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        when(mockJdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("gptlibrary");

        assertThatThrownBy(() -> TestDatabaseSafetyGuard.assertSafeForTestCleanup(mockJdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL TEST SAFETY VIOLATION")
                .hasMessageContaining("Refusing to execute test cleanup on non-test database 'gptlibrary'!");
    }

    @Test
    void assertSafeForTestCleanupSucceedsWhenConnectedToTestDb() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        when(mockJdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("gptlibrary_test");

        // Should execute without throwing
        TestDatabaseSafetyGuard.assertSafeForTestCleanup(mockJdbc);
    }

    @Test
    void isConnectedToDevDbIdentifiesDevelopmentDatabaseUrlsAccurately() {
        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb("jdbc:postgresql://localhost:5432/gptlibrary")).isTrue();
        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb("jdbc:postgresql://localhost:5432/gptlibrary?sslmode=disable")).isTrue();
        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb("jdbc:postgresql://db:5432/gptlibrary")).isTrue();

        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb("jdbc:postgresql://localhost:5432/gptlibrary_test")).isFalse();
        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb("jdbc:postgresql://localhost:5432/gptlibrary_test?sslmode=disable")).isFalse();
        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb("jdbc:postgresql://localhost:5432/postgres")).isFalse();
        assertThat(TestDatabaseSafetyGuard.isConnectedToDevDb(null)).isFalse();
    }
}
