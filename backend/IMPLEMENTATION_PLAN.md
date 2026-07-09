# Renewal Radar Plaid Backend Implementation Plan

## Goal

Enable Renewal Radar to detect recurring subscription, bill, trial, renewal, and
upcoming-charge patterns from connected bank and card accounts while keeping all
Plaid secrets and access tokens off Android.

## Android Boundary

Android may:

- Request `POST /api/plaid/create-link-token`.
- Open Plaid Link SDK with the returned `link_token`.
- Send the Link `public_token` to `POST /api/plaid/exchange-public-token`.
- Display safe account, sync, transaction, and subscription candidate summaries.
- Let users confirm, edit, ignore, delete, or disconnect safe summaries.

Android must not:

- Ask users for bank credentials.
- Store bank credentials.
- Store `PLAID_SECRET`, Plaid `client_secret`, or Plaid `access_token`.
- Call Plaid API endpoints directly outside Plaid Link SDK.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/plaid/create-link-token` | Backend creates a Plaid Link token for the signed-in or local app user and returns `link_token`. |
| `POST` | `/api/plaid/exchange-public-token` | Backend receives Link `public_token`, exchanges it for Plaid `access_token`, encrypts token, and stores safe item/account metadata. |
| `POST` | `/api/plaid/sync-transactions` | Backend fetches latest transactions from Plaid, stores normalized summaries, detects recurring subscriptions, and returns sync status. |
| `GET` | `/api/renewals/candidates` | Returns detected recurring candidates to Android. |
| `POST` | `/api/renewals/candidates/{id}/confirm` | Converts a candidate into a confirmed subscription. |
| `POST` | `/api/renewals/candidates/{id}/ignore` | Marks a candidate ignored. |
| `POST` | `/api/plaid/disconnect` | Removes encrypted token, stops sync, and marks institution/accounts disconnected. |
| `POST` | `/api/plaid/webhook` | Receives Plaid transaction/recurring webhooks and triggers backend sync. |
| `GET` | `/api/account/export` | Exports safe user data for portability. |
| `POST` | `/api/account/delete` | Deletes user bank-sync data and encrypted tokens. |

## Storage Models

### User

- `id`
- `createdAt`
- `mode`

### ConnectedInstitution

- `id`
- `userId`
- `plaidItemId`
- `institutionName`
- `encryptedAccessToken`
- `transactionCursor`
- `status`
- `createdAt`
- `disconnectedAt`

### ConnectedAccount

- `id`
- `userId`
- `institutionId`
- `plaidAccountId`
- `name`
- `mask`
- `type`
- `subtype`
- `status`
- `lastSyncedAt`

### BankTransaction

- `id`
- `userId`
- `institutionId`
- `plaidAccountId`
- `merchantName`
- `amountCents`
- `isoCurrencyCode`
- `date`
- `pending`
- `category`
- `createdAt`

### RecurringStream

- `id`
- `userId`
- `institutionId`
- `accountId`
- `merchantName`
- `amountCents`
- `cadence`
- `firstSeenDate`
- `lastSeenDate`
- `nextExpectedDate`
- `confidence`

### SubscriptionCandidate

- `id`
- `userId`
- `accountId`
- `merchantName`
- `amountCents`
- `cadence`
- `nextChargeDate`
- `confidence`
- `status`
- `createdAt`
- `updatedAt`

### ConfirmedSubscription

- `id`
- `userId`
- `candidateId`
- `merchantName`
- `amountCents`
- `cadence`
- `nextChargeDate`
- `sourceAccountId`
- `createdAt`

### SyncLog

- `id`
- `userId`
- `institutionId`
- `status`
- `startedAt`
- `finishedAt`
- `message`

## Security Requirements

- Store Plaid access tokens encrypted at rest with AES-256-GCM or production
  envelope encryption.
- Never log raw tokens.
- Never return tokens to Android.
- Reject non-HTTPS production requests.
- Use API auth before production. The scaffold uses `X-Renewal-User-Id` only for
  local development.
- Rate limit every request.
- Write audit logs for connect, disconnect, sync, export, delete, webhook, and
  candidate decisions.
- Support user data export and account deletion.
- Keep `.env`, JSON stores, keystores, and service account files out of git.

## Development Mode

`PLAID_MOCK_MODE=true` avoids real Plaid calls and seeds recurring candidates:

- Netflix
- Spotify
- Adobe
- Google
- Apple
- Amazon Prime
- Gym membership
- Phone bill
- Insurance
- Utilities

## Production Roadmap

1. Replace JSON store with Postgres or another managed database.
2. Add authenticated users and remove local development user fallback.
3. Store `PLAID_TOKEN_ENCRYPTION_KEY` in a secret manager or use cloud KMS.
4. Move webhook-triggered sync to a background job queue.
5. Add Plaid webhook signature/IP validation if configured for the deployed
   environment.
6. Add retry and backoff for Plaid sync failures.
7. Add monitoring for sync lag, webhook failures, rate-limit events, and token
   decrypt failures.
8. Add Android API client integration against this backend while keeping Plaid
   Link as the only Android-side Plaid surface.
