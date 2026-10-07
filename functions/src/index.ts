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

        if (
          !token ||
          !Number.isFinite(userLat) ||
          !Number.isFinite(userLon)
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
