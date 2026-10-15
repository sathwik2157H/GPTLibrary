package com.gptlibrary.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDatabaseInitializerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "gptlibrary_test",
            "test_db_1",
            "_custom_test",
            "a",
            "TEST_DB",
            "db_1234567890_abcdefghijklmnopqrstuvwxyz"
    })
    void acceptsValidDatabaseNames(String validName) {
        assertThat(TestDatabaseInitializer.isValidDatabaseName(validName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123test",       // Starts with digit
            "test-db",       // Contains hyphen
            "test db",       // Contains space
            "",              // Empty
            "   ",           // Blank
            "test.db",       // Contains dot
            "test$db",       // Contains dollar sign
            "test/db"        // Contains slash
    })
    void rejectsInvalidDatabaseNames(String invalidName) {
        assertThat(TestDatabaseInitializer.isValidDatabaseName(invalidName)).isFalse();
    }

    @Test
    void rejectsNullOrOverlongDatabaseName() {
        assertThat(TestDatabaseInitializer.isValidDatabaseName(null)).isFalse();
        assertThat(TestDatabaseInitializer.isValidDatabaseName("a".repeat(64))).isFalse();
        assertThat(TestDatabaseInitializer.isValidDatabaseName("a".repeat(63))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gptlibrary_test; DROP TABLE users; --",
            "test' OR '1'='1",
            "test; SHUTDOWN;",
            "test/*comment*/db",
            "\"test_db\"",
            "test\0null",
            "test\nDROP DATABASE gptlibrary;"
    })
    void rejectsSqlInjectionLikeInputs(String sqlInjectionPayload) {
        assertThat(TestDatabaseInitializer.isValidDatabaseName(sqlInjectionPayload)).isFalse();
    }

    @Test
    void initializeThrowsIllegalArgumentExceptionOnInvalidDbName() {
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment mockEnv = mock(org.springframework.core.env.ConfigurableEnvironment.class);

        when(mockContext.getEnvironment()).thenReturn(mockEnv);
        when(mockEnv.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(mockEnv.getProperty("DB_TEST_NAME", TestDatabaseSafetyGuard.ISOLATED_TEST_DB_NAME))
                .thenReturn("test; DROP DATABASE gptlibrary; --");

        TestDatabaseInitializer initializer = new TestDatabaseInitializer();

        assertThatThrownBy(() -> initializer.initialize(mockContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid test database name")
                .hasMessageContaining("test; DROP DATABASE gptlibrary; --");
    }

    @Test
    void initializeThrowsIllegalStateExceptionIfTestDbMatchesDevelopmentDb() {
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment mockEnv = mock(org.springframework.core.env.ConfigurableEnvironment.class);

        when(mockContext.getEnvironment()).thenReturn(mockEnv);
        when(mockEnv.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(mockEnv.getProperty("DB_TEST_NAME", TestDatabaseSafetyGuard.ISOLATED_TEST_DB_NAME))
                .thenReturn("gptlibrary");

        TestDatabaseInitializer initializer = new TestDatabaseInitializer();

        assertThatThrownBy(() -> initializer.initialize(mockContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL TEST SAFETY VIOLATION")
                .hasMessageContaining("Test database name cannot be the development database 'gptlibrary'!");
    }

    @Test
    void initializeSkipsWhenTestProfileNotActive() {
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment mockEnv = mock(org.springframework.core.env.ConfigurableEnvironment.class);

        when(mockContext.getEnvironment()).thenReturn(mockEnv);
        when(mockEnv.getActiveProfiles()).thenReturn(new String[]{"prod"});

        TestDatabaseInitializer initializer = new TestDatabaseInitializer();
        // Should return immediately without querying or throwing
        initializer.initialize(mockContext);
    }
}
