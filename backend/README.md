# Smithware Financial Sync Backend

This backend owns all Plaid API calls for Renewal Radar and Paycheck Pilot.
Both Android apps use Plaid Link SDK only to receive a `public_token`, then send
that `public_token` to this backend. Android must never receive or store Plaid
`client_secret`, `secret`, or `access_token` values.

Renewal Radar uses the shared connected-account and transaction data to detect
subscriptions and recurring charges. Paycheck Pilot uses the same shared data to
detect paycheck deposits, recurring income, bills before payday, safe-to-spend
estimates, and watch-outs before the next paycheck.

## Required Environment

```text
PLAID_CLIENT_ID=
PLAID_SECRET=
PLAID_ENV=sandbox
PLAID_PRODUCTS=transactions
PLAID_COUNTRY_CODES=US
PLAID_REDIRECT_URI=
PLAID_ANDROID_PACKAGE_NAME=com.renewalradar.app
PAYCHECK_PILOT_ANDROID_PACKAGE_NAME=com.paycheckpilot
TOKEN_ENCRYPTION_KEY=
BACKEND_PUBLIC_BASE_URL=https://api.example.com
PORT=8788
PLAID_MOCK_MODE=true
SEED_MOCK_DATA=true
BETA_MODE=false
BETA_MAX_USERS=10
BETA_ALLOWED_USER_IDS=
```

`TOKEN_ENCRYPTION_KEY` should be a 32-byte base64 key. Keep it in a secret
manager for production. Do not commit it.

`PLAID_ANDROID_PACKAGE_NAME` must match the Android `applicationId`
(`com.renewalradar.app`) registered in the Plaid Dashboard.

`PAYCHECK_PILOT_ANDROID_PACKAGE_NAME` must match Paycheck Pilot's Android
`applicationId` registered in the Plaid Dashboard. Paycheck Pilot requests
should send `X-Client-App: paycheck-pilot` so link tokens use this package
name. Renewal Radar can omit that header or send `X-Client-App: renewal-radar`.

## Hosted Backend URL

Real users must connect through a hosted HTTPS backend such as Render or
Railway. Do not ship production builds pointed at localhost, a LAN IP, ngrok,
Cloudflare quick tunnels, or any desktop server.

Android release builds default to the placeholder
`https://renewal-radar-bank-sync.example.com`. Enter the real hosted HTTPS URL
in Settings only after `/health` works on the deployed backend.

For local backend development only:

```powershell
cd backend
npm.cmd test
npm.cmd run dev
```

If `PLAID_CLIENT_ID` and `PLAID_SECRET` are not set, the backend runs in mock
mode. Mock mode can sync demo recurring charges and payroll deposits, but real
Plaid Link requires valid Plaid sandbox/development/production credentials on
the hosted backend.

See `DEPLOYMENT.md` for Render and Railway setup.
See `PLAID_BETA_REQUIREMENTS.md` for the low-number Plaid beta checklist,
Trial/Limited Production notes, and backend cap settings.

## Security Rules

- Android never calls Plaid API endpoints directly except through Plaid Link SDK.
- Android sends only `public_token` and safe Link metadata to the backend.
- Plaid `access_token` is encrypted at rest with AES-256-GCM.
- Plaid tokens are never logged and never returned to Android.
- Production traffic must use HTTPS.
- API responses return safe summaries only: institutions, masked account labels,
  normalized transaction summaries, subscription candidates, income summaries,
  paycheck candidates, bill summaries, pay periods, and safe-to-spend snapshots.
- Connect, disconnect, sync, export, and delete actions write audit logs.
- Account deletion removes encrypted Plaid tokens and local bank-sync data.

## App Identity Headers

Development builds use headers for app and local user identity:

```text
X-Client-App: renewal-radar
X-Renewal-User-Id: local-user

X-Client-App: paycheck-pilot
X-Paycheck-Pilot-User-Id: local-user
```

