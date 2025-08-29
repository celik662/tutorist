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


// --- YENİ: Bir öğrenci yeni yorum oluşturduğunda öğretmen puanlarını güncelle ---
const db = admin.firestore();

exports.onTeacherReviewCreated = functions.firestore
  .document("teacherReviews/{rid}")
  .onCreate(async (snap, ctx) => {
    const review = snap.data();
    const teacherId = String(review.teacherId || "");
    const rating = Math.max(1, Math.min(5, Number(review.rating || 0)));
    if (!teacherId || !rating) return null;

    const profRef = db.collection("teacherProfiles").doc(teacherId);

    // ratingSum, ratingCount ve ratingAvg alanlarını atomik olarak güncelle
    await db.runTransaction(async (tr) => {
      const profSnap = await tr.get(profRef);
      const prof = profSnap.exists ? profSnap.data() : {};

      const prevSum = Number(prof.ratingSum || 0);
      const prevCount = Number(prof.ratingCount || 0);

      const newSum = prevSum + rating;
      const newCount = prevCount + 1;
      const newAvg = Math.round((newSum / newCount) * 100) / 100; // 2 hane

      tr.set(
        profRef,
        {
          ratingSum: newSum,
          ratingCount: newCount,
          ratingAvg: newAvg,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    });

    return null;
  });

// --- Öğrenci yeni yorum eklediğinde öğretmen puanlarını güncelle ---
exports.onTeacherReviewCreated = functions.firestore
  .document("teacherReviews/{rid}")
  .onCreate(async (snap, ctx) => {
    const r = snap.data();
    const teacherId = r && r.teacherId;
    const rating = Number(r && r.rating || 0);
    if (!teacherId || rating <= 0) return null;

    const db = admin.firestore();
    const profRef = db.collection("teacherProfiles").doc(teacherId);

    await db.runTransaction(async (tr) => {
      const prof = await tr.get(profRef);
      const prevSum   = Number(prof.get("ratingSum")   || 0);
      const prevCount = Number(prof.get("ratingCount") || 0);

      const sum   = prevSum + rating;
      const count = prevCount + 1;
      const avg   = count ? sum / count : 0;

      tr.set(
        profRef,
        {
          ratingSum: sum,
          ratingCount: count,
          ratingAvg: Math.round(avg * 10) / 10, // 1 ondalık
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    });
    return null;
  });

// ---- 3) Yeni yorum eklendiğinde öğretmenin rating ortalamasını güncelle ----
// Yeni yorum eklendiğinde öğretmen profilinde ratingAvg / ratingCount güncelle
exports.onTeacherReviewCreate = functions.firestore
  .document("teacherReviews/{rid}")
  .onCreate(async (snap) => {
    const r = snap.data();
    const teacherId = r.teacherId;
    const rating = Number(r.rating || 0);
    if (!teacherId || !rating) return null;

    const db = admin.firestore();
    const profRef = db.collection("teacherProfiles").doc(teacherId);

    return db.runTransaction(async (tr) => {
      const prof = await tr.get(profRef);
      const prevAvg = Number(prof.get("ratingAvg") || 0);
      const prevCnt = Number(prof.get("ratingCount") || 0);

      const newCnt = prevCnt + 1;
      const newAvg = ((prevAvg * prevCnt) + rating) / newCnt;

      tr.set(profRef, {
        ratingAvg: newAvg,
        ratingCount: newCnt,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
    });
  });



  });
