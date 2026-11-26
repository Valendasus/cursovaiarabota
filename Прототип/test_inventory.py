from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Barrier
import sqlite3
import unittest

from inventory import expiry_alerts, load_demo, open_database, replenishment, sell

DAY = date(2026, 11, 26)


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.db = open_database(":memory:")
        self.addCleanup(self.db.close)
        load_demo(self.db)

    def test_fefo_cart_and_price_snapshot(self):
        result = sell(self.db, 1, {"MILK": 5, "BREAD": 1}, DAY)
        self.assertEqual([(4, 1, 5500), (2, 3, 9000), (3, 2, 9000)],
                         result["allocations"])
        self.assertEqual(50500, result["total_kopecks"])
        self.db.execute("UPDATE assortment SET price_kopecks = 9300 WHERE sku = 'MILK'")
        prices = self.db.execute(
            "SELECT DISTINCT price_kopecks FROM sale_allocation ORDER BY 1"
        ).fetchall()
        self.assertEqual([(5500,), (9000,)], prices)

    def test_insufficient_stock_leaves_whole_cart_unchanged(self):
        with self.assertRaisesRegex(ValueError, "Недостаточно"):
            sell(self.db, 1, {"BREAD": 1, "MILK": 9}, DAY)
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM sale").fetchone()[0])
        self.assertEqual(4, self.db.execute("SELECT quantity FROM batch WHERE id = 4").fetchone()[0])

    def test_expiry_day_and_alert_boundary(self):
        self.db.execute("UPDATE batch SET expires_on = '2026-11-26' WHERE id = 2")
        result = sell(self.db, 1, {"MILK": 1}, DAY)
        self.assertEqual([(3, 1, 9000)], result["allocations"])
        self.assertEqual([(1, "MILK", "2026-11-25", 4, "EXPIRED"),
                          (2, "MILK", "2026-11-26", 3, "EXPIRED"),
                          (4, "BREAD", "2026-11-27", 4, "EXPIRING")],
                         expiry_alerts(self.db, 1, DAY, 1))
        self.assertEqual(expiry_alerts(self.db, 1, date.max, 0),
                         expiry_alerts(self.db, 1, date.max, 1))

    def test_replenishment_includes_confirmed_only(self):
        sell(self.db, 1, {"BREAD": 1, "MILK": 5}, DAY)
        self.assertEqual([("BREAD", 3, 0, 6), ("MILK", 3, 2, 9)],
                         replenishment(self.db, 1, DAY))
        self.assertEqual([], replenishment(self.db, 2, DAY))

    def test_fails_for_invalid_input(self):
        for basket in ({}, {"MILK": 0}, {"MILK": -1}, {"MILK": True}, {"EGG": 1}):
            with self.subTest(basket=basket), self.assertRaises(ValueError):
                sell(self.db, 1, basket, DAY)
        with self.assertRaises(ValueError):
            expiry_alerts(self.db, 1, DAY, -1)

    def test_database_constraints(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE batch SET quantity = -1 WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE batch SET expires_on = '2026-02-30' WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO inbound VALUES (3, 2, 'BREAD', 5, 'CONFIRMED')")
        sale_id = sell(self.db, 1, {"MILK": 1}, DAY)["sale_id"]
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO sale_allocation VALUES (?, 1, 5, 1, 9000)",
                            (sale_id,))

    def test_failed_database_write_rolls_back_stock_and_receipt(self):
        self.db.execute("""CREATE TRIGGER reject_allocation BEFORE INSERT ON sale_allocation
                           BEGIN SELECT RAISE(ABORT, 'demo failure'); END""")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "demo failure"):
            sell(self.db, 1, {"MILK": 1}, DAY)
        self.assertEqual(3, self.db.execute("SELECT quantity FROM batch WHERE id = 2").fetchone()[0])
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM sale").fetchone()[0])

    def test_concurrent_checkout_cannot_oversell(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.sqlite3"
            connection = open_database(path)
            load_demo(connection)
            connection.close()
            start = Barrier(2, timeout=5)

            def buy():
                connection = open_database(path)
                try:
                    start.wait()
                    sell(connection, 1, {"MILK": 5}, DAY)
                    return True
                except ValueError:
                    return False
                finally:
                    connection.close()

            with ThreadPoolExecutor(2) as pool:
                attempts = [pool.submit(buy) for _ in range(2)]
                self.assertEqual([False, True], sorted(job.result() for job in attempts))


if __name__ == "__main__":
    unittest.main()
