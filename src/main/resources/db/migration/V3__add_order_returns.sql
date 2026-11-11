ALTER TABLE order_items ADD COLUMN returned_quantity INTEGER NOT NULL DEFAULT 0
  CHECK (returned_quantity >= 0 AND returned_quantity <= quantity);

CREATE TABLE order_returns (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
  reason TEXT CHECK (reason IS NULL OR length(reason) <= 500),
  created_at TEXT DEFAULT CURRENT_TIMESTAMP NOT NULL,
  created_by TEXT NOT NULL
);

CREATE INDEX idx_order_returns_order ON order_returns(order_id);

CREATE TABLE order_return_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  return_id INTEGER NOT NULL REFERENCES order_returns(id) ON DELETE CASCADE,
  order_item_id INTEGER NOT NULL REFERENCES order_items(id) ON DELETE RESTRICT,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  refund_amount REAL NOT NULL CHECK (refund_amount >= 0),
  UNIQUE (return_id, order_item_id)
);

CREATE INDEX idx_order_return_items_return ON order_return_items(return_id);
CREATE INDEX idx_order_return_items_order_item ON order_return_items(order_item_id);
