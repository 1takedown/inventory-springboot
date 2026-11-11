ALTER TABLE orders ADD COLUMN order_date TEXT;

UPDATE orders
SET order_date = date(created_at, '+8 hours')
WHERE order_date IS NULL;

CREATE INDEX idx_orders_order_date ON orders(order_date);
