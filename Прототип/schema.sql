PRAGMA foreign_keys = ON;

CREATE TABLE store (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE product (
    sku TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL
);

CREATE TABLE assortment (
    store_id INTEGER NOT NULL REFERENCES store(id),
    sku TEXT NOT NULL REFERENCES product(sku),
    price_kopecks INTEGER NOT NULL CHECK (price_kopecks > 0),
    reorder_point INTEGER NOT NULL CHECK (reorder_point >= 0),
    target_stock INTEGER NOT NULL CHECK (target_stock > reorder_point),
    PRIMARY KEY (store_id, sku)
);

CREATE TABLE batch (
    id INTEGER PRIMARY KEY,
    store_id INTEGER NOT NULL,
    sku TEXT NOT NULL,
    expires_on TEXT NOT NULL CHECK (
        date(expires_on, '+0 days') IS expires_on
    ),
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    FOREIGN KEY (store_id, sku) REFERENCES assortment(store_id, sku),
    UNIQUE (id, store_id)
);

CREATE INDEX batch_fefo ON batch(store_id, sku, expires_on, id)
WHERE quantity > 0;
CREATE INDEX batch_expiry ON batch(store_id, expires_on, id)
WHERE quantity > 0;

CREATE TABLE sale (
    id INTEGER PRIMARY KEY,
    store_id INTEGER NOT NULL REFERENCES store(id),
    sold_on TEXT NOT NULL CHECK (date(sold_on, '+0 days') IS sold_on),
    UNIQUE (id, store_id)
);

CREATE TABLE sale_allocation (
    sale_id INTEGER NOT NULL,
    store_id INTEGER NOT NULL,
    batch_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    price_kopecks INTEGER NOT NULL CHECK (price_kopecks > 0),
    PRIMARY KEY (sale_id, batch_id),
    FOREIGN KEY (sale_id, store_id) REFERENCES sale(id, store_id),
    FOREIGN KEY (batch_id, store_id) REFERENCES batch(id, store_id)
);

CREATE TABLE inbound (
    id INTEGER PRIMARY KEY,
    store_id INTEGER NOT NULL,
    sku TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL CHECK (
        status IN ('CONFIRMED', 'RECEIVED', 'CANCELLED')
    ),
    FOREIGN KEY (store_id, sku) REFERENCES assortment(store_id, sku)
);

CREATE INDEX inbound_confirmed ON inbound(store_id, sku)
WHERE status = 'CONFIRMED';