Production should replace these local user headers with authenticated identity
from the hosted API layer. The app header is still useful for app-specific Link
configuration and response routing.

## Shared Endpoints

### `POST /api/plaid/create-link-token`

Creates a Plaid Link token for the current app user. The backend chooses the
registered Android package name from `X-Client-App`.

Response:

```json
{ "link_token": "link-sandbox-...", "expiration": "2026-07-09T12:00:00.000Z" }
```

### `POST /api/plaid/exchange-public-token`

Receives `public_token` after Plaid Link succeeds, exchanges it for
`access_token`, encrypts and stores the token, and stores safe institution and
account metadata.

Request:

```json
{
  "public_token": "public-sandbox-...",
  "institution": { "name": "Plaid Sandbox Bank", "institution_id": "ins_109508" },
  "accounts": [
    { "id": "acc_1", "name": "Checking", "mask": "0000", "type": "depository", "subtype": "checking" }
  ]
}
```

### `POST /api/plaid/sync-transactions`

Fetches latest Plaid transactions via `/transactions/sync`, stores normalized
transaction summaries, updates Renewal Radar recurring candidates, updates
Paycheck Pilot income/paycheck/bill summaries, and returns sync status.

### `GET /api/accounts`

Returns connected account summaries safe for Android: account id, institution
name, account name, mask, type, subtype, and active state. Plaid access tokens
are never returned.

### `GET /api/transactions`

Returns normalized transaction summaries for the local user. This is shared
source data for Renewal Radar detection and Paycheck Pilot estimates.

### `POST /api/plaid/disconnect`

Disconnects an institution or account, removes the encrypted access token, and
stops future syncs.

### `POST /api/plaid/webhook`

Receives Plaid transaction and recurring update webhooks. Transaction webhooks
trigger a backend sync for the matching Plaid item.

## Renewal Radar Endpoints

### `GET /api/renewals/candidates`

Returns detected recurring subscription and bill candidates to Android. Results
are always review-only with `status: "pending"` until the user confirms,
ignores, or dismisses them.

### `POST /api/renewals/candidates/{id}/confirm`

Confirms a detected recurring charge as a subscription.

### `POST /api/renewals/candidates/{id}/ignore`

Ignores a detected recurring charge.

## Paycheck Pilot Endpoints

Paycheck Pilot should use the shared Plaid endpoints above for connection,
sync, account listing, transaction listing, disconnect, export, and delete.
App-specific paycheck planning data lives under `/api/paycheck`.

### `GET /api/paycheck/income-streams`

Returns detected recurring income streams, including payer name, average amount,
cadence, last pay date, predicted next pay date, confidence, and source
transaction ids.

### `GET /api/paycheck/paychecks`

Returns paycheck candidates detected from income transactions. Candidates can be
left pending, confirmed, or ignored without changing the raw bank transaction.

### `POST /api/paycheck/paychecks/confirm`

Confirms a paycheck candidate as an actual paycheck. Request body:

```json
{ "candidateId": "paycheck-abc123", "actualAmountCents": 85000, "notes": "Matched pay stub" }
```

### `POST /api/paycheck/paychecks/ignore`

Ignores a paycheck candidate that should not be used for paycheck planning.
Request body:

```json
{ "candidateId": "paycheck-abc123", "notes": "Transfer, not payroll" }
```

### `GET /api/paycheck/bills-before-payday`

Returns detected recurring bills expected before the current pay period's next
payday, including amount, due date, account nickname, category, and detection
confidence.

### `GET /api/paycheck/safe-to-spend`

Returns a deterministic planning snapshot with current balance, next payday,
expected paycheck, bills due before payday, projected leftover, safety buffer,
safe-to-spend amount, and warning state.

### `POST /api/paycheck/pay-periods`

Creates or updates the active Paycheck Pilot pay period settings for the user.
Request body:

```json
{
  "startDate": "2026-07-01",
  "nextPayday": "2026-07-15",
  "expectedPaycheckCents": 85000,
  "safetyBufferCents": 20000,
  "currentBalanceCents": 120000
}
```

