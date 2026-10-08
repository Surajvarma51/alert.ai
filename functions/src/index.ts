import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { geohashQueryBounds, distanceBetween } from "geofire-common";

initializeApp();

const db = getFirestore();
const messaging = getMessaging();

const ALERT_RADIUS_KM = 5;
const MAX_TOKEN_BATCH = 500;
const MAX_PRESENCE_AGE_MS = 24 * 60 * 60 * 1000;

export const dispatchFastAlert = onDocumentCreated(
  {
    document: "alerts/{alertId}",
    region: "asia-south1",
    retry: true
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const alert = snapshot.data();
    if (alert.type !== "FAST") return;

    const latitude = Number(alert.latitude);
    const longitude = Number(alert.longitude);
    const senderId = String(alert.senderId ?? "");

    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      logger.warn("Ignoring FAST alert with invalid coordinates", {
        alertId: event.params.alertId
      });
      return;
    }

    const bounds = geohashQueryBounds(
      [latitude, longitude],
      ALERT_RADIUS_KM * 1000
    );

    const userSnapshots = await Promise.all(
      bounds.map(([start, end]) =>
        db.collection("users")
          .orderBy("geohash")
          .startAt(start)
          .endAt(end)
          .get()
      )
    );

    const recipients = new Map<string, { token: string; distanceKm: number }>();

    for (const snap of userSnapshots) {
      for (const doc of snap.docs) {
        if (doc.id === senderId) continue;

        const data = doc.data();
        const token = typeof data.fcmToken === "string" ? data.fcmToken : "";
        const userLat = Number(data.latitude);
        const userLon = Number(data.longitude);
        const lastSeen = data.lastSeen?.toMillis?.() ?? 0;

        if (
          !token ||
          !Number.isFinite(userLat) ||
          !Number.isFinite(userLon) ||
          !lastSeen ||
          Date.now() - lastSeen > MAX_PRESENCE_AGE_MS
        ) {
          continue;
        }

        const distanceKm = distanceBetween(
          [latitude, longitude],
          [userLat, userLon]
        );

        if (distanceKm <= ALERT_RADIUS_KM) {
          recipients.set(doc.id, { token, distanceKm });
        }
      }
    }

    if (recipients.size === 0) {
      logger.info("FAST alert has no nearby recipients", {
        alertId: event.params.alertId
      });
      return;
    }

    const entries = Array.from(recipients.entries());

    for (let i = 0; i < entries.length; i += MAX_TOKEN_BATCH) {
      const batch = entries.slice(i, i + MAX_TOKEN_BATCH);

      const messages = batch.map(([userId, recipient]) => ({
        token: recipient.token,
        data: {
          title: "⚡ FAST ALERT",
          body: "A FAST alert was reported near you.",
          alertId: event.params.alertId,
          distanceKm: recipient.distanceKm.toFixed(1)
        },
        android: {
          priority: "high" as const
        }
      }));

      const response = await messaging.sendEach(messages);

      response.responses.forEach((result, index) => {
        if (!result.success) {
          logger.warn("FCM send failed", {
            userId: batch[index][0],
            error: result.error?.message
          });
        }
      });
    }

    await snapshot.ref.update({
      notificationStatus: "dispatched",
      recipientCount: recipients.size
    });
  }
);

import { defineSecret } from "firebase-functions/params";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { Resend } from "@resend/node";
import { createHash, randomInt } from "node:crypto";

const RESEND_API_KEY = defineSecret("RESEND_API_KEY");
const RESEND_FROM_EMAIL = defineSecret("RESEND_FROM_EMAIL");
const OTP_PEPPER = defineSecret("OTP_PEPPER");

function hashOtp(uid: string, otp: string): string {
  return createHash("sha256")
    .update(`${uid}:${otp}:${OTP_PEPPER.value()}`)
    .digest("hex");
}

function requireAuth(request: { auth?: { uid?: string | null } | null }): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in required");
  return uid;
}

