ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'ADMIN'
  CHECK (role IN ('ADMIN', 'CASHIER'));
ALTER TABLE users ADD COLUMN active INTEGER NOT NULL DEFAULT 1
  CHECK (active IN (0, 1));
ALTER TABLE users ADD COLUMN auth_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE auth_sessions (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT UNIQUE NOT NULL,
  auth_version INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  revoked_at INTEGER,
  replaced_by TEXT,
  created_at INTEGER NOT NULL,
  created_ip TEXT,
  user_agent TEXT
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_family ON auth_sessions(family_id);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions(expires_at);

CREATE TABLE revoked_access_tokens (
  jti TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER NOT NULL
);

CREATE INDEX idx_revoked_access_expiry ON revoked_access_tokens(expires_at);
