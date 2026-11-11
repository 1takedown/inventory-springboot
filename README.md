# Inventory Spring Boot

This is a separate inventory project based on commit `f5bd862` of
`inventory-springboot`, with order port support removed. The original project is unchanged.

## Included behavior

- Short-lived JWT access tokens with rotating, revocable refresh tokens
- Administrator, cashier, and auditor authorization roles
- Item list, barcode lookup, create, update, and delete
- Draft order creation with reserved stock, editable item lines, confirmation, and audited cancellation
- Confirmed order list and details with immutable sale snapshots
- Audited full and partial order returns
- Transactional stock deduction and return restoration
- Admin stock history with item/reason filters, opening balances, and retained history for deleted items
- English and Simplified Chinese API messages
- Excel receipt-template downloads
- A maintainable React/TypeScript UI built with Vite
- SQLite persistence with versioned Flyway migrations

Cashiers can create draft and confirmed orders, including receipt export for
confirmed orders, and can add or remove item lines on saved drafts. Editing a
draft's note or date, confirming or cancelling drafts, pickup changes,
returns, and archiving require an administrator. Drafts reserve stock until an administrator confirms or cancels
them; cancellation restores the reserved stock.

The Auditor role is for in-house staff who track the numbers. Auditors can
review stock levels, stock movements, the daily dashboard, and all orders
(including archived and cancelled) with financial data hidden. Business records
are read-only; auditors cannot export receipts or manage users, but can change
their own password. Descriptions and order notes remain visible; prices typed
into these free-text fields are not automatically hidden.
Database inserts that omit a role default to Administrator; existing accounts keep
their assigned roles.

## Run

Requirements: JDK 17 or newer and Maven 3.9 or newer.

Generate a signing key and set it before starting the application:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)
$env:AUTH_COOKIE_SECURE = 'false' # local HTTP development only
mvn spring-boot:run
```

Open <http://localhost:3000>. Start with a new database in this project directory.
Migrations are numbered V1–V12; databases from the original project are incompatible
with this migration history and must not be reused.
For an empty database, create the first administrator once by also setting
`BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` before startup, then
remove those variables.

Maven downloads the pinned Node/npm toolchain, installs the locked frontend
dependencies, builds the UI, and includes it in the application.

For UI development, run the backend and Vite server in separate terminals:

```powershell
mvn spring-boot:run
cd frontend
npm ci
npm run dev
```

Open <http://localhost:5173>; Vite proxies `/api` to the backend on port 3000.

To build and test:

```powershell
mvn clean test
mvn clean package
java -jar target/inventory-springboot-1.0.0.jar
```

Run the packaged JAR from this project directory so the default relative
database path resolves to `data/inventory.db`.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | HTTP port |
| `DB_PATH` | `./data/inventory.db` | SQLite file path |
| `JWT_SECRET_BASE64` | required | Base64-encoded random signing key of at least 32 bytes |
| `ACCESS_TOKEN_SECONDS` | `600` | Short-lived access-token lifetime |
| `REFRESH_TOKEN_SECONDS` | `2592000` | Rotating refresh-token lifetime |
| `AUTH_COOKIE_SECURE` | `true` | Require HTTPS for the browser refresh cookie |
| `REQUIRE_HTTPS` | `false` | Redirect all application traffic to HTTPS |
| `APP_CORS_ALLOWED_ORIGINS` | empty | Comma-separated explicit browser origins |
| `LEGACY_LOGIN_ENABLED` | `false` | Temporarily retain deprecated `/api/login` |
| `FORWARD_HEADERS_STRATEGY` | `none` | Use `native` with explicit trusted-proxy and client-header settings for deployment |
| `BOOTSTRAP_ADMIN_USERNAME` | empty | One-time first-administrator username |
| `BOOTSTRAP_ADMIN_PASSWORD` | empty | One-time first-administrator password |
| `BOOTSTRAP_ADMIN_RESET` | `false` | Reset the named administrator during startup |

For production, terminate TLS at a trusted reverse proxy, set
`REQUIRE_HTTPS=true` and configure native forwarding with trusted proxy addresses
and headers as shown in [the Cloudflare deployment guide](CLOUDFLARE_TUNNEL_DEPLOYMENT.md#5-create-the-production-environment-file).
Prevent direct network access to the backend, rotate the signing key and administrator
password, and keep the SQLite database outside source control. Run only one application
instance against SQLite; multi-instance deployments require a server database
and shared rate-limit/revocation storage.

Browser authentication uses an HttpOnly refresh cookie and keeps access tokens
only in memory. First-party non-browser clients use `/api/auth/token`,
`/api/auth/token/refresh`, and `/api/auth/token/revoke`. The old `/api/login`
endpoint is deprecated and should be disabled after clients migrate.
Refresh tokens are single-use and replay revokes the full token family, so
non-browser clients must serialize refresh requests.

## Continuous integration

GitHub Actions runs a Java 17 Maven build, the backend and frontend test suites,
the production frontend build, and a production dependency audit on pushes and
pull requests to `main`. It does not publish a release or deploy the application.
