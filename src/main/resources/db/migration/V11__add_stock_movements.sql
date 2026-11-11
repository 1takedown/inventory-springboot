CREATE TABLE stock_movements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id INTEGER REFERENCES items(id) ON DELETE SET NULL,
  item_id_snapshot INTEGER NOT NULL,
  title_snapshot TEXT NOT NULL,
  barcode_snapshot TEXT,
  delta INTEGER NOT NULL,
  quantity_before INTEGER NOT NULL CHECK (quantity_before BETWEEN 0 AND 2147483647),
  quantity_after INTEGER NOT NULL CHECK (quantity_after BETWEEN 0 AND 2147483647),
  reason TEXT NOT NULL CHECK (reason IN ('OPENING_STOCK', 'MIGRATION_BASELINE',
    'MANUAL_ADJUSTMENT', 'ORDER_RESERVED', 'ORDER_ITEM_REMOVED', 'ORDER_CANCELLED',
    'ORDER_RETURNED', 'ITEM_DELETED')),
  order_id INTEGER REFERENCES orders(id) ON DELETE RESTRICT,
  order_item_id INTEGER REFERENCES order_items(id) ON DELETE RESTRICT,
  order_return_id INTEGER REFERENCES order_returns(id) ON DELETE RESTRICT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by TEXT NOT NULL,
  CHECK (quantity_before + delta = quantity_after),
  CHECK (delta <> 0 OR reason IN ('OPENING_STOCK', 'MIGRATION_BASELINE'))
);

CREATE INDEX idx_stock_movements_item ON stock_movements(item_id_snapshot, id);
CREATE INDEX idx_stock_movements_reason ON stock_movements(reason, id);

INSERT INTO stock_movements
  (item_id, item_id_snapshot, title_snapshot, barcode_snapshot, delta,
   quantity_before, quantity_after, reason, created_by)
SELECT id, id, title, barcode, quantity, 0, quantity, 'MIGRATION_BASELINE', 'system:migration'
FROM items ORDER BY id;
