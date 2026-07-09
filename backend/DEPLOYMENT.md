# Renewal Radar Backend Deployment

Renewal Radar must use a hosted HTTPS backend for real Plaid bank/card sync.
Do not ship production builds pointed at localhost, a LAN IP, ngrok, Cloudflare
quick tunnels, or a desktop server.

## Stage Order

1. Demo Mode works fully inside Android with seeded transactions and no backend.
2. CSV Import works fully inside Android with manually exported bank/card CSVs.
3. Plaid Sandbox runs through this backend only. Android uses Plaid Link SDK and
   sends only the `public_token`.
4. Hosted sandbox deployment runs on Render or Railway with HTTPS.
5. Plaid Production stays disabled until real Plaid production approval,
   credentials, retention policy, and billing plan are ready.

## Required Environment Variables

```text
PLAID_CLIENT_ID=
PLAID_SECRET=
PLAID_ENV=sandbox
PLAID_PRODUCTS=transactions
PLAID_COUNTRY_CODES=US
PLAID_REDIRECT_URI=
PLAID_ANDROID_PACKAGE_NAME=com.renewalradar.app
DATABASE_URL=
TOKEN_ENCRYPTION_KEY=
BACKEND_PUBLIC_BASE_URL=https://your-hosted-backend.example
PLAID_MOCK_MODE=true
SEED_MOCK_DATA=true
```

`TOKEN_ENCRYPTION_KEY` must be a 32-byte raw string or base64-encoded 32-byte
key. Store it in Render/Railway secrets, never in git.

## Render

1. Create a new Render Web Service from the GitHub repo.
2. Set the root directory to `backend`.
3. Use `npm install` as the build command and `npm start` as the start command.
4. Add the variables from `.env.example` in the Render dashboard.
5. Set `BACKEND_PUBLIC_BASE_URL` to the Render HTTPS service URL.
6. Open `/health`. Expected sandbox response:

```json
{
  "status": "ok",
  "environment": "sandbox",
  "plaidConfigured": true
}
```

## Railway

1. Create a Railway project from the GitHub repo.
2. Set the service root to `backend`.
3. Add the variables from `.env.example`.
4. Set `BACKEND_PUBLIC_BASE_URL` to the Railway HTTPS domain.
5. Verify `/health` before entering the URL in Android Settings.

## Android Configuration

Debug builds can test with hosted sandbox HTTPS URLs. Release builds default to
`https://renewal-radar-bank-sync.example.com`, which is intentionally a
placeholder and displays a production-backend-not-configured error until a real
hosted backend URL is entered.

The Android app never stores Plaid `client_secret`, `PLAID_SECRET`, or
`access_token`. Access tokens remain encrypted only on the backend.
