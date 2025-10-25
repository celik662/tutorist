/* eslint-disable no-console */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");

if (!admin.apps.length) admin.initializeApp();
const db = admin.firestore();
const REGION = "europe-west1";

/* ---------- helpers ---------- */
const safeServerTimestamp =
  () => (admin.firestore.FieldValue && admin.firestore.FieldValue.serverTimestamp()) || new Date();

/** uid listesi için tokenları ve token->uid map'ini getirir */
async function tokensForUsersWithMap(uids = []) {
  const uniqUids = Array.from(new Set(uids.filter(Boolean)));
  const ownerMap = {}; // token -> uid
  const tokens = new Set();
  await Promise.all(
    uniqUids.map(async (uid) => {
      const snap = await db.collection("users").doc(uid).get();
      const arr = (snap.data()?.fcmTokens || []).filter(Boolean);
      console.log("[TOKENS] uid:", uid, "count:", arr.length);
      arr.forEach((t) => { tokens.add(t); ownerMap[t] = uid; });
    })
  );
  const tokArr = Array.from(tokens);
  console.log("[TOKENS] unique:", tokArr.length, tokArr.map(t => t.slice(0,12) + "…"));
  return { tokens: tokArr, ownerMap };
}

/** Geçersiz tokenları users/{uid}.fcmTokens'tan temizle */
async function pruneInvalidTokens(invalidTokens = [], ownerMap = {}) {
  const byUser = new Map();
  for (const t of invalidTokens) {
    const uid = ownerMap[t];
    if (!uid) continue;
    if (!byUser.has(uid)) byUser.set(uid, new Set());
    byUser.get(uid).add(t);
  }
  if (!byUser.size) return { updatedUsers: 0, removed: 0 };
  const batch = db.batch();
  for (const [uid, set] of byUser.entries()) {
    const ref = db.collection("users").doc(uid);
    for (const tok of set) {
      batch.update(ref, { fcmTokens: admin.firestore.FieldValue.arrayRemove(tok) });
    }
  }
  await batch.commit();
  let removed = 0;
  for (const set of byUser.values()) removed += set.size;
  return { updatedUsers: byUser.size, removed };
}

/** Çoklu gönderim yardımcıları (tek yerden log + pruning) */
async function sendMulticastToUids(uids, { title, data = {}, clickAction = "OPEN_BOOKING_DETAILS" }) {
  const { tokens, ownerMap } = await tokensForUsersWithMap(uids);
  if (!tokens.length) {
    console.log("[PUSH] no tokens for uids", uids);
    return { successCount: 0, failureCount: 0, responses: [] };
  }

  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    notification: { title }, // body yok
    data,
    android: { priority: "high", notification: { channelId: "booking_updates", clickAction } }
  });

  res.responses.forEach((r, i) => {
    if (!r.success) {
      console.warn("[PUSH][FAIL]", tokens[i].slice(0,12) + "…", r.error?.code, r.error?.message);
    }
  });
  console.log("[PUSH] ok:", res.successCount, "fail:", res.failureCount);

  const invalid = res.responses
    .map((r, i) => (!r.success ? { t: tokens[i], c: r.error?.code } : null))
    .filter(Boolean)
    .filter((x) => x.c === "messaging/registration-token-not-registered" || x.c === "messaging/invalid-registration-token")
    .map((x) => x.t);
  if (invalid.length) {
    console.log("[PUSH] pruning invalid:", invalid.length);
    await pruneInvalidTokens(invalid, ownerMap);
  }

  return res;
}

/* =============== TRIGGERLAR =============== */

/** 1) Yeni rezervasyon isteği -> ÖĞRETMENE bildir */
exports.onBookingCreated = onDocumentCreated(
  { region: REGION, document: "bookings/{id}" },
  async (event) => {
    const b = event.data?.data();
    if (!b || b.status !== "pending") return;

    const ref = event.data.ref;
    if (b?.notifications?.createdToTeacher) return; // idempotent

    const title = "Yeni ders talebi";
    const res = await sendMulticastToUids([b.teacherId], {
      title,
      data: {
        kind: "bookingCreated",
        bookingId: ref.id,
        startAt: (b.startAt?.toDate?.() ?? new Date(b.startAt)).toISOString(),
        subjectName: b.subjectName || "",
      },
      clickAction: "OPEN_BOOKING_REQUESTS",
    });
    console.log("[BOOKING_CREATED][PUSH] ok:", res.successCount, "fail:", res.failureCount);

    await ref.set({
      notifications: { ...(b.notifications || {}), createdToTeacher: true },
      updatedAt: safeServerTimestamp()
    }, { merge: true });
    console.log("[BOOKING_CREATED] notified teacher", ref.id);
  }
);

