# alert.ai

V1 of **alert.ai** is a fast local hazard-reporting network.

## V1 scope

- One alert type: **FAST**
- Press FAST → get a fresh GPS location
- Create a FAST alert in Firestore
- Find active users within **5 km**
- Send an FCM alert to nearby users
- Anonymous Firebase Authentication
- No continuous background location tracking
- No photos, hazard categories, mesh, Bluetooth/Wi-Fi Direct, LoRa, or SMS in V1

## Project structure

- `app/` — Android app (Kotlin + Jetpack Compose)
- `functions/` — Firebase Cloud Functions
- `firestore.rules` — Firestore security rules
- `firebase.json` — Firebase deployment configuration

## Firebase setup

Create a Firebase project and add an Android app with package name:

`ai.alert.app`

Download `google-services.json` and place it at:

`app/google-services.json`

It is intentionally ignored by Git.

Enable:

1. Authentication → Sign-in providers → **Anonymous**
2. Firestore Database
3. Cloud Messaging
4. Cloud Functions

The Android app uses Firebase Authentication, Firestore and FCM.

## Deploy the backend

Install the Firebase CLI, log in, select your Firebase project, then:

```bash
cd functions
npm install
npm run build
cd ..
firebase use YOUR_FIREBASE_PROJECT_ID
firebase deploy --only firestore:rules,functions
```

The Cloud Function is deployed in `asia-south1`.

## Nearby recipient logic

A device registers:

- FCM token
- latitude
- longitude
- geohash
- lastSeen

Presence is refreshed when the app opens. The backend ignores presence older than 24 hours.

A FAST alert searches the 5 km geohash area, verifies the exact Haversine distance, excludes the sender, and sends an FCM data message.

## Android test

Use two Android phones:

1. Install the same debug APK on both.
2. Configure the same Firebase project in `google-services.json`.
3. Open alert.ai on both phones.
4. Allow location and notification permissions.
5. Keep both phones within 5 km.
6. On Phone A, press **FAST**.
7. Phone B should receive **⚡ FAST ALERT**.

## Security

Firestore clients cannot read the complete user directory. User documents are only readable by their owner. Alert creation requires authentication and a sender ID matching the authenticated user. Cloud Functions use the Admin SDK for recipient lookup.

## Current V1 limitations

The Android project is Firebase-ready, but `google-services.json` is not committed. Firebase backend deployment must be performed against your own Firebase project.

The next engineering stage is a proper alert detail screen, map location, delivery/read telemetry, stale-token cleanup, automated tests, and production hardening.
