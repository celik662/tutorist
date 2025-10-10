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
