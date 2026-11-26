package retail;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryService {
    private static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    record Store(long id, String name) {}
    record Product(String sku, String name, long priceKopecks) {}
    record Batch(long id, long available) {}
    record Allocation(long batchId, String sku, int quantity, long priceKopecks) {}
    record Sale(long saleId, BigInteger totalKopecks, List<Allocation> allocations) {}
    record Alert(long batchId, String sku, LocalDate expiresOn, long quantity, String status) {}
    record Recommendation(String sku, long available, long expected, long quantity) {}

    static final class StoreNotFound extends RuntimeException {
        StoreNotFound(long store) {
            super("Магазин " + store + " отсутствует");
        }
    }

    static final class SaleRejected extends RuntimeException {
        SaleRejected(String reason) {
            super(reason);
        }
    }

    public List<Store> stores() {
        return jdbc.query("SELECT id, name FROM store ORDER BY id",
                (row, index) -> new Store(row.getLong("id"), row.getString("name")));
    }

    public List<Product> assortment(long storeId) {
        requireStore(storeId);
        return jdbc.query("""
                SELECT p.sku, p.name, a.price_kopecks
                FROM assortment a JOIN product p ON p.sku = a.sku
                WHERE a.store_id = ? ORDER BY p.name, p.sku
                """, (row, index) -> new Product(row.getString("sku"), row.getString("name"),
                row.getLong("price_kopecks")), storeId);
    }

    @Transactional
    public Sale sell(long storeId, LocalDate date, Map<String, Integer> items) {
        requireStore(storeId);
        String day = dateKey(date);
        List<Allocation> allocations = new ArrayList<>();
        for (var entry : new TreeMap<>(items).entrySet()) {
            addAllocations(allocations, storeId, day, entry.getKey(), entry.getValue());
        }

        long saleId = jdbc.queryForObject(
                "INSERT INTO sale(store_id, sold_on) VALUES (?, ?) RETURNING id",
                Long.class, storeId, day);
        BigInteger total = BigInteger.ZERO;
        for (Allocation row : allocations) {
            jdbc.update("UPDATE batch SET quantity = quantity - ? WHERE id = ?",
                    row.quantity(), row.batchId());
            jdbc.update("""
                    INSERT INTO sale_allocation(sale_id, store_id, batch_id, quantity, price_kopecks)
                    VALUES (?, ?, ?, ?, ?)
                    """, saleId, storeId, row.batchId(), row.quantity(), row.priceKopecks());
            total = total.add(BigInteger.valueOf(row.priceKopecks())
                    .multiply(BigInteger.valueOf(row.quantity())));
        }
        return new Sale(saleId, total, List.copyOf(allocations));
    }

    private void addAllocations(List<Allocation> plan, long storeId, String day, String sku, int quantity) {
        List<Long> prices = jdbc.queryForList("""
                SELECT price_kopecks FROM assortment WHERE store_id = ? AND sku = ?
                """, Long.class, storeId, sku);
        if (prices.isEmpty()) {
            throw new SaleRejected("Товар " + sku + " отсутствует в ассортименте");
        }
        long price = prices.getFirst();
        List<Batch> batches = jdbc.query("""
                SELECT id, quantity FROM batch
                WHERE store_id = ? AND sku = ? AND expires_on > ? AND quantity > 0
                ORDER BY expires_on, id
                """, (row, index) -> new Batch(row.getLong("id"), row.getLong("quantity")),
                storeId, sku, day);
        int remaining = quantity;
        for (Batch batch : batches) {
            int amount = (int) Math.min(remaining, batch.available());
            plan.add(new Allocation(batch.id(), sku, amount, price));
            remaining -= amount;
            if (remaining == 0) {
                return;
            }
        }
        throw new SaleRejected("Недостаточно годного остатка для " + sku);
    }

    public List<Alert> alerts(long storeId, LocalDate date, int horizon) {
        requireStore(storeId);
        String day = dateKey(date);
        LocalDate end = date.plusDays(Math.min((long) horizon, LAST_DATE.toEpochDay() - date.toEpochDay()));
        return jdbc.query("""
                SELECT id, sku, expires_on, quantity,
                       CASE WHEN expires_on <= ? THEN 'EXPIRED' ELSE 'EXPIRING' END AS status
                FROM batch
                WHERE store_id = ? AND quantity > 0 AND expires_on <= ?
                ORDER BY expires_on, id
                """, (row, index) -> new Alert(row.getLong("id"), row.getString("sku"),
                LocalDate.parse(row.getString("expires_on")), row.getLong("quantity"),
                row.getString("status")), day, storeId, end.toString());
    }

    public List<Recommendation> replenishment(long storeId, LocalDate date) {
        requireStore(storeId);
        return named.query("""
                WITH available AS (
                    SELECT sku, SUM(quantity) AS quantity FROM batch
                    WHERE store_id = :store AND expires_on > :day AND quantity > 0 GROUP BY sku
                ), expected AS (
                    SELECT sku, SUM(quantity) AS quantity FROM inbound
                    WHERE store_id = :store AND status = 'CONFIRMED' GROUP BY sku
                ), position AS (
                    SELECT a.sku, a.reorder_point, a.target_stock,
                           COALESCE(v.quantity, 0) AS available,
                           COALESCE(e.quantity, 0) AS expected
                    FROM assortment AS a
                    LEFT JOIN available AS v ON v.sku = a.sku
                    LEFT JOIN expected AS e ON e.sku = a.sku
                    WHERE a.store_id = :store
                )
                SELECT sku, available, expected, target_stock - available - expected AS quantity
                FROM position WHERE available + expected <= reorder_point ORDER BY sku
                """, Map.of("store", storeId, "day", dateKey(date)),
                (row, index) -> new Recommendation(row.getString("sku"),
                        row.getLong("available"), row.getLong("expected"), row.getLong("quantity")));
    }

    private void requireStore(long storeId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM store WHERE id = ?)", Boolean.class, storeId))) {
            throw new StoreNotFound(storeId);
        }
    }

    private String dateKey(LocalDate date) {
        if (date.getYear() < 1 || date.isAfter(LAST_DATE)) {
            throw new IllegalArgumentException("Дата должна иметь формат YYYY-MM-DD");
        }
        return date.toString();
    }
}
