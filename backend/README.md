# Renewal Radar Bank Sync Backend

This backend owns all Plaid API calls for Renewal Radar. The Android app must
only use Plaid Link SDK to receive a `public_token`, then send that
`public_token` to this backend. Android must never receive or store Plaid
`client_secret`, `secret`, or `access_token` values.

## Required Environment

```text
PLAID_CLIENT_ID=
PLAID_SECRET=
PLAID_ENV=sandbox
PLAID_PRODUCTS=transactions
PLAID_COUNTRY_CODES=US
PLAID_REDIRECT_URI=
PLAID_ANDROID_PACKAGE_NAME=com.renewalradar.app
PLAID_TOKEN_ENCRYPTION_KEY=
BACKEND_PUBLIC_BASE_URL=https://api.example.com
PORT=8788
PLAID_MOCK_MODE=true
SEED_MOCK_DATA=true
```

`PLAID_TOKEN_ENCRYPTION_KEY` should be a 32-byte base64 key. Keep it in a secret
manager for production. Do not commit it.

`PLAID_ANDROID_PACKAGE_NAME` must match the Android `applicationId`
(`com.renewalradar.app`) registered in the Plaid Dashboard.

## Security Rules

- Android never calls Plaid API endpoints directly except through Plaid Link SDK.
- Android sends only `public_token` and safe Link metadata to the backend.
- Plaid `access_token` is encrypted at rest with AES-256-GCM.
- Plaid tokens are never logged and never returned to Android.
- Production traffic must use HTTPS.
- API responses return safe summaries only: institutions, masked account labels,
  normalized transaction summaries, and subscription candidates.
- Connect, disconnect, sync, export, and delete actions write audit logs.
- Account deletion removes encrypted Plaid tokens and local bank-sync data.

## Endpoints

### `POST /api/plaid/create-link-token`

Creates a Plaid Link token for the current app user.

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
transaction summaries, updates recurring candidates, and returns sync status.

### `GET /api/renewals/candidates`

Returns detected recurring subscription and bill candidates to Android. Results
are always review-only with `status: "pending"` until the user confirms,
ignores, or dismisses them.

### `POST /api/renewals/candidates/{id}/confirm`

Confirms a detected recurring charge as a subscription.

### `POST /api/renewals/candidates/{id}/ignore`

Ignores a detected recurring charge.

### `POST /api/plaid/disconnect`

Disconnects an institution or account, removes the encrypted access token, and
stops future syncs.

### `POST /api/plaid/webhook`

Receives Plaid transaction and recurring update webhooks. Transaction webhooks
trigger a backend sync for the matching Plaid item.

### `GET /api/account/export`

Exports user bank-sync data in JSON form for data portability.

### `POST /api/account/delete`

Deletes the local user, encrypted tokens, transactions, candidates, confirmed
subscriptions, sync logs, and audit logs.

## Development Mode

With `PLAID_MOCK_MODE=true`, the backend does not call Plaid. It returns a mock
Link token and seeds normalized transaction histories for Netflix, Spotify,
Adobe, Google, Apple, Amazon Prime, gym membership, phone bill, insurance, and
utilities. The normal detector turns those imported transactions into
candidates.

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

Replace JSON storage with a managed database before production. Keep these model
boundaries:

- `User`
- `ConnectedInstitution`
- `ConnectedAccount`
- `BankTransaction`
- `RecurringStream`
- `SubscriptionCandidate`
- `ConfirmedSubscription`
- `SyncLog`

Use a managed key service or envelope encryption for Plaid token encryption,
centralized structured logging with token redaction, persistent job workers for
webhook-triggered sync, and authenticated user identity instead of the local
`X-Renewal-User-Id` development header.
