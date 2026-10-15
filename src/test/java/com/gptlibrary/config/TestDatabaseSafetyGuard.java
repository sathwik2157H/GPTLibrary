package com.gptlibrary.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Safety guard that fails fast if any integration test attempts to connect
 * to the production or development database ('gptlibrary').
 *
 * Runs as a BeanPostProcessor immediately when the DataSource bean is created,
 * blocking Flyway, Hibernate, and test cleanup before any query can be executed.
 */
@Component
@Slf4j
public class TestDatabaseSafetyGuard implements BeanPostProcessor {

    public static final String ISOLATED_TEST_DB_NAME = "gptlibrary_test";
    public static final String PROTECTED_DEV_DB_NAME = "gptlibrary";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            validateIsolatedTestDatabase(dataSource);
        }
        return bean;
    }

    /**
     * Inspects the DataSource connection to ensure it is connected to the isolated test database
     * and strictly NOT connected to the development database.
     */
    public static void validateIsolatedTestDatabase(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String catalog = conn.getCatalog();
            String url = conn.getMetaData().getURL();
            log.info("TestDatabaseSafetyGuard verified test datasource: catalog='{}', url='{}'", catalog, url);

            if (catalog == null && url != null) {
                catalog = extractDatabaseName(url);
            }

            if (PROTECTED_DEV_DB_NAME.equalsIgnoreCase(catalog) || isConnectedToDevDb(url)) {
                throw new IllegalStateException(
                        String.format("CRITICAL TEST SAFETY VIOLATION: Integration test attempted to connect to development database '%s' (url='%s')! "
                                        + "Integration tests must strictly run against the isolated '%s' database to prevent data destruction.",
                                catalog, url, ISOLATED_TEST_DB_NAME)
                );
            }

            if (!ISOLATED_TEST_DB_NAME.equalsIgnoreCase(catalog) && (url == null || !url.contains(ISOLATED_TEST_DB_NAME))) {
                throw new IllegalStateException(
                        String.format("TEST SAFETY VIOLATION: Expected integration test database '%s' but connected to '%s' (url='%s').",
                                ISOLATED_TEST_DB_NAME, catalog, url)
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect test database connection for safety validation: " + e.getMessage(), e);
        }
    }

    /**
     * Guard to be called before destructive operations (e.g. DELETE FROM vector_store or deleteAll)
     * to guarantee that the current database is the isolated test database.
     */
    public static void assertSafeForTestCleanup(JdbcTemplate jdbcTemplate) {
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        if (PROTECTED_DEV_DB_NAME.equalsIgnoreCase(currentDb) || !ISOLATED_TEST_DB_NAME.equalsIgnoreCase(currentDb)) {
            throw new IllegalStateException(
                    String.format("CRITICAL TEST SAFETY VIOLATION: Refusing to execute test cleanup on non-test database '%s'!", currentDb)
            );
        }
    }

    public static boolean isConnectedToDevDb(String url) {
        if (url == null) {
            return false;
        }
        // Match /gptlibrary followed by ? or end of string, but NOT /gptlibrary_test
        return url.matches(".*[/:](gptlibrary)(\\?.*)?$") && !url.contains(ISOLATED_TEST_DB_NAME);
    }

    private static String extractDatabaseName(String url) {
        if (url == null) return null;
        int qIdx = url.indexOf('?');
        String cleanUrl = qIdx > 0 ? url.substring(0, qIdx) : url;
        int slashIdx = cleanUrl.lastIndexOf('/');
        return slashIdx >= 0 ? cleanUrl.substring(slashIdx + 1) : null;
    }
}