/** 2) Onaylandı -> ÖĞRENCİYE bildir */
exports.onBookingAccepted = onDocumentUpdated(
  { region: REGION, document: "bookings/{id}" },
  async (event) => {
    const before = event.data.before.data();
    const after  = event.data.after.data();
    if (!before || !after) return;
    if (!(before.status === "pending" && after.status === "accepted")) return;

    const ref = event.data.after.ref;
    if (after?.notifications?.acceptedToStudent) return;

    const title = "Rezervasyonun onaylandı";
    const res = await sendMulticastToUids([after.studentId], {
      title,
      data: {
        kind: "bookingAccepted",
        bookingId: ref.id,
        startAt: (after.startAt?.toDate?.() ?? new Date(after.startAt)).toISOString(),
        subjectName: after.subjectName || "",
      }
    });
    console.log("[BOOKING_ACCEPTED][PUSH] ok:", res.successCount, "fail:", res.failureCount);

    await ref.set({
      notifications: { ...(after.notifications || {}), acceptedToStudent: true },
      updatedAt: safeServerTimestamp()
    }, { merge: true });
    console.log("[BOOKING_ACCEPTED] notified student", ref.id);
  }
);

/** 3) Reddedildi/İptal -> ÖĞRENCİYE bildir (tek tetikleyici) */
exports.onBookingRejectedOrDeclined = onDocumentUpdated(
  { region: REGION, document: "bookings/{id}" },
  async (event) => {
    const before = event.data.before.data();
    const after  = event.data.after.data();
    if (!(before && after)) return;

    // pending|accepted → rejected|declined
    const becameDeclinedOrRejected =
      (["pending","accepted"].includes(before.status)) &&
      (after.status === "rejected" || after.status === "declined");
    if (!becameDeclinedOrRejected) return;

    const ref = event.data.after.ref;

    // İdempotent (eski ve yeni isimler)
    if (after?.notifications?.rejectedToStudent || after?.notifications?.declinedToStudent) return;

    const isDeclined = after.status === "declined";
    const title = isDeclined ? "Rezervasyonun iptal edildi" : "Rezervasyonun reddedildi";

    const res = await sendMulticastToUids([after.studentId], {
      title,
      data: {
        kind: isDeclined ? "bookingDeclined" : "bookingRejected",
        bookingId: ref.id,
        startAt: (after.startAt?.toDate?.() ?? new Date(after.startAt)).toISOString(),
        subjectName: after.subjectName || "",
      }
    });
    console.log("[BOOKING_REJECTED/DECLINED][PUSH] ok:", res.successCount, "fail:", res.failureCount);

    await ref.set({
      notifications: { ...(after.notifications || {}), rejectedToStudent: true, declinedToStudent: true },
      updatedAt: safeServerTimestamp()
    }, { merge: true });

    console.log("[BOOKING_REJECTED/DECLINED] notified student", ref.id);
  }
);
// === JOIN TOKEN CALLABLE ===
exports.getJoinToken = onCall({ region: REGION }, async (req) => {
  const bookingId = String(req.data?.bookingId || "");
  if (!bookingId) throw new HttpsError("invalid-argument", "bookingId required");

  // (İsteğe göre burada bookings/{id} kontrolü yap)
  const snap = await db.collection("bookings").doc(bookingId).get();
  if (!snap.exists) throw new HttpsError("not-found", "booking not found");

  // Burada gerçek token üretimini yap (örn. Daily/Agora/Zoom vs).
  // Şimdilik stub dönelim ki NOT_FOUND kalksın:
  return { token: "stub-token", roomName: snap.data().roomName || bookingId };
});

/* ====== TEST CALLABLE’LAR ====== */
exports.sendTestDataMsg = onCall({ region: REGION }, async (req) => {
  const token = req.data?.token;
  if (!token) throw new HttpsError("invalid-argument", "token required");

  await admin.messaging().send({
    token,
    android: { priority: "high", notification: { channelId: "booking_updates" } },
    notification: { title: "Tutorist (Test)" }, // body yok
    data: { type: "test", bookingId: "test123" },
  });
  return { ok: true };
});

