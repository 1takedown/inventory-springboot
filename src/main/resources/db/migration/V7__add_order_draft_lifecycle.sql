ALTER TABLE orders ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED'
  CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED'));
ALTER TABLE orders ADD COLUMN confirmed_at TEXT;
ALTER TABLE orders ADD COLUMN confirmed_by TEXT;
ALTER TABLE orders ADD COLUMN cancelled_at TEXT;
ALTER TABLE orders ADD COLUMN cancelled_by TEXT;

UPDATE orders
SET confirmed_at = created_at,
    confirmed_by = created_by;

ALTER TABLE order_items ADD COLUMN removed_at TEXT;
ALTER TABLE order_items ADD COLUMN removed_by TEXT;

CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_order_items_active ON order_items(order_id, removed_at);
