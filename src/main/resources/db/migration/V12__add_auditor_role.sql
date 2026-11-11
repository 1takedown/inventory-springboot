ALTER TABLE users ADD COLUMN role_next TEXT NOT NULL DEFAULT 'ADMIN'
  CHECK (role_next IN ('ADMIN', 'CASHIER', 'AUDITOR'));
UPDATE users SET role_next = role;
ALTER TABLE users DROP COLUMN role;
ALTER TABLE users RENAME COLUMN role_next TO role;
