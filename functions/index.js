/* eslint-disable no-console */

// ── Firebase Functions v2
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const functions = require("firebase-functions");
const admin = require("firebase-admin");

// Node 18+/20: fetch global
admin.initializeApp();
const db = admin.firestore();

/* ====================================================================================
 * Ortak yardımcılar
 * ==================================================================================== */
function safeServerTimestamp() {
  const fv = admin.firestore && admin.firestore.FieldValue;
  return fv && typeof fv.serverTimestamp === "function"
    ? fv.serverTimestamp()
    : new Date();
}

function addDays(ms, days) {
  return ms + days * 24 * 60 * 60 * 1000;
}

/* ====================================================================================
 * DAILY (video) AYARLARI
 * ==================================================================================== */
const DAILY_API_KEY =
  process.env.DAILY_API_KEY || (functions.config().daily && functions.config().daily.key);
const DAILY_SUBDOMAIN =
  process.env.DAILY_SUBDOMAIN || (functions.config().daily && functions.config().daily.subdomain);

if (!DAILY_API_KEY || !DAILY_SUBDOMAIN) {
  console.warn("[Daily] Missing DAILY_API_KEY / DAILY_SUBDOMAIN");
}

/** bookingId için Daily odasını garanti eder; yoksa oluşturur ve
 *  bookings/{id}.meeting alanını {provider:'daily',roomName,roomUrl} ile yazar.
 *  Oda recording=cloud açık kurulur. */
async function ensureDailyRoomForBooking(bookingId) {
  if (!DAILY_API_KEY || !DAILY_SUBDOMAIN) {
    throw new HttpsError("failed-precondition", "Daily config missing (DAILY_API_KEY / DAILY_SUBDOMAIN).");
  }

  const roomName = `tutorist_${bookingId}`;
  const roomUrl  = `https://${DAILY_SUBDOMAIN}.daily.co/${roomName}`;

  // 1) Var mı diye GET
  let res = await fetch(`https://api.daily.co/v1/rooms/${roomName}`, {
    headers: { Authorization: `Bearer ${DAILY_API_KEY}` },
  });

  // 2) Yoksa oluştur (recording açık)
  if (!res.ok) {
    res = await fetch("https://api.daily.co/v1/rooms", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${DAILY_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        name: roomName,
        privacy: "private", // token şart
        properties: {
          enable_chat: true,
          start_video_off: true,
          start_audio_off: false,
          eject_at_room_exp: true,
        },
      }),
    });

    if (!res.ok) {
      const t = await res.text();
      throw new HttpsError("internal", `Daily room create failed: ${res.status} ${t}`);
    }
  }

  // Firestore'a meeting bilgisini işle (merge)
  await db.doc(`bookings/${bookingId}`).set(
    {
      meeting: { provider: "daily", roomName, roomUrl },
      updatedAt: safeServerTimestamp(),
    },
    { merge: true }
  );

  return { roomName, roomUrl };
}

/* ====================================================================================
 * (Opsiyonel) Status değişince oda kur
 * ==================================================================================== */
exports.onBookingAccepted = onDocumentUpdated(
  { region: "europe-west1", document: "bookings/{id}" },
  async (event) => {
    try {
      const before = event.data.before.data();
      const after  = event.data.after.data();
      if (!before || !after) return;
      if (!(before.status === "pending" && after.status === "accepted")) return;

      console.log("onBookingAccepted TRIGGER", {
        id: event.params.id, before: before.status, after: after.status,
      });

      await ensureDailyRoomForBooking(event.params.id);
    } catch (e) {
      console.error("onBookingAccepted error:", e);
    }
  }
);

/* ====================================================================================
 * Webhook: Daily’den recording hazır olduğunda kayıt bilgisini işaretle
 *  - Daily dashboard’da webhook URL olarak bu fonksiyonu gir (event türü: recording.ready)
 *  - Güvenlik için basit secret header doğrulaması ekledim (opsiyonel).
 * ==================================================================================== */

/* ====================================================================================
 * Scheduled cleanup: expiresAt geçmiş kayıtları Daily’den sil + Firestore’dan işaretle
 *  - Günde 1 kez çalışır
 * ==================================================================================== */

/* ====================================================================================
 * getJoinToken: Katıl → token ver (zaman penceresi, taraf kontrolü)
 *  - Oda yoksa oluşturur
 * ==================================================================================== */
exports.getJoinToken = onCall({ region: "europe-west1" }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Login required");
  const bookingId = req.data && req.data.bookingId;
  if (!bookingId) throw new HttpsError("invalid-argument", "bookingId missing");

  console.log("getJoinToken CALL", { uid: req.auth.uid, bookingId });

  const snap = await db.doc(`bookings/${bookingId}`).get();
  if (!snap.exists) throw new HttpsError("not-found", "Booking not found");
  const b = snap.data();

  const uid = req.auth.uid;
  if (![b.studentId, b.teacherId].includes(uid)) {
    throw new HttpsError("permission-denied", "Not allowed");
  }

  const debug = !!(req.data && req.data.debug);
  const IS_EMU = process.env.FUNCTIONS_EMULATOR === "true" || !!process.env.FIREBASE_EMULATOR_HUB;

  const now   = Date.now();
  const start = (b.startAt?.toDate?.() ?? new Date(b.startAt)).getTime();
  const end   = (b.endAt?.toDate?.()   ?? new Date(b.endAt)).getTime();
  const windowStart = start - 5 * 60 * 1000;
  const windowEnd   = end   + 10 * 60 * 1000;

  if (!(IS_EMU || debug)) {
    if (now < windowStart) throw new HttpsError("failed-precondition", "Not started yet");
    if (now > windowEnd)   throw new HttpsError("failed-precondition", "Time window passed");
  } else {
    console.log("getJoinToken DEBUG/BYPASS time window", { now, start, end });
  }

  // Odayı garanti et
  let roomName = b?.meeting?.roomName;
  let roomUrl  = b?.meeting?.roomUrl;
  if (!roomName || !roomUrl) {
    const ensured = await ensureDailyRoomForBooking(bookingId);
    roomName = ensured.roomName;
    roomUrl  = ensured.roomUrl;
  }

  const isTeacher = uid === b.teacherId;

  // Daily meeting-token
  const tokenRes = await fetch("https://api.daily.co/v1/meeting-tokens", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${DAILY_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      properties: {
        room_name: roomName,
        is_owner: isTeacher,
        eject_at_token_exp: true,
        exp: Math.floor((now + 90 * 60 * 1000) / 1000),
      },
    }),
  });

  if (!tokenRes.ok) {
    const t = await tokenRes.text();
    console.error("Daily token error:", tokenRes.status, t);
    throw new HttpsError("internal", `Daily token error: ${tokenRes.status} ${t}`);
  }

  const tokenJson = await tokenRes.json();
  return { roomUrl, token: tokenJson.token };
});

/* Basit ping */
exports.ping = onCall({ region: "europe-west1" }, (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
  return { ok: true };
});


