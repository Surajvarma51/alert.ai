# alert.ai

alert.ai is a community FAST hazard-reporting network. V1 uses Firebase Authentication, Firestore and FCM for the online network.

## Account system
- Email/password registration and sign-in
- Display name
- Email verification
- Password reset email
- Change password with recent re-authentication
- Change email with recent re-authentication
- Sign out
- Account deletion with recent re-authentication
- Firestore profile record owned by the authenticated user
- No continuous location tracking

## Firebase setup
Create a Firebase project and add Android app package `ai.alert.app`. Download `google-services.json` into `app/google-services.json` (never commit it).

Enable:
1. Authentication -> Sign-in method -> Email/Password
2. Firestore Database
3. Cloud Messaging
4. Cloud Functions

Deploy backend:
```bash
cd functions
npm install
npm run build
cd ..
firebase use YOUR_FIREBASE_PROJECT_ID
firebase deploy --only firestore:rules,functions
```

## Two-phone V1 test
1. Put your Firebase `google-services.json` in `app/`.
2. Build the APK with the Firebase config present.
3. Install the same APK on Phone A and Phone B.
4. Create a separate account on each phone.
5. Verify both email addresses if desired.
6. Allow notification and location permissions.
7. Open alert.ai on both phones so their nearby presence is refreshed.
8. Keep them within 5 km.
9. Press FAST on Phone A.
10. Phone B should receive an FCM FAST notification.
11. Tap the notification and verify alert.ai opens.
