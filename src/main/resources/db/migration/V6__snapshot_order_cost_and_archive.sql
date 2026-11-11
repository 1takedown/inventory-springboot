ALTER TABLE order_items ADD COLUMN cost_snapshot REAL
  CHECK (cost_snapshot IS NULL OR cost_snapshot >= 0);

ALTER TABLE orders ADD COLUMN archived_at TEXT;
ALTER TABLE orders ADD COLUMN archived_by TEXT;

CREATE INDEX idx_orders_archived_at ON orders(archived_at);
