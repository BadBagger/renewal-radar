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
- JVM unit tests for status boundaries
- No Firebase, cloud sync, or paid APIs

## Build

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration.

Command line, when Java and Gradle are available:

```powershell
gradle test
gradle assembleDebug
```

This workspace did not include Java or Gradle on PATH when generated, so command-line verification may require installing Android Studio or a JDK first.
