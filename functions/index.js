// functions/index.js
const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

async function tokensOf(uid) {
  const snap = await admin.firestore()
      .collection("users").doc(uid)
      .collection("devices").get();
  const arr = [];
  snap.forEach(d => {
    const t = d.get("token");
    if (t) arr.push(t);
  });
  return arr;
}

// 1) Yeni talep: öğretmene bildir
exports.onBookingCreate = functions.firestore
  .document("bookings/{bookingId}")
  .onCreate(async (snap, ctx) => {
    const b = snap.data();
    const teacherId = b.teacherId;
    const title = "Yeni rezervasyon talebi";
    const body  = `${b.studentName || "Öğrenci"} - ${b.date} ${String(b.hour).padStart(2,"0")}:00`;

    const tokens = await tokensOf(teacherId);
    if (!tokens.length) return null;

    const msg = {
      tokens,
      notification: { title, body },
      data: {
        kind: "bookingCreated",
        bookingId: ctx.params.bookingId || "",
      }
    };
    return admin.messaging().sendMulticast(msg);
  });

// 2) Statü değişti: öğrenciye bildir
exports.onBookingStatusChange = functions.firestore
  .document("bookings/{bookingId}")
  .onUpdate(async (chg, ctx) => {
    const before = chg.before.data();
    const after  = chg.after.data();
    if (!before || !after) return null;
    if (before.status === after.status) return null;

    const studentId = after.studentId;
    let title = "Rezervasyon güncellendi";
    let body  = `${after.date} ${String(after.hour).padStart(2,"0")}:00 → ${after.status}`;

    if (after.status === "accepted") {
      title = "Dersin kabul edildi 🎉";
      body  = `${after.date} ${String(after.hour).padStart(2,"0")}:00 • ${after.subjectName || after.subjectId}`;
    } else if (after.status === "declined") {
      title = "Ders talebi reddedildi";
    } else if (after.status === "cancelled") {
      title = "Talep iptal edildi";
    }

    const tokens = await tokensOf(studentId);
    if (!tokens.length) return null;

    const msg = {
      tokens,
      notification: { title, body },
      data: {
        kind: "bookingStatus",
        bookingId: ctx.params.bookingId || "",
        status: String(after.status || ""),
      }
    };
    return admin.messaging().sendMulticast(msg);
  });
