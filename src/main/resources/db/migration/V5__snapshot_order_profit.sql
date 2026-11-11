ALTER TABLE order_items ADD COLUMN profit_snapshot REAL
  CHECK (profit_snapshot IS NULL OR profit_snapshot >= 0);
