INSERT INTO store VALUES (1, 'Магазин Учебный'), (2, 'Магазин Резервный');
INSERT INTO product VALUES ('MILK', 'Молоко 1 л'), ('BREAD', 'Хлеб 400 г');
INSERT INTO assortment VALUES
    (1, 'MILK', 9000, 5, 14),
    (1, 'BREAD', 5500, 3, 9),
    (2, 'MILK', 9200, 5, 14);
INSERT INTO batch VALUES
    (1, 1, 'MILK', '2026-11-25', 4),
    (2, 1, 'MILK', '2026-11-28', 3),
    (3, 1, 'MILK', '2026-12-01', 5),
    (4, 1, 'BREAD', '2026-11-27', 4),
    (5, 2, 'MILK', '2026-11-29', 7);
INSERT INTO inbound VALUES
    (1, 1, 'MILK', 2, 'CONFIRMED'),
    (2, 1, 'MILK', 10, 'CANCELLED');
