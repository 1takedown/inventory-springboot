ALTER TABLE items ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE order_items ADD COLUMN title_snapshot TEXT NOT NULL DEFAULT '';
ALTER TABLE order_items ADD COLUMN barcode_snapshot TEXT;

UPDATE order_items
SET title_snapshot = COALESCE((
      SELECT title FROM items WHERE items.id = order_items.item_id
    ), ''),
    barcode_snapshot = (
      SELECT barcode FROM items WHERE items.id = order_items.item_id
    );
