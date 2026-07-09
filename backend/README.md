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

### `POST /api/paycheck/paychecks/received`

Marks an expected or detected paycheck as received. This is useful when the
deposit arrived but the user wants to confirm the actual amount.

```json
{
  "candidateId": "paycheck-abc123",
  "payDate": "2026-07-15",
  "actualAmountCents": 84250,
  "notes": "Matched pay stub"
}
```

### `POST /api/paycheck/paychecks/manual`

Adds a manual paycheck when income is irregular, paid outside a connected bank,
or not detected from Plaid transactions.

```json
{
  "sourceName": "Weekend gig",
  "payDate": "2026-07-12",
  "amountCents": 17500,
  "notes": "Cash job"
}
```

### `GET /api/paycheck/bills-before-payday`

Returns the sectioned "Bills before payday" review screen. Sources include
manual pay-period bills, Renewal Radar-style confirmed recurring charges,
user-confirmed subscriptions/utilities/bills, and detected recurring outflows
from bank sync.

Response sections:

- `dueBeforePayday`: confirmed/manual included items expected before payday.
- `dueAfterPayday`: confirmed/manual included items expected after payday.
- `needsReview`: detected bank-sync items that are not auto-confirmed.
- `ignored`: ignored or excluded bills/subscriptions/candidates.

Each card includes name, expected amount, due/charge date, category, confidence,
source (`manual`, `bank sync`, or `Renewal Radar`), account/card nickname,
include/exclude toggle metadata, edit action metadata, and review status. The
summary includes total due before payday, biggest upcoming charge, days until
payday, and safe-to-spend impact. Watch-outs call out bills before payday,
larger charges, possible duplicates, amount increases, and charges that usually
hit earlier than expected.

Detected bills are never silently confirmed. Android should show `needsReview`
items with confirm/edit/ignore controls before treating them as user-confirmed.

### `GET /api/paycheck/safe-to-spend`

Returns a deterministic planning snapshot with current balance, next payday,
expected paycheck, committed bills, upcoming renewals/subscriptions, buffer,
planned savings, essentials allowance, total safe-to-spend, daily safe-to-spend,
confidence, explanation, missing-data guidance, and calm watch-outs.

Formula:

```text
current available money
- bills due before next payday
- upcoming subscriptions/renewals
- user buffer
- planned savings
- essentials allowance
- already-spent adjustment
= safe-to-spend amount
```

The response includes both numeric cent fields and compatibility aliases:
`currentBalance`, `nextPayday`, `expectedIncome`, `committedBills`,
`recurringCharges`, `bufferAmount`, `savingsGoal`, `essentialsAllowance`,
`safeToSpendTotal`, `safeToSpendDaily`, `confidenceScore`, and `explanation`.
Itemized arrays are returned as `bills`, `recurringChargeItems`, `watchOuts`,
and `missingData`. The explanation always treats the value as an estimate, not
financial advice.

### `POST /api/paycheck/pay-periods`

Creates or updates the active Paycheck Pilot pay period settings for the user.
Request body:

```json
{
  "payFrequency": "biweekly",
  "lastPayday": "2026-07-01",
  "startDate": "2026-07-01",
  "endDate": "2026-07-14",
  "nextPayday": "2026-07-15",
  "expectedPaycheckCents": 85000,
  "sourceName": "PUBLIX PAYROLL",
  "paycheckAccountId": "acct-checking",
  "safetyBufferCents": 20000,
  "currentBalanceCents": 120000,
  "savingsGoalCents": 10000,
  "essentialsAllowanceCents": 5000,
  "gasAllowanceCents": 4000,
  "groceryAllowanceCents": 12000,
  "alreadySpentCents": 2500,
  "manualBills": [
    { "name": "Phone bill", "amountCents": 6500, "dueDate": "2026-07-11" }
  ],
  "excludedAccountIds": [],
  "excludedTransactionIds": [],
  "excludedCandidateIds": [],
  "excludedSubscriptionIds": []
}
```

Manual override and exclusion fields let users correct estimates without
changing bank data. Excluded accounts, detected candidates, and confirmed
subscriptions are left out of safe-to-spend calculations.

Supported `payFrequency` values are `weekly`, `biweekly`, `semi-monthly`,
`monthly`, `irregular/gig`, and `manual`.

### `GET /api/paycheck/pay-periods/setup`

Returns setup options, the current pay-period settings, and suggested schedules
from detected paycheck deposits. Suggested schedules include evidence text such
as "Detected 3 deposits from PUBLIX PAYROLL every Thursday." Android should let
the user confirm or edit the suggestion before relying on it.

### `GET /api/paycheck/pay-periods/current`

Returns the active pay period. If no explicit period exists, the backend derives
one from the strongest detected recurring income stream.

### `GET /api/paycheck/pay-calendar`

Returns previous and upcoming paydays for the active schedule, expected versus
actual amounts from confirmed/manual paychecks, and watch-outs for missing,
lower-than-usual, or higher-than-usual paychecks. Calendar events expose actions
for confirm paycheck, edit expected amount, mark received, mark as not income,
and add manual paycheck.

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

## Paycheck Income Detection

Paycheck Pilot detection is review-first. The backend detects possible income
sources from normalized transactions, but it does not silently confirm a
paycheck stream. The app should show `status: "pending"` candidates and let the
user confirm, edit the schedule, mark as not income, or ignore.

The detector groups possible income by account and normalized source name, then
classifies cadence as `weekly`, `biweekly`, `semi-monthly`, `monthly`,
`irregular/gig`, `one-time`, or `unknown`. It scores confidence from recurrence,
amount stability, source wording, and whether the source looks like payroll,
employer income, gig income, government benefits, or a recurring transfer.

High-confidence candidates require 3 or more similar deposits on a stable
cadence. Medium confidence covers 2 similar payroll/employer deposits on a
likely cadence. Low confidence covers recurring but variable deposits, including
gig income. One large payroll-like deposit may be surfaced as very low
confidence for review. One-time Cash App, Venmo, Zelle, PayPal, or similar
transfers are not treated as paycheck candidates by default.

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