exports.debugSendReminder = onCall({ region: REGION }, async (req) => {
  const bookingId = String(req.data?.bookingId || "");
  const minutes = Number(req.data?.minutes ?? 0);
  if (!bookingId) throw new HttpsError("invalid-argument", "bookingId required");
  if (!Number.isFinite(minutes)) throw new HttpsError("invalid-argument", "minutes invalid");
  console.log("debugSendReminder", { bookingId, minutes });
  return { ok: true, bookingId, minutes };
});

/* ====== DAILY.JS — JOIN TOKEN ====== */
exports.dailyGetJoinToken = onCall({ region: REGION }, async (req) => {
  // İsteği yapan kullanıcı
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "auth required");

  const bookingId = String(req.data?.bookingId || "");
  if (!bookingId) throw new HttpsError("invalid-argument", "bookingId required");

  // Rezervasyonu oku (kimlik kontrolü istersen burada yap)
  const snap = await db.collection("bookings").doc(bookingId).get();
  if (!snap.exists) throw new HttpsError("not-found", "booking not found");

  const b = snap.data();
  // (İsteğe bağlı) Sadece ilgili öğretmen/öğrenci girsin:
  // if (![b.studentId, b.teacherId].includes(uid)) {
  //   throw new HttpsError("permission-denied", "not allowed");
  // }

  // Odanın adını belirle (örn: bookings/{id} alanından)
  // yoksa deterministik bir isim üret
  const roomName = b.dailyRoom || `booking_${bookingId}`;

  // Daily API anahtarını çek (Functions config/secrets)
  const DAILY_API_KEY = process.env.DAILY_API_KEY || process.env.DAILY_API_TOKEN;
  if (!DAILY_API_KEY) {
    console.error("Missing DAILY_API_KEY");
    throw new HttpsError("failed-precondition", "Server missing DAILY_API_KEY");
  }

  // Node 18+ ortamında fetch global; yoksa node-fetch/axios kullan
  const resp = await fetch("https://api.daily.co/v1/meeting-tokens", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${DAILY_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      properties: {
        room_name: roomName,
        // İsteğe bağlı özellikler:
        // is_owner: false,
        // user_name: b.studentId === uid ? "Öğrenci" : "Öğretmen",
        // exp: Math.floor(Date.now()/1000) + 60*60  // 1 saat
      },
    }),
  });

  if (!resp.ok) {
    const text = await resp.text().catch(()=>"");
    console.error("Daily token fail", resp.status, text);
    if (resp.status === 404) throw new HttpsError("not-found", "Daily room not found");
    if (resp.status === 401 || resp.status === 403) throw new HttpsError("permission-denied", "Daily auth error");
    throw new HttpsError("internal", "Daily token error");
  }

  const json = await resp.json();
  const token = json?.token;
  if (!token) throw new HttpsError("internal", "No token in response");

  return { token, roomName };
});


/* ====== ZAMANLI HATIRLATICILAR (10 dk & 60 dk) ======
   Her dakika çalışır; 10±1 dk ve 60±1 dk pencerelerinde başlayacak ACCEPTED
   derslerde öğrenci + öğretmene bildirim yollar. */
exports.reminderSweep = onSchedule(
  { region: REGION, schedule: "every 1 minutes", timeZone: "Europe/Istanbul" },
  async () => {
    const now = Date.now();
    const TOL = 60 * 1000; // 1 dk tolerans

    async function sweepWindow(minutes, flagKey) {
      const startLo = new Date(now + minutes * 60 * 1000 - TOL);
      const startHi = new Date(now + minutes * 60 * 1000 + TOL);

      const snap = await db.collection("bookings")
        .where("status", "==", "accepted")
        .where("startAt", ">=", startLo)
        .where("startAt", "<",  startHi)
        .get();

      if (snap.empty) return;
      console.log(`[REMINDER][${minutes}] candidates:`, snap.size);

      for (const doc of snap.docs) {
        const b = doc.data();
        const ref = doc.ref;
        const n = b.notifications || {};
        const toStudentKey = `${flagKey}ToStudent`;
        const toTeacherKey = `${flagKey}ToTeacher`;
        if (n[toStudentKey] && n[toTeacherKey]) continue;

        const startAtIso = (b.startAt?.toDate?.() ?? new Date(b.startAt)).toISOString();
        const title = minutes === 10 ? "Dersin birazdan başlıyor" : "Yaklaşan ders hatırlatması";

        const targets = [];
        if (!n[toStudentKey] && b.studentId) targets.push(b.studentId);
        if (!n[toTeacherKey] && b.teacherId) targets.push(b.teacherId);
        if (!targets.length) continue;

        const res = await sendMulticastToUids(targets, {
          title,
          data: {
            kind: "lessonReminder",
            bookingId: doc.id,
            startAt: startAtIso,
            subjectName: b.subjectName || "",
            minutes: String(minutes),
          },
          clickAction: "OPEN_BOOKING_DETAILS",
        });
        console.log(`[REMINDER][${minutes}] ${doc.id} ok:`, res.successCount, "fail:", res.failureCount);

        const patch = { notifications: { ...(n) }, updatedAt: safeServerTimestamp() };
        if (targets.includes(b.studentId)) patch.notifications[toStudentKey] = true;
        if (targets.includes(b.teacherId)) patch.notifications[toTeacherKey] = true;
        await ref.set(patch, { merge: true });
      }
    }

    await sweepWindow(60, "reminder60");
    await sweepWindow(10,  "reminder10");

    console.log("[REMINDER] sweep done");
  }
);

