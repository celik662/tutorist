/* eslint-disable no-console */

// ── Firebase Functions v2
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const functions = require("firebase-functions");
const admin = require("firebase-admin");

// Node 18+/20: fetch global
admin.initializeApp();
const db = admin.firestore();
const REGION = "europe-west1";

/* ====================================================================================
 * Ortak yardımcılar
 * ==================================================================================== */
function safeServerTimestamp() {
  const fv = admin.firestore && admin.firestore.FieldValue;
  return fv && typeof fv.serverTimestamp === "function"
    ? fv.serverTimestamp()
    : new Date();
}
function tsPlusMinutes(m) {
  return admin.firestore.Timestamp.fromDate(new Date(Date.now() + m * 60 * 1000));
}

/* Kullanıcıların FCM tokenlarını topla (users/{uid}.fcmTokens) */
async function tokensForUsers(uids = []) {
  const uniq = Array.from(new Set(uids.filter(Boolean)));
  const tokens = [];
  await Promise.all(
    uniq.map(async (uid) => {
      const snap = await db.collection("users").doc(uid).get();
      const arr = (snap.data()?.fcmTokens || []).filter(Boolean);
      tokens.push(...arr);
    })
  );
  return Array.from(new Set(tokens)).filter(Boolean);
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
 *  bookings/{id}.meeting alanını {provider:'daily',roomName,roomUrl} ile yazar. */
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

  // 2) Yoksa oluştur
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
          enable_screenshare: true,
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
  { region: REGION, document: "bookings/{id}" },
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
 * Ders hatırlatmaları: T-60 ve T-10
 *  - status=='accepted' ve startAt yaklaşan dersleri tarar
 *  - Hem öğrenciye hem öğretmene FCM gönderir
 *  - bookings/{id}.reminders.{sent60|sent10} = true (idempotent)
 * ==================================================================================== */
async function sendReminderIfNotSent(bookingRef, bData, flag, minutes) {
  // Aynı bildirimi iki kez göndermemek için önce bayrağı transaction ile yaz
  const res = await db.runTransaction(async (tx) => {
    const doc = await tx.get(bookingRef);
    const curr = doc.data() || {};
    const already = curr?.reminders?.[flag] === true;
    if (already) return { skip: true };
    tx.set(bookingRef, { reminders: { [flag]: true }, updatedAt: safeServerTimestamp() }, { merge: true });
    return { skip: false };
  });
  if (res.skip) return;

  const tokens = await tokensForUsers([bData.teacherId, bData.studentId]);
  if (!tokens.length) {
    console.log("[reminder]", bookingRef.id, flag, "no tokens");
    return;
  }

  const startDate = (bData.startAt?.toDate?.() ?? new Date(bData.startAt));
  const hhmm = startDate.toISOString().substring(11, 16);
  const title = minutes === 60 ? "1 saat sonra dersin var" : "10 dk sonra ders başlıyor";
  const body  = `${bData.subjectName || "Ders"} • ${hhmm}`;

  const link = bData?.meeting?.roomUrl || "";

  await admin.messaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    data: {
      kind: "lessonReminder",
      bookingId: bookingRef.id,
      roomUrl: link,
      startAt: startDate.toISOString(),
      subjectName: bData.subjectName || ""
    },
    android: {
      priority: "high",
      notification: { clickAction: "OPEN_LESSON" } // opsiyonel; sistem bildirimi için
    }
  });

  console.log("[reminder] sent", { bookingId: bookingRef.id, flag, tokens: tokens.length });
}

exports.remindUpcomingLessons = onSchedule(
  { region: REGION, schedule: "every 1 minutes", timeZone: "Europe/Berlin" },
  async () => {
    const now = admin.firestore.Timestamp.now();

    // T-60: şimdi → +60 dk
    const in60 = tsPlusMinutes(60);
    const q60 = await db.collection("bookings")
      .where("status", "==", "accepted")
      .where("startAt", ">=", now)
      .where("startAt", "<=", in60)
      .get();
    for (const doc of q60.docs) {
      await sendReminderIfNotSent(doc.ref, doc.data(), "sent60", 60);
    }

    // T-10: şimdi → +10 dk
    const in10 = tsPlusMinutes(10);
    const q10 = await db.collection("bookings")
      .where("status", "==", "accepted")
      .where("startAt", ">=", now)
      .where("startAt", "<=", in10)
      .get();
    for (const doc of q10.docs) {
      await sendReminderIfNotSent(doc.ref, doc.data(), "sent10", 10);
    }
  }
);

/* ====================================================================================
 * getJoinToken: Katıl → token ver (zaman penceresi, taraf kontrolü)
 *  - Oda yoksa oluşturur
 * ==================================================================================== */
exports.getJoinToken = onCall({ region: REGION }, async (req) => {
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
exports.ping = onCall({ region: REGION }, (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
  return { ok: true };
});


fabUpcoming.setVisibility(View.VISIBLE);
fabUpcoming.setEnabled(false);
fabUpcoming.setText("—:—");

query.get()
  .addOnSuccessListener(snap -> {
    if (!snap.isEmpty()) {
      Timestamp ts = snap.getDocuments().get(0).getTimestamp("startAt");
      fabUpcoming.setText(formatHHmm(ts));
      fabUpcoming.setEnabled(true);
    } else {
      fabUpcoming.setText("Yok");
    }
  })
  .addOnFailureListener(e -> {
    // Index building vs. durumunda da görünür kalsın
    fabUpcoming.setText("…");
    fabUpcoming.setEnabled(false);
    Log.w("Upcoming", "Query failed", e);
  });
