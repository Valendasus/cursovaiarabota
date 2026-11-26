"""Процедурная реализация операций магазина с хранением в SQLite."""

from datetime import date, timedelta
from pathlib import Path
import argparse
import json
import sqlite3

ROOT = Path(__file__).resolve().parent


def open_database(path):
    connection = sqlite3.connect(path, isolation_level=None)
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def load_demo(connection):
    for filename in ("schema.sql", "demo.sql"):
        connection.executescript((ROOT / filename).read_text(encoding="utf-8"))


def allocate_fefo(ordered_batches, quantity):
    """Построить план без изменения остатков."""
    if type(quantity) is not int or quantity <= 0:
        raise ValueError("Количество должно быть положительным целым")
    plan = []
    for batch_id, available in ordered_batches:
        taken = min(available, quantity)
        plan.append((batch_id, taken))
        quantity -= taken
        if quantity == 0:
            return plan
    raise ValueError("Недостаточно годного остатка")


def sell(connection, store_id, basket, day):
    if not basket:
        raise ValueError("Пустая корзина")
    date_key = day.isoformat()
    connection.execute("BEGIN IMMEDIATE")
    try:
        allocations = []
        for sku, quantity in sorted(basket.items()):
            assortment = connection.execute(
                """SELECT price_kopecks FROM assortment
                   WHERE store_id = ? AND sku = ?""",
                (store_id, sku),
            ).fetchone()
            if assortment is None:
                raise ValueError(f"Товар {sku} отсутствует в ассортименте")
            batches = connection.execute(
                """SELECT id, quantity FROM batch
                   WHERE store_id = ? AND sku = ?
                     AND expires_on > ? AND quantity > 0
                   ORDER BY expires_on, id""",
                (store_id, sku, date_key),
            )
            price = assortment[0]
            allocations.extend(
                (batch_id, amount, price)
                for batch_id, amount in allocate_fefo(batches, quantity)
            )

        sale_id = connection.execute(
            "INSERT INTO sale (store_id, sold_on) VALUES (?, ?)",
            (store_id, date_key),
        ).lastrowid
        for batch_id, amount, price in allocations:
            connection.execute(
                "UPDATE batch SET quantity = quantity - ? WHERE id = ?",
                (amount, batch_id),
            )
            connection.execute(
                "INSERT INTO sale_allocation VALUES (?, ?, ?, ?, ?)",
                (sale_id, store_id, batch_id, amount, price),
            )
        connection.commit()
    except (Exception, KeyboardInterrupt):
        connection.rollback()
        raise

    return {
        "sale_id": sale_id,
        "allocations": allocations,
        "total_kopecks": sum(amount * price for _, amount, price in allocations),
    }


def expiry_alerts(connection, store_id, day, window_days):
    if type(window_days) is not int or window_days < 0:
        raise ValueError("Горизонт должен быть неотрицательным целым")
    end = day + timedelta(days=window_days)
    return connection.execute(
        """SELECT id, sku, expires_on, quantity,
                  CASE WHEN expires_on <= ? THEN 'EXPIRED' ELSE 'EXPIRING' END
           FROM batch
           WHERE store_id = ? AND quantity > 0 AND expires_on <= ?
           ORDER BY expires_on, id""",
        (day.isoformat(), store_id, end.isoformat()),
    ).fetchall()


def replenishment(connection, store_id, day):
    return connection.execute(
        """WITH available AS (
               SELECT sku, SUM(quantity) AS quantity
               FROM batch
               WHERE store_id = :store AND expires_on > :day AND quantity > 0
               GROUP BY sku
           ), expected AS (
               SELECT sku, SUM(quantity) AS quantity
               FROM inbound
               WHERE store_id = :store AND status = 'CONFIRMED'
               GROUP BY sku
           ), position AS (
               SELECT a.sku, a.reorder_point, a.target_stock,
                      COALESCE(v.quantity, 0) AS available,
                      COALESCE(e.quantity, 0) AS expected
               FROM assortment AS a
               LEFT JOIN available AS v ON v.sku = a.sku
               LEFT JOIN expected AS e ON e.sku = a.sku
               WHERE a.store_id = :store
           )
           SELECT sku, available, expected, target_stock - available - expected
           FROM position
           WHERE available + expected <= reorder_point
           ORDER BY sku""",
        {"store": store_id, "day": day.isoformat()},
    ).fetchall()


def run_demo(path):
    if path != ":memory:" and Path(path).exists():
        raise FileExistsError(f"Выберите новый файл: {path}")
    connection = open_database(path)
    try:
        load_demo(connection)
        day = date(2026, 11, 26)
        sale = sell(connection, 1, {"MILK": 5, "BREAD": 1}, day)
        print(json.dumps({
            "sale": sale,
            "expiry_alerts": expiry_alerts(connection, 1, day, 2),
            "replenishment": replenishment(connection, 1, day),
        }, ensure_ascii=False, indent=2))
    finally:
        connection.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Учебный пример товарного учёта")
    parser.add_argument("--db", default=":memory:", help="Новый файл SQLite")
    run_demo(parser.parse_args().db)
