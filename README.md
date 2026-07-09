# Renewal Radar

Renewal Radar is a local-first Android app built with Kotlin, Jetpack Compose, Room, and WorkManager.

It tracks renewals by final deadline and classifies each item as:

- Safe: more than `renewWindowDays` away
- Renew Soon: within `renewWindowDays` but outside `attentionWindowDays`
- Needs Attention: within `attentionWindowDays` through the due date
- Overdue: after `dueDate`

Default windows are 70 days for renewals and 14 days for attention.

## Features

- Room local database
- Renewal item model with per-item windows
- Dashboard summary
- Renewal list sorted by due date
- Add/edit/delete flow
- Settings and about screen
- Daily local notification checks for overdue and needs-attention items
- Plaid Link Android connection flow through a secure backend
- Sandbox/mock connected-account and detected-subscription review flow
- Transaction-history subscription detection with weekly, biweekly, monthly,
  every-4-weeks, quarterly, semiannual, annual, and irregular cadence support
- Detected renewals review screen with high-confidence, needs-review, and
  ignored/dismissed sections plus bulk review actions
- Renewal prediction windows, amount variance, confidence labels, reminder
  timing, and watch-outs for price increases, early charges, duplicates,
  inactive subscriptions, and trial conversion risk
- JVM unit tests for status boundaries
- No Firebase, cloud sync, or paid APIs

## Bank Connection Security

Renewal Radar does not ask for bank usernames or passwords. Plaid Link is the
intended account-connection surface, and Android only sends a short-lived
`public_token` to a secure HTTPS backend. Plaid `client_secret` and
`access_token` values must never be stored in the Android app.

The backend owns Plaid API calls, exchanges `public_token` for `access_token`,
stores tokens encrypted, fetches transactions and recurring transactions, and
returns only safe account, transaction, and subscription summaries to Android.
This build uses sandbox/mock mode until a real backend is configured.

Detected recurring charges are added to a review queue as pending candidates.
Renewal Radar never silently confirms every detected charge as a subscription;
the user can confirm, edit, ignore, or delete each candidate.

Predictions include the last charge date, expected next charge date, a start/end
window for charges that move around weekends or variable bill cycles, usual
amount, variance, confidence, and any watch-outs. If the user edits the next
charge date, the app preserves that correction on later syncs.

The backend scaffold lives in `backend/`. See `backend/README.md` and
`backend/IMPLEMENTATION_PLAN.md` for required Plaid environment variables,
endpoints, storage models, security rules, mock data, and production rollout
steps.

The Android app uses Plaid Link SDK for the connection UI only. All Link token
creation, `public_token` exchange, transaction sync, and token storage happen on
the backend.

## Build

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration.

Command line, when Java and Gradle are available:

```powershell
gradle test
gradle assembleDebug
```

This workspace did not include Java or Gradle on PATH when generated, so command-line verification may require installing Android Studio or a JDK first.
