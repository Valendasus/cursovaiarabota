import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class InventoryTest {
    private static void check(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Ожидалось " + expected + ", получено " + actual);
        }
    }

    public static void main(String[] args) {
        LocalDate day = LocalDate.of(2026, 11, 26);
        InventoryDemo.Inventory inventory = InventoryDemo.example();

        try {
            inventory.sell(1, Map.of("BREAD", 1, "MILK", 9), day);
            throw new AssertionError("Продажа при нехватке должна быть отклонена");
        } catch (InventoryDemo.InsufficientStock expected) {
            check(List.of("1:MILK:2026-11-25:4:EXPIRED",
                          "4:BREAD:2026-11-27:4:EXPIRING"),
                  inventory.alerts(1, day, 1));
        }

        List<InventoryDemo.Allocation> sale =
                inventory.sell(1, Map.of("MILK", 5, "BREAD", 1), day);
        check("[4:1, 2:3, 3:2]", sale.toString());
        check(50500L, sale.stream().mapToLong(row -> (long) row.quantity * row.price).sum());
        check(List.of("BREAD:3:0:6", "MILK:3:2:9"), inventory.replenishment(1, day));
        check(List.of(), inventory.replenishment(2, day));

        InventoryDemo.Inventory boundary = InventoryDemo.example();
        check("[3:1]", boundary.sell(1, Map.of("MILK", 1), day.plusDays(2)).toString());
        System.out.println("Java: 6 проверок пройдены");
    }
}
