# Plaid Requirements For Renewal Radar Beta

Renewal Radar is ready for a small Plaid beta only after these requirements are
met. The Android app must never contain Plaid secrets or access tokens.

## Current Plaid Environment Reality

- Sandbox is free and uses test accounts only.
- Plaid Development has been replaced by Limited Production / Trial-style real
  data testing in Production.
- Real beta users require Production environment access, even for a low-number
  trial.
- OAuth support is required for many major US institutions, so Android Link
  launch plus the hosted HTTPS backend must be configured before beta users test
  real banks.

References:

- https://plaid.com/docs/sandbox/
- https://plaid.com/docs/quickstart/glossary/
- https://plaid.com/docs/account/billing/
- https://plaid.com/docs/link/oauth/

## Plaid Dashboard Setup

Create or configure:

1. Plaid Dashboard account with verified email.
2. Renewal Radar app in the Plaid Dashboard.
3. Products: `transactions`.
4. Country codes: `US`.
5. Android package name: `com.renewalradar.app`.
6. Sandbox keys for internal fake-data testing.
7. Trial / Limited Production access for real beta user data.
8. A clear use case description for subscription, bill, and recurring charge
   detection.
9. Any required Plaid Link customization and privacy/data transparency copy.

## Suggested Plaid Trial Application Answers

Use this as the working copy when Plaid asks what the app does:

```text
Renewal Radar helps users identify recurring subscriptions, bills, free trials,
and upcoming renewal charges from read-only transaction data. Users review and
confirm detected recurring charges before anything becomes a tracked renewal.
The app alerts users before upcoming charges, price increases, duplicate
charges, and likely renewal dates. Renewal Radar does not move money, initiate
payments, provide credit decisions, or store bank login credentials.
```

Data usage:

```text
Read-only transaction data is used to normalize merchants, detect recurring
charges, estimate next charge dates, calculate confidence scores, and show
review-only subscription or bill candidates. The Android app receives only safe
summaries and never receives Plaid access tokens.
```

Security:

```text
Plaid Link handles credential entry. Android sends only the public_token to a
hosted HTTPS backend. The backend exchanges it for an access_token, encrypts the
token at rest, never logs tokens, and never returns tokens to Android. Users can
disconnect accounts and delete bank-sync data.
```

Beta scope:

```text
Initial beta is limited to a small number of users through backend beta caps.
Manual tracking and CSV import remain available without bank sync.
```

## Hosted Backend Requirements

Use Render or Railway. Do not use localhost, LAN IPs, ngrok, Cloudflare quick
tunnels, or a desktop server for beta users.

Required secrets:

```text
PLAID_CLIENT_ID=
PLAID_SECRET=
PLAID_ENV=production
PLAID_PRODUCTS=transactions
PLAID_COUNTRY_CODES=US
PLAID_ANDROID_PACKAGE_NAME=com.renewalradar.app
BACKEND_PUBLIC_BASE_URL=https://your-hosted-backend.example
TOKEN_ENCRYPTION_KEY=
PLAID_MOCK_MODE=false
BETA_MODE=true
BETA_MAX_USERS=5
BETA_ALLOWED_USER_IDS=
```

For sandbox-only testing, use:

```text
PLAID_ENV=sandbox
PLAID_MOCK_MODE=false
BETA_MODE=true
BETA_MAX_USERS=5
```

Sandbox still uses fake bank data. It proves the real Link flow without live
financial data.

## Low-Number Beta Guardrails

The backend supports:

- `BETA_MODE=true`
- `BETA_MAX_USERS=5` or another low cap
- optional `BETA_ALLOWED_USER_IDS=rr-...,rr-...`

Android sends a random per-install `X-Renewal-User-Id`, so beta users do not
all share the old `local-user` backend identity.

`GET /health` returns:

```json
{
  "status": "ok",
  "environment": "production",
  "plaidConfigured": true,
  "mockMode": false,
  "betaTrial": {
    "enabled": true,
    "maxUsers": 5,
    "activeUsers": 0,
    "slotsAvailable": 5,
    "allowlistEnabled": false
  }
}
```

## Beta Launch Steps

1. Deploy the backend to Render or Railway.
2. Add Plaid and beta env vars in the host dashboard.
3. Confirm `/health` shows `plaidConfigured: true`, `mockMode: false`, and the
   expected beta slots.
4. Enter the hosted HTTPS backend URL in Renewal Radar Settings.
5. Install the beta APK on one test device.
6. In a debug build, open Settings > Developer Backend Status and copy the
   `Beta install ID` if you are using `BETA_ALLOWED_USER_IDS`.
7. Tap Connected Accounts > Connect bank or card.
8. Complete Plaid Link.
9. Sync transactions.
10. Review Detected renewals.
11. Confirm that disconnect removes backend access tokens and stops syncing.

## What Not To Do

- Do not put `PLAID_SECRET` in Android.
- Do not put Plaid `access_token` in Android.
- Do not ask users for bank usernames or passwords.
- Do not run real beta users through a desktop-hosted backend.
- Do not increase the beta cap until Plaid billing and hosting costs are clear.