const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");
const db = admin.firestore();

exports.completionSweep = onSchedule(
  { region: REGION, schedule: "every 2 minutes", timeZone: "Europe/Istanbul" },
  async () => {
    const now = Date.now();
    const GRACE = 5 * 60 * 1000; // bitişten sonra 5 dk tolerans

    // 1) accepted & endAt < now-GRACE  -> completed
    {
      const snap = await db.collection("bookings")
        .where("status", "==", "accepted")
        .where("endAt", "<", new Date(now - GRACE))
        .get();

      if (!snap.empty) console.log("[COMPLETION] candidates:", snap.size);

      for (const doc of snap.docs) {
        await db.runTransaction(async (tr) => {
          const bRef = doc.ref;
          const b    = (await tr.get(bRef)).data();
          if (!b || b.status !== "accepted") return; // idempotent

          // booking -> completed
          tr.set(bRef, {
            status: "completed",
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          }, { merge: true });

          // slot lock temizliği (opsiyonel ama tavsiye)
          if (b.teacherId && b.date && Number.isFinite(b.hour)) {
            const lockId = `${b.teacherId}_${b.date}_${b.hour}`;
            tr.delete(db.collection("slotLocks").doc(lockId));
          }

          // completedCount agregasyonu (idempotent bayrakla)
          if (!b.stats?.countedCompleted) {
            const tRef = db.collection("teacherProfiles").doc(b.teacherId);
            const tSnap = await tr.get(tRef);
            const curr = (tSnap.data()?.completedCount || 0) + 1;
            tr.set(tRef, { completedCount: curr }, { merge: true });
            tr.set(bRef, { stats: { ...(b.stats || {}), countedCompleted: true } }, { merge: true });
          }
        });
      }
    }

    // 2) pending & endAt < now-GRACE -> expired
    {
      const snap = await db.collection("bookings")
        .where("status", "==", "pending")
        .where("endAt", "<", new Date(now - GRACE))
        .get();

      if (!snap.empty) console.log("[EXPIRE] candidates:", snap.size);

      const batch = db.batch();
      for (const doc of snap.docs) {
        const b = doc.data();
        batch.set(doc.ref, {
          status: "expired",
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });

        // kilidi aç
        if (b.teacherId && b.date && Number.isFinite(b.hour)) {
          const lockId = `${b.teacherId}_${b.date}_${b.hour}`;
          batch.delete(db.collection("slotLocks").doc(lockId));
        }
      }
      if (!snap.empty) await batch.commit();
    }

    console.log("[SWEEP] done");
  }
);

const { onDocumentUpdated } = require("firebase-functions/v2/firestore");

exports.onBookingCompletedAgg = onDocumentUpdated(
  { region: REGION, document: "bookings/{id}" },
  async (event) => {
    const before = event.data.before.data();
    const after  = event.data.after.data();
    if (!before || !after) return;

    const becameCompleted = before.status !== "completed" && after.status === "completed";
    if (!becameCompleted) return;

    const ref = event.data.after.ref;
    await db.runTransaction(async (tr) => {
      const snap = await tr.get(ref);
      const b = snap.data();
      if (b?.stats?.countedCompleted) return; // idempotent

      const tRef = db.collection("teacherProfiles").doc(after.teacherId);
      const tSnap = await tr.get(tRef);
      const curr = (tSnap.data()?.completedCount || 0) + 1;
      tr.set(tRef, { completedCount: curr }, { merge: true });
      tr.set(ref, { stats: { ...(b?.stats || {}), countedCompleted: true } }, { merge: true });
    });
  }
);

