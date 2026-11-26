package retail;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static java.nio.charset.StandardCharsets.UTF_8;

@Configuration(proxyBeanMethods = false)
class InventoryDatabase {
    @Bean
    DataSource inventoryDataSource(@Value("${inventory.database}") String database,
                                   @Value("${inventory.seed-demo}") boolean seedDemo)
            throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + Path.of(database).toAbsolutePath());

        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (needsSchema(connection)) {
                    executeScript(connection, "schema.sql");
                    if (seedDemo) {
                        executeScript(connection, "demo.sql");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
        return source;
    }

    private boolean needsSchema(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'store'");
             var result = statement.executeQuery()) {
            return !result.next();
        }
    }

    private void executeScript(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(file), UTF_8));
    }
}