export const requestEmailOtp = onCall(
  { region: "asia-south1", secrets: [RESEND_API_KEY, RESEND_FROM_EMAIL, OTP_PEPPER] },
  async (request) => {
    const uid = requireAuth(request);
    const userRecord = await import("firebase-admin/auth").then(({ getAuth }) => getAuth().getUser(uid));
    const email = userRecord.email;
    if (!email) throw new HttpsError("failed-precondition", "This account has no email address");

    const existing = await db.collection("emailVerificationChallenges").doc(uid).get();
    const existingData = existing.data();
    const now = Date.now();
    if (existingData?.lastSentAt?.toMillis && now - existingData.lastSentAt.toMillis() < 60_000) {
      throw new HttpsError("resource-exhausted", "Please wait before requesting another code");
    }

    const otp = String(randomInt(100000, 1000000));
    const challenge = {
      otpHash: hashOtp(uid, otp),
      expiresAt: new Date(now + 10 * 60_000),
      lastSentAt: new Date(now),
      attempts: 0
    };
    await db.collection("emailVerificationChallenges").doc(uid).set(challenge);

    const resend = new Resend(RESEND_API_KEY.value());
    const result = await resend.emails.send({
      from: RESEND_FROM_EMAIL.value(),
      to: email,
      subject: "Verify your alert.ai email",
      html: `<h2>Verify your alert.ai email</h2><p>Your verification code is:</p><p style="font-size:32px;font-weight:700;letter-spacing:8px">${otp}</p><p>This code expires in 10 minutes.</p><p>If you did not create this account, you can ignore this email.</p>`
    });
    if (result.error) {
      await db.collection("emailVerificationChallenges").doc(uid).delete();
      throw new HttpsError("internal", "Could not send verification email");
    }
    return { sent: true };
  }
);

export const verifyEmailOtp = onCall(
  { region: "asia-south1", secrets: [OTP_PEPPER] },
  async (request) => {
    const uid = requireAuth(request);
    const code = String(request.data?.code ?? "").trim();
    if (!/^\d{6}$/.test(code)) throw new HttpsError("invalid-argument", "Enter the 6-digit code");

    const ref = db.collection("emailVerificationChallenges").doc(uid);
    const snap = await ref.get();
    const data = snap.data();
    if (!data) throw new HttpsError("failed-precondition", "No verification code is active");
    if (data.expiresAt?.toMillis && data.expiresAt.toMillis() < Date.now()) {
      await ref.delete();
      throw new HttpsError("deadline-exceeded", "Verification code expired");
    }
    const attempts = Number(data.attempts ?? 0);
    if (attempts >= 5) {
      await ref.delete();
      throw new HttpsError("resource-exhausted", "Too many incorrect attempts");
    }
    if (data.otpHash !== hashOtp(uid, code)) {
      await ref.update({ attempts: attempts + 1 });
      throw new HttpsError("permission-denied", "Incorrect verification code");
    }

    const { getAuth } = await import("firebase-admin/auth");
    await getAuth().updateUser(uid, { emailVerified: true });
    await ref.delete();
    await db.collection("users").doc(uid).set(
      { emailVerified: true, updatedAt: new Date() },
      { merge: true }
    );
    return { verified: true };
  }
);

export const sendWelcomeEmail = onCall(
  { region: "asia-south1", secrets: [RESEND_API_KEY, RESEND_FROM_EMAIL] },
  async (request) => {
    const uid = requireAuth(request);
    const ref = db.collection("users").doc(uid);
    const profile = (await ref.get()).data() ?? {};
    if (profile.welcomeEmailSent === true) return { sent: false, alreadySent: true };

    const { getAuth } = await import("firebase-admin/auth");
    const userRecord = await getAuth().getUser(uid);
    if (!userRecord.email) throw new HttpsError("failed-precondition", "No email address on account");

    const resend = new Resend(RESEND_API_KEY.value());
    const result = await resend.emails.send({
      from: RESEND_FROM_EMAIL.value(),
      to: userRecord.email,
      subject: "Welcome to alert.ai",
      html: `<h2>Welcome to alert.ai, ${userRecord.displayName ?? "there"}!</h2><p>Your Google account is now connected to alert.ai.</p><p>You can optionally set an alert.ai password from <b>Account → Security</b> so you can also sign in with your email and password.</p><p><b>Never share your password or verification codes with anyone.</b></p><p>Welcome to the alert.ai community.</p>`
    });
    if (result.error) throw new HttpsError("internal", "Could not send welcome email");

    await ref.set({ welcomeEmailSent: true, updatedAt: new Date() }, { merge: true });
    return { sent: true };
  }
);
