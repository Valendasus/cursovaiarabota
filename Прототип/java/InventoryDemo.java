import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class InventoryDemo {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

    static final class Batch {
        final int id;
        final int store;
        final String sku;
        final LocalDate expiresOn;
        int quantity;

        Batch(int id, int store, String sku, String expiresOn, int quantity) {
            this.id = id;
            this.store = store;
            this.sku = sku;
            this.expiresOn = LocalDate.parse(expiresOn);
            this.quantity = quantity;
        }
    }

    static final class Assortment {
        final int price;
        final int reorderPoint;
        final int target;

        Assortment(int price, int reorderPoint, int target) {
            this.price = price;
            this.reorderPoint = reorderPoint;
            this.target = target;
        }
    }

    static final class Inbound {
        final int store;
        final String sku;
        final int quantity;
        final boolean confirmed;

        Inbound(int store, String sku, int quantity, boolean confirmed) {
            this.store = store;
            this.sku = sku;
            this.quantity = quantity;
            this.confirmed = confirmed;
        }
    }

    static final class Allocation {
        final Batch batch;
        final int quantity;
        final int price;

        Allocation(Batch batch, int quantity, int price) {
            this.batch = batch;
            this.quantity = quantity;
            this.price = price;
        }

        @Override
        public String toString() {
            return batch.id + ":" + quantity;
        }
    }

    interface AllocationPolicy {
        List<Allocation> plan(List<Batch> batches, int quantity, int price);
    }

    static abstract class InventoryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InventoryException(String message) {
            super(message);
        }
    }

    static final class InsufficientStock extends InventoryException {
        private static final long serialVersionUID = 1L;

        InsufficientStock() {
            super("Недостаточно годного остатка");
        }
    }

    static final class UnknownSku extends InventoryException {
        private static final long serialVersionUID = 1L;

        UnknownSku(String sku) {
            super("Товар " + sku + " отсутствует в ассортименте");
        }
    }

    private static final Comparator<Batch> EXPIRY_ORDER =
            Comparator.comparing((Batch batch) -> batch.expiresOn)
                    .thenComparingInt(batch -> batch.id);

    static final class FefoPolicy implements AllocationPolicy {
        @Override
        public List<Allocation> plan(List<Batch> batches, int quantity, int price) {
            if (quantity <= 0) {
                throw new IllegalArgumentException("Количество должно быть положительным");
            }
            List<Allocation> result = new ArrayList<>();
            for (Batch batch : batches.stream().sorted(EXPIRY_ORDER)
                    .collect(Collectors.toList())) {
                int taken = Math.min(quantity, batch.quantity);
                result.add(new Allocation(batch, taken, price));
                quantity -= taken;
                if (quantity == 0) {
                    return result;
                }
            }
            throw new InsufficientStock();
        }
    }

    static final class Inventory {
        private final Map<Integer, Map<String, Assortment>> assortments;
        private final List<Batch> batches;
        private final List<Inbound> inbound;
        private final AllocationPolicy allocationPolicy;

        Inventory(Map<Integer, Map<String, Assortment>> assortments,
                  List<Batch> batches, List<Inbound> inbound,
                  AllocationPolicy allocationPolicy) {
            this.assortments = assortments;
            this.batches = batches;
            this.inbound = inbound;
            this.allocationPolicy = allocationPolicy;
        }

        List<Allocation> sell(int store, Map<String, Integer> basket, LocalDate day) {
            if (basket.isEmpty()) {
                throw new IllegalArgumentException("Пустая корзина");
            }
            Map<String, Assortment> assortment = assortments.getOrDefault(store, Map.of());
            List<Allocation> result = new ArrayList<>();
            for (Map.Entry<String, Integer> line : new TreeMap<>(basket).entrySet()) {
                String sku = line.getKey();
                Assortment item = assortment.get(sku);
                if (item == null) {
                    throw new UnknownSku(sku);
                }
                List<Batch> available = batches.stream()
                        .filter(batch -> batch.store == store && batch.sku.equals(sku))
                        .filter(batch -> batch.quantity > 0 && batch.expiresOn.isAfter(day))
                        .collect(Collectors.toList());
                result.addAll(allocationPolicy.plan(available, line.getValue(), item.price));
            }
            result.forEach(row -> row.batch.quantity -= row.quantity);
            return List.copyOf(result);
        }

        List<String> alerts(int store, LocalDate day, int horizon) {
            if (horizon < 0) {
                throw new IllegalArgumentException("Отрицательный горизонт");
            }
            LocalDate end = day.plusDays(horizon);
            return batches.stream()
                    .filter(batch -> batch.store == store && batch.quantity > 0)
                    .filter(batch -> !batch.expiresOn.isAfter(end))
                    .sorted(EXPIRY_ORDER)
                    .map(batch -> batch.id + ":" + batch.sku + ":" + batch.expiresOn + ":"
                            + batch.quantity + ":"
                            + (batch.expiresOn.isAfter(day) ? "EXPIRING" : "EXPIRED"))
                    .collect(Collectors.toList());
        }

        List<String> replenishment(int store, LocalDate day) {
            Map<String, Long> available = batches.stream()
                    .filter(batch -> batch.store == store && batch.quantity > 0)
                    .filter(batch -> batch.expiresOn.isAfter(day))
                    .collect(Collectors.groupingBy(batch -> batch.sku,
                            Collectors.summingLong(batch -> batch.quantity)));
            Map<String, Long> expected = inbound.stream()
                    .filter(row -> row.store == store && row.confirmed)
                    .collect(Collectors.groupingBy(row -> row.sku,
                            Collectors.summingLong(row -> row.quantity)));

            List<String> result = new ArrayList<>();
            Map<String, Assortment> assortment = assortments.getOrDefault(store, Map.of());
            new TreeMap<>(assortment).forEach((sku, item) -> {
                long stock = available.getOrDefault(sku, 0L);
                long incoming = expected.getOrDefault(sku, 0L);
                long position = stock + incoming;
                if (position <= item.reorderPoint) {
                    result.add(sku + ":" + stock + ":" + incoming + ":" + (item.target - position));
                }
            });
            return result;
        }
    }

    static Inventory example() {
        return new Inventory(
                Map.of(1, Map.of("MILK", new Assortment(9000, 5, 14),
                                "BREAD", new Assortment(5500, 3, 9)),
                       2, Map.of("MILK", new Assortment(9200, 5, 14))),
                List.of(new Batch(1, 1, "MILK", "2026-11-25", 4),
                        new Batch(2, 1, "MILK", "2026-11-28", 3),
                        new Batch(3, 1, "MILK", "2026-12-01", 5),
                        new Batch(4, 1, "BREAD", "2026-11-27", 4),
                        new Batch(5, 2, "MILK", "2026-11-29", 7)),
                List.of(new Inbound(1, "MILK", 2, true),
                        new Inbound(1, "MILK", 10, false)),
                new FefoPolicy());
    }

    public static void main(String[] args) {
        Inventory inventory = example();
        List<Allocation> sale = inventory.sell(1, Map.of("MILK", 5, "BREAD", 1), DAY);
        long total = sale.stream().mapToLong(row -> (long) row.quantity * row.price).sum();
        System.out.println(sale);
        System.out.println(total);
        System.out.println(inventory.alerts(1, DAY, 2));
        System.out.println(inventory.replenishment(1, DAY));
    }
}