### `GET /api/paycheck/pay-periods/current`

Returns the active pay period. If no explicit period exists, the backend derives
one from the strongest detected recurring income stream.

### `GET /api/paycheck/watch-outs`

Returns non-judgmental warnings such as "You may run short", "Build a bigger
buffer if you can", or upcoming bills that may hit before the next paycheck.

### `GET /api/account/export`

Exports user bank-sync data in JSON form for data portability.

### `POST /api/account/delete`

Deletes the local user, encrypted tokens, transactions, candidates, confirmed
subscriptions, sync logs, and audit logs.

## Development Mode

With `PLAID_MOCK_MODE=true`, the backend does not call Plaid. It returns a mock
Link token and seeds normalized transaction histories for Netflix, Spotify,
Adobe, Amazon Prime, gym membership, phone bill, electric utility, duplicate
charge, price increase examples, and biweekly payroll deposits. The normal
detectors turn those imported transactions into Renewal Radar candidates and
Paycheck Pilot income/paycheck/bill summaries.

### Testing Paycheck Pilot With Sandbox Or Demo Data

For demo data without Plaid credentials:

```powershell
cd backend
$env:PLAID_MOCK_MODE="true"
$env:SEED_MOCK_DATA="true"
npm.cmd run dev
```

Then call:

```powershell
Invoke-RestMethod http://localhost:8788/health
Invoke-RestMethod http://localhost:8788/api/paycheck/income-streams -Headers @{ "X-Client-App" = "paycheck-pilot" }
Invoke-RestMethod http://localhost:8788/api/paycheck/safe-to-spend -Headers @{ "X-Client-App" = "paycheck-pilot" }
```

For Plaid Sandbox, set `PLAID_CLIENT_ID`, `PLAID_SECRET`,
`PLAID_ENV=sandbox`, `TOKEN_ENCRYPTION_KEY`, and both Android package-name
environment variables on the hosted backend. Android opens Plaid Link with the
backend-created `link_token`, sends the `public_token` to
`/api/plaid/exchange-public-token`, then calls `/api/plaid/sync-transactions`.

## Subscription Detection

Detection groups normalized bank/card transactions by merchant and similar
amount. It only considers completed positive outflows, excludes income/payroll
style deposits, and avoids one-time retail unless a stable recurring amount is
present. Supported cadence labels are `Weekly`, `Biweekly`, `Monthly`, `Every 4
weeks`, `Quarterly`, `Semiannual`, `Annual`, and `Irregular but repeated`.

Candidate payloads include merchant name, average amount, last charge date,
predicted next charge date, cadence, confidence score, transaction IDs used,
account nickname, category, detection reason, type (`subscription` or `bill`),
review status, amount variance, next-charge window start/end, inactive flag, and
watch-outs such as price increases, early charges, suspected duplicates,
subscriptions not seen recently, and free-trial conversion risk.

Run:

```powershell
cd backend
npm.cmd test
npm.cmd run dev
```

The server stores development data in `backend/data/dev-store.json`.

## Production Implementation Notes

Replace JSON storage with a managed database before production. Keep these
shared model boundaries:

- `User`
- `ConnectedInstitution`
- `ConnectedAccount`
- `BankTransaction`
- `RecurringStream`
- `SyncLog`

Renewal Radar-specific models:

- `SubscriptionCandidate`
- `ConfirmedSubscription`
- `RenewalWatchOut`

Paycheck Pilot-specific models:

- `IncomeStream`
- `PaycheckCandidate`
- `ConfirmedPaycheck`
- `PayPeriod`
- `BillBeforePayday`
- `SafeToSpendSnapshot`
- `PaycheckWatchOut`

Use a managed key service or envelope encryption for Plaid token encryption,
centralized structured logging with token redaction, persistent job workers for
webhook-triggered sync, and authenticated user identity instead of the local
`X-Renewal-User-Id` and `X-Paycheck-Pilot-User-Id` development headers.
