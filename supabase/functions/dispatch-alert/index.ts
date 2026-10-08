import { withSupabase } from "npm:@supabase/server@1"
import { importPKCS8, SignJWT } from "npm:jose@6.1.0"

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
const FCM_TOKEN_URL = "https://oauth2.googleapis.com/token"

function distanceKm(aLat: number, aLon: number, bLat: number, bLon: number) {
  const r = 6371
  const dLat = (bLat - aLat) * Math.PI / 180
  const dLon = (bLon - aLon) * Math.PI / 180
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(aLat * Math.PI / 180) * Math.cos(bLat * Math.PI / 180) * Math.sin(dLon / 2) ** 2
  return 2 * r * Math.asin(Math.sqrt(x))
}

async function getGoogleAccessToken(sa: any) {
  const key = await importPKCS8(sa.private_key, "RS256")
  const now = Math.floor(Date.now() / 1000)
  const assertion = await new SignJWT({ scope: FCM_SCOPE }).setProtectedHeader({ alg: "RS256", typ: "JWT" }).setIssuer(sa.client_email).setAudience(FCM_TOKEN_URL).setIssuedAt(now).setExpirationTime(now + 3600).sign(key)
  const response = await fetch(FCM_TOKEN_URL, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }) })
  if (!response.ok) throw new Error("Google OAuth failed: " + await response.text())
  return (await response.json()).access_token as string
}

export default {
  fetch: withSupabase({ auth: "user" }, async (req, ctx) => {
    try {
      const { alert_id } = await req.json()
      if (!alert_id) return Response.json({ error: "alert_id is required" }, { status: 400 })
      const { data: alert, error: alertError } = await ctx.supabaseAdmin.from("alerts").select("id,sender_id,latitude,longitude").eq("id", alert_id).single()
      if (alertError || !alert) return Response.json({ error: "Alert not found" }, { status: 404 })
      if (alert.sender_id !== ctx.userClaims?.sub) return Response.json({ error: "Not your alert" }, { status: 403 })
      const { data: profiles, error: profileError } = await ctx.supabaseAdmin.from("profiles").select("id,fcm_token,latitude,longitude,display_name").not("fcm_token", "is", null).not("latitude", "is", null).not("longitude", "is", null)
      if (profileError) throw profileError
      const recipients = (profiles ?? []).filter((p: any) => p.id !== alert.sender_id).map((p: any) => ({ ...p, distanceKm: distanceKm(alert.latitude, alert.longitude, p.latitude, p.longitude) })).filter((p: any) => p.distanceKm <= 5)
      const saRaw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON")
      if (!saRaw) return Response.json({ error: "FCM backend is not configured" }, { status: 503 })
      const sa = JSON.parse(saRaw)
      const accessToken = await getGoogleAccessToken(sa)
      let sent = 0
      const receipts: any[] = []
      for (const recipient of recipients) {
        const fcmResponse = await fetch("https://fcm.googleapis.com/v1/projects/" + sa.project_id + "/messages:send", {
          method: "POST",
          headers: { authorization: "Bearer " + accessToken, "content-type": "application/json" },
          body: JSON.stringify({ message: { token: recipient.fcm_token, data: { type: "SEND_ALERT", alertId: alert.id, senderId: alert.sender_id, latitude: String(alert.latitude), longitude: String(alert.longitude), distanceKm: recipient.distanceKm.toFixed(1) }, android: { priority: "HIGH" } } })
        })
        if (fcmResponse.ok) { sent++; receipts.push({ alert_id: alert.id, receiver_id: recipient.id, status: "SENT" }) }
      }
      if (receipts.length) await ctx.supabaseAdmin.from("alert_receipts").upsert(receipts, { onConflict: "alert_id,receiver_id" })
      return Response.json({ alert_id: alert.id, nearby_users: recipients.length, notifications_sent: sent })
    } catch (error) {
      console.error(error)
      return Response.json({ error: error instanceof Error ? error.message : "Dispatch failed" }, { status: 500 })
    }
  }),
}