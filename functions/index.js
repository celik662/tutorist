/* eslint-disable no-console */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();
const REGION = "europe-west1";

/* ------- helpers ------- */
const safeServerTimestamp = () =>
  (admin.firestore.FieldValue && admin.firestore.FieldValue.serverTimestamp()) || new Date();

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

/* ------- Daily ------- */
const DAILY_API_KEY =
  process.env.DAILY_API_KEY || (functions.config().daily && functions.config().daily.key);
const DAILY_SUBDOMAIN =
  process.env.DAILY_SUBDOMAIN || (functions.config().daily && functions.config().daily.subdomain);

async function ensureDailyRoomForBooking(bookingId) {
  if (!DAILY_API_KEY || !DAILY_SUBDOMAIN) {
    throw new HttpsError("failed-precondition", "Daily config missing");
  }
  const roomName = `tutorist_${bookingId}`;
  const roomUrl  = `https://${DAILY_SUBDOMAIN}.daily.co/${roomName}`;

  let res = await fetch(`https://api.daily.co/v1/rooms/${roomName}`, {
    headers: { Authorization: `Bearer ${DAILY_API_KEY}` },
  });
  if (!res.ok) {
    res = await fetch("https://api.daily.co/v1/rooms", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${DAILY_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ name: roomName, privacy: "private" }),
    });
    if (!res.ok) {
      const t = await res.text();
      throw new HttpsError("internal", `Daily room create failed: ${res.status} ${t}`);
    }
  }
  await db.doc(`bookings/${bookingId}`).set(
    { meeting: { provider: "daily", roomName, roomUrl }, updatedAt: safeServerTimestamp() },
    { merge: true }
  );
  return { roomName, roomUrl };
}

/* ------- triggers ------- */
exports.onBookingAccepted = onDocumentUpdated(
  { region: REGION, document: "bookings/{id}" },
  async (event) => {
    const before = event.data.before.data();
    const after  = event.data.after.data();
    if (!(before && after)) return;
    if (!(before.status === "pending" && after.status === "accepted")) return;
    await ensureDailyRoomForBooking(event.params.id);
  }
);

exports.remindUpcomingLessons = onSchedule(
  { region: REGION, schedule: "every 1 minutes", timeZone: "Europe/Berlin" },
  async () => {
    const now = Date.now();
    const windows = [60, 10].map((min) => {
      const target = now + min * 60_000;
      return {
        from: new Date(target - 120_000), // -2 dk
        to:   new Date(target + 120_000), // +2 dk
        flag: min === 60 ? "sent60" : "sent10",
        min,
      };
    });

    for (const w of windows) {
      console.log("Window", w.flag, w.from.toISOString(), w.to.toISOString());

      const snap = await db.collection("bookings")
        .where("status", "==", "accepted")
        .where("startAt", ">=", w.from)
        .where("startAt", "<=", w.to)
        // isteğe bağlı: index zaten var, sıralama debug için yardımcı
        .orderBy("startAt")
        .get();

      console.log("Match count", w.flag, snap.size);

      for (const doc of snap.docs) {
        const ref = doc.ref, b = doc.data();
        const txRes = await db.runTransaction(async (tx) => {
          const d = await tx.get(ref);
          const already = d.data()?.reminders?.[w.flag];
          if (already) return false;
          tx.set(ref, { reminders: { [w.flag]: true }, updatedAt: safeServerTimestamp() }, { merge: true });
          return true;
        });
        if (!txRes) continue;

        const tokens = await tokensForUsers([b.teacherId, b.studentId]);
        if (!tokens.length) continue;

        const start = (b.startAt?.toDate?.() ?? new Date(b.startAt));
        const hhmm = start.toISOString().slice(11, 16);
        const title = w.min === 60 ? "1 saat sonra dersin var" : "10 dk sonra ders başlıyor";
        const body  = `${b.subjectName || "Ders"} • ${hhmm}`;

        await admin.messaging().sendEachForMulticast({
          tokens,
          notification: { title, body },                 // <— OTO GÖRÜNÜR
          data: {
            kind: "lessonReminder",
            bookingId: ref.id,
            roomUrl: b?.meeting?.roomUrl || "",
            startAt: start.toISOString(),
            subjectName: b.subjectName || "",
          },
          android: { priority: "high", notification: { clickAction: "OPEN_LESSON" } }
        });
      }
    }
  }
);

/* ------- callable: getJoinToken ------- */
exports.getJoinToken = onCall({ region: REGION }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Login required");
  const bookingId = req.data?.bookingId;
  if (!bookingId) throw new HttpsError("invalid-argument", "bookingId missing");

  const snap = await db.doc(`bookings/${bookingId}`).get();
  if (!snap.exists) throw new HttpsError("not-found", "Booking not found");
  const b = snap.data();

  if (![b.studentId, b.teacherId].includes(req.auth.uid)) {
    throw new HttpsError("permission-denied", "Not allowed");
  }

  let roomName = b?.meeting?.roomName;
  let roomUrl  = b?.meeting?.roomUrl;
  if (!roomName || !roomUrl) ({ roomName, roomUrl } = await ensureDailyRoomForBooking(bookingId));

  const tokenRes = await fetch("https://api.daily.co/v1/meeting-tokens", {
    method: "POST",
    headers: { Authorization: `Bearer ${DAILY_API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      properties: { room_name: roomName, is_owner: req.auth.uid === b.teacherId, eject_at_token_exp: true,
        exp: Math.floor((Date.now() + 90 * 60 * 1000) / 1000) },
    }),
  });
  if (!tokenRes.ok) throw new HttpsError("internal", `Daily token error: ${tokenRes.status} ${await tokenRes.text()}`);
  const tokenJson = await tokenRes.json();
  return { roomUrl, token: tokenJson.token };
});

/* ------- callable: ping ------- */
exports.ping = onCall({ region: REGION }, (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
  return { ok: true };
});

/* ------- callable: debugSendReminder (manuel test) ------- */
exports.debugSendReminder = onCall({ region: REGION }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Login required");
  const bookingId = req.data?.bookingId;
  const minutes = Number(req.data?.minutes) || 10;
  if (!bookingId) throw new HttpsError("invalid-argument", "bookingId missing");

  const snap = await db.doc(`bookings/${bookingId}`).get();
  if (!snap.exists) throw new HttpsError("not-found", "Booking not found");
  const b = snap.data();

  const tokens = await tokensForUsers([b.teacherId, b.studentId]);
  if (!tokens.length) return { ok: true, tokens: 0 };

  const start = (b.startAt?.toDate?.() ?? new Date(b.startAt));
  const hhmm = start.toISOString().slice(11, 16);
  const title = minutes === 60 ? "1 saat sonra dersin var" : "10 dk sonra ders başlıyor";
  const body  = `${b.subjectName || "Ders"} • ${hhmm}`;

  await admin.messaging().sendEachForMulticast({
    tokens,
    notification: { title, body },    // <— OTO GÖRÜNÜR
    data: {
      kind: "lessonReminder", bookingId, roomUrl: b?.meeting?.roomUrl || "",
      startAt: start.toISOString(), subjectName: b.subjectName || "", debug: "true"
    },
    android: { priority: "high", notification: { clickAction: "OPEN_LESSON" } }
  });
  return { ok: true, tokens: tokens.length };
});

/* ------- callable: sendTestDataMsg (Android’te çağırdığın) ------- */
exports.sendTestDataMsg = onCall({ region: REGION }, async (req) => {
  const token = req.data?.token;
  if (!token) throw new HttpsError("invalid-argument", "token required");
  await admin.messaging().send({
    token,
    android: { priority: "high" },
    notification: {                         // <— OTO GÖRÜNÜR
      title: "Test dersi",
      body: "Bu bir test bildirimi",
    },
    data: {
      kind: "lessonReminder",
      bookingId: "test123",
      roomUrl: "https://example.com"
    }
  });
  return { ok: true };
});
