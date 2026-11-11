CREATE TEMP TABLE money_migration_guard (
  ok INTEGER NOT NULL CHECK (ok = 1)
);

INSERT INTO money_migration_guard
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM items
  WHERE cost IS NULL OR cost < 0 OR cost > 92233720368547700
     OR profit IS NULL OR profit < 0 OR profit > 92233720368547700
) THEN 0 ELSE 1 END;

INSERT INTO money_migration_guard
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM order_items
  WHERE paid_amount IS NULL OR paid_amount < 0 OR paid_amount > 92233720368547700
     OR (cost_snapshot IS NOT NULL AND (cost_snapshot < 0 OR cost_snapshot > 92233720368547700))
     OR (profit_snapshot IS NOT NULL AND (profit_snapshot < 0 OR profit_snapshot > 92233720368547700))
) THEN 0 ELSE 1 END;

INSERT INTO money_migration_guard
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM order_return_items
  WHERE refund_amount IS NULL OR refund_amount < 0 OR refund_amount > 92233720368547700
) THEN 0 ELSE 1 END;

ALTER TABLE items ADD COLUMN cost_minor INTEGER NOT NULL DEFAULT 0
  CHECK (typeof(cost_minor) = 'integer' AND cost_minor >= 0);
ALTER TABLE items ADD COLUMN profit_minor INTEGER NOT NULL DEFAULT 0
  CHECK (typeof(profit_minor) = 'integer' AND profit_minor >= 0);
UPDATE items SET cost_minor = CAST(ROUND(cost * 100, 0) AS INTEGER),
                 profit_minor = CAST(ROUND(profit * 100, 0) AS INTEGER);

INSERT INTO money_migration_guard
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM items
  WHERE cost_minor > 9223372036854775807 - profit_minor
) THEN 0 ELSE 1 END;

ALTER TABLE order_items ADD COLUMN paid_amount_minor INTEGER NOT NULL DEFAULT 0
  CHECK (typeof(paid_amount_minor) = 'integer' AND paid_amount_minor >= 0);
ALTER TABLE order_items ADD COLUMN cost_snapshot_minor INTEGER
  CHECK (cost_snapshot_minor IS NULL OR (typeof(cost_snapshot_minor) = 'integer' AND cost_snapshot_minor >= 0));
ALTER TABLE order_items ADD COLUMN profit_snapshot_minor INTEGER
  CHECK (profit_snapshot_minor IS NULL OR (typeof(profit_snapshot_minor) = 'integer' AND profit_snapshot_minor >= 0));
UPDATE order_items SET paid_amount_minor = CAST(ROUND(paid_amount * 100, 0) AS INTEGER),
                       cost_snapshot_minor = CASE WHEN cost_snapshot IS NULL THEN NULL ELSE CAST(ROUND(cost_snapshot * 100, 0) AS INTEGER) END,
                       profit_snapshot_minor = CASE WHEN profit_snapshot IS NULL THEN NULL ELSE CAST(ROUND(profit_snapshot * 100, 0) AS INTEGER) END;

INSERT INTO money_migration_guard
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM order_items
  WHERE quantity > 0 AND (
       paid_amount_minor > 9223372036854775807 / quantity
       OR (cost_snapshot_minor IS NOT NULL
           AND cost_snapshot_minor > 9223372036854775807 / quantity)
       OR (profit_snapshot_minor IS NOT NULL
           AND profit_snapshot_minor > 9223372036854775807 / quantity)
  )
) THEN 0 ELSE 1 END;

ALTER TABLE order_return_items ADD COLUMN refund_amount_minor INTEGER NOT NULL DEFAULT 0
  CHECK (typeof(refund_amount_minor) = 'integer' AND refund_amount_minor >= 0);
UPDATE order_return_items SET refund_amount_minor = CAST(ROUND(refund_amount * 100, 0) AS INTEGER);

ALTER TABLE items DROP COLUMN cost;
ALTER TABLE items DROP COLUMN profit;
ALTER TABLE items RENAME COLUMN cost_minor TO cost;
ALTER TABLE items RENAME COLUMN profit_minor TO profit;
ALTER TABLE order_items DROP COLUMN paid_amount;
ALTER TABLE order_items DROP COLUMN cost_snapshot;
ALTER TABLE order_items DROP COLUMN profit_snapshot;
ALTER TABLE order_items RENAME COLUMN paid_amount_minor TO paid_amount;
ALTER TABLE order_items RENAME COLUMN cost_snapshot_minor TO cost_snapshot;
ALTER TABLE order_items RENAME COLUMN profit_snapshot_minor TO profit_snapshot;
ALTER TABLE order_return_items DROP COLUMN refund_amount;
ALTER TABLE order_return_items RENAME COLUMN refund_amount_minor TO refund_amount;

DROP TABLE money_migration_guard;