package retail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryHttpTest {
    private static final String BASE = "/api/stores/1";
    private static final String DATE = "2026-11-26";

    @TempDir
    static Path temporaryDirectory;

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) {
        registry.add("inventory.database", () -> temporaryDirectory.resolve("http-test.sqlite3").toString());
        registry.add("inventory.seed-demo", () -> false);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = new JsonMapper();

    @BeforeEach
    void loadFixture() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_insert");
        for (String table : new String[]{"sale_allocation", "sale", "inbound", "batch",
                "assortment", "product", "store"}) {
            jdbc.update("DELETE FROM " + table);
        }
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("demo.sql"), UTF_8));
        }
    }

    @Test
    void saleAllocatesFefoAndUpdatesPlanning() throws Exception {
        var response = send(sale(BASE, "{\"MILK\":5,\"BREAD\":1}"));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode result = mapper.readTree(response.body());
        assertThat(result.path("totalKopecks").asInt()).isEqualTo(50500);
        assertThat(result.path("allocations").get(1).path("batchId").asInt()).isEqualTo(2);
        assertThat(result.path("allocations").get(2).path("quantity").asInt()).isEqualTo(2);

        JsonNode alerts = mapper.readTree(send(get(BASE + "/expiry-alerts?date=" + DATE)).body());
        assertThat(alerts.get(0).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(alerts.get(1).path("status").asText()).isEqualTo("EXPIRING");
        JsonNode replenishment = mapper.readTree(send(get(BASE + "/replenishment?date=" + DATE)).body());
        assertThat(replenishment.get(0).path("quantity").asInt()).isEqualTo(6);
        assertThat(replenishment.get(1).path("quantity").asInt()).isEqualTo(9);
        assertThat(send(get("/api/stores/2/replenishment?date=" + DATE)).body()).isEqualTo("[]");
    }

    @Test
    void shortageLeavesTheWholeBasketUntouched() throws Exception {
        assertThat(send(sale(BASE, "{\"BREAD\":1,\"MILK\":9}")).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM batch WHERE id = 4", Integer.class)).isEqualTo(4);
    }

    @Test
    void invalidRequestsAndUnknownStoreReturnClientErrors() throws Exception {
        assertThat(send(sale(BASE, "{}")).statusCode()).isEqualTo(400);
        assertThat(send(sale(BASE, "{\"MILK\":0}")).statusCode()).isEqualTo(400);
        assertThat(send(sale(BASE, "{\"MILK\":1.5}")).statusCode()).isEqualTo(400);
        assertThat(send(get(BASE + "/expiry-alerts?date=" + DATE + "&days=-1")).statusCode()).isEqualTo(400);
        assertThat(send(get("/api/stores/555/replenishment?date=" + DATE)).statusCode()).isEqualTo(404);
    }

    @Test
    void sqliteFailureRollsBackBothSaleAndStock() throws Exception {
        jdbc.execute("""
                CREATE TRIGGER reject_insert BEFORE INSERT ON sale_allocation
                BEGIN SELECT RAISE(ABORT, 'test write failure'); END
                """);
        assertThat(send(sale(BASE, "{\"MILK\":1}")).statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM batch WHERE id = 2", Integer.class)).isEqualTo(3);
    }

    @Test
    void concurrentSalesCannotOversell() {
        var codes = Stream.of(sale(BASE, "{\"MILK\":5}"), sale(BASE, "{\"MILK\":5}"))
                .map(request -> client.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
                .toList();
        assertThat(codes.stream().map(CompletableFuture::join).map(HttpResponse::statusCode))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale", Integer.class)).isEqualTo(1);
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }

    private HttpRequest sale(String base, String items) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + base + "/sales"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"date\":\"" + DATE + "\",\"items\":" + items + "}"))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
