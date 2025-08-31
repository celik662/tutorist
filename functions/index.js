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


// functions/index.js
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const Iyzipay = require("iyzipay");
admin.initializeApp();

const db = admin.firestore();

// ---- IYZICO client (sandbox) ----
const IYZI_API_KEY = process.env.IYZI_API_KEY || (functions.config().iyzi && functions.config().iyzi.apikey);
const IYZI_SECRET  = process.env.IYZI_SECRET  || (functions.config().iyzi && functions.config().iyzi.secret);
const IYZI_BASE    = process.env.IYZI_BASE    || (functions.config().iyzi && functions.config().iyzi.base) || "https://sandbox-api.iyzipay.com";

const iyzipay = new Iyzipay({
  apiKey: IYZI_API_KEY,
  secretKey: IYZI_SECRET,
  uri: IYZI_BASE,
});

// basit yardımcılar
function slotId(teacherId, dateIso, hour) {
  return `${teacherId}_${dateIso}_${hour}`;
}
function toTwo(n) { return (n < 10 ? "0" : "") + n; }

// ------------------ 1) Checkout başlat ------------------
exports.iyziInitCheckout = functions.https.onCall(async (data, context) => {
  if (!context.auth) throw new functions.https.HttpsError("unauthenticated", "Giriş gerekli.");

  const studentId  = context.auth.uid;
  const {
    teacherId, subjectId, subjectName, dateIso, hour
  } = data || {};

  if (!teacherId || !subjectId || !subjectName || !dateIso || typeof hour !== "number") {
    throw new functions.https.HttpsError("invalid-argument", "Eksik parametre.");
  }

  // 0) slot hold ve fiyatı çek
  const profRef = db.collection("teacherProfiles").doc(teacherId);
  const userRef = db.collection("users").doc(studentId);

  // atomik: fiyatı çek + slot hold
  const { price, piId } = await db.runTransaction(async (tr) => {
    const profSnap = await tr.get(profRef);
    if (!profSnap.exists) throw new functions.https.HttpsError("not-found", "Öğretmen bulunamadı.");

    const subjectsMap = profSnap.get("subjectsMap") || {};
    const price = Number(subjectsMap[subjectId]);
    if (!price || isNaN(price)) {
      throw new functions.https.HttpsError("failed-precondition", "Bu ders için fiyat bulunamadı.");
    }

    const lockRef = db.collection("slotLocks").doc(slotId(teacherId, dateIso, hour));
    const lockSnap = await tr.get(lockRef);
    if (lockSnap.exists) {
      const status = lockSnap.get("status");
      if (status && status !== "cancelled") {
        throw new functions.https.HttpsError("already-exists", "Bu saat dolu.");
      }
    }

    // 15 dk hold
    const holdMs = 15 * 60 * 1000;
    tr.set(lockRef, {
      teacherId, studentId, date: dateIso, hour,
      status: "hold",
      holdUntil: admin.firestore.Timestamp.fromMillis(Date.now() + holdMs),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    // paymentIntent
    const piRef = db.collection("paymentIntents").doc();
    tr.set(piRef, {
      status: "pending",
      teacherId, studentId, subjectId, subjectName, dateIso, hour,
      price,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { price, piId: piRef.id };
  });

  // 1) Buyer bilgisi (temel)
  const userSnap = await userRef.get();
  const fullName = (userSnap.exists && userSnap.get("fullName")) || "Öğrenci";
  const phone    = (userSnap.exists && userSnap.get("phone")) || "0000000000";
  const email    = (userSnap.exists && userSnap.get("email")) || "student@example.com";

  // 2) Iyzico request
  const request = {
    locale: Iyzipay.LOCALE.TR,
    price: price.toFixed(2),
    paidPrice: price.toFixed(2),
    currency: Iyzipay.CURRENCY.TRY,
    basketId: piId, // callback'te bunu geri alırız
    paymentGroup: Iyzipay.PAYMENT_GROUP.LISTING,
    // Cloud Functions URL (aşağıdaki fonksiyon):
    callbackUrl: `https://YOUR_REGION-${process.env.GCLOUD_PROJECT}.cloudfunctions.net/iyziCallback`,

    buyer: {
      id: studentId,
      name: fullName.split(" ")[0] || "Ad",
      surname: fullName.split(" ").slice(1).join(" ") || "Soyad",
      gsmNumber: phone,
      email,
      identityNumber: "11111111110",   // sandbox için dummy
      registrationAddress: "Adres",
      ip: "85.34.78.112",
      city: "Istanbul",
      country: "Turkey",
    },
    shippingAddress: {
      contactName: fullName,
      city: "Istanbul",
      country: "Turkey",
      address: "Adres",
    },
    billingAddress: {
      contactName: fullName,
      city: "Istanbul",
      country: "Turkey",
      address: "Adres",
    },
    basketItems: [
      {
        id: subjectId,
        name: `${subjectName} (${dateIso} ${toTwo(hour)}:00)`,
        category1: "Özel Ders",
        itemType: Iyzipay.BASKET_ITEM_TYPE.VIRTUAL,
        price: price.toFixed(2),
      },
    ],
  };

  // 3) checkout formu başlat
  const initResp = await new Promise((resolve, reject) => {
    iyzipay.checkoutFormInitialize.create(request, (err, result) => {
      if (err) return reject(err);
      resolve(result);
    });
  });

  if (!initResp || !initResp.token) {
    throw new functions.https.HttpsError("internal", "Ödeme başlatılamadı.");
  }

  // 4) token'ı paymentIntent'a yaz
  await db.collection("paymentIntents").doc(piId).update({
    iyziToken: initResp.token,
    iyziRaw: initResp,
  });

  // Client’a HTML (checkoutFormContent) ve paymentIntent id dön
  return {
    piId,
    token: initResp.token,
    checkoutFormContent: initResp.checkoutFormContent, // WebView'de gösterilecek HTML
  };
});

// ------------------ 2) Iyzi callback (server-side) ------------------
exports.iyziCallback = functions.https.onRequest(async (req, res) => {
  try {
    const token = req.body && (req.body.token || req.query.token);
    if (!token) return res.status(400).send("missing token");

    const retrieve = await new Promise((resolve, reject) => {
      iyzipay.checkoutForm.retrieve({ locale: Iyzipay.LOCALE.TR, token }, (err, result) => {
        if (err) return reject(err);
        resolve(result);
      });
    });

    // basketId'yi alıp paymentIntent'ı bul
    const basketId = retrieve && retrieve.basketId;
    if (!basketId) return res.status(400).send("missing basketId");

    const piRef = db.collection("paymentIntents").doc(basketId);
    const piSnap = await piRef.get();
    if (!piSnap.exists) return res.status(404).send("pi not found");

    const pi = piSnap.data();
    const lockRef = db.collection("slotLocks").doc(slotId(pi.teacherId, pi.dateIso, pi.hour));

    if (retrieve.paymentStatus === "SUCCESS") {
      // Booking + lock yaz
      const bId = slotId(pi.teacherId, pi.dateIso, pi.hour);
      const bRef = db.collection("bookings").doc(bId);

      const startAt = new Date(`${pi.dateIso}T${toTwo(pi.hour)}:00:00`);
      const endAt   = new Date(startAt.getTime() + 60 * 60 * 1000);

      await db.runTransaction(async (tr) => {
        // lock hala hold mu?
        const lockSnap = await tr.get(lockRef);
        if (!lockSnap.exists || lockSnap.get("status") !== "hold") {
          throw new Error("slot not held anymore");
        }

        tr.set(bRef, {
          teacherId: pi.teacherId,
          studentId: pi.studentId,
          subjectId: pi.subjectId,
          subjectName: pi.subjectName,
          date: pi.dateIso,
          hour: pi.hour,
          status: "pending", // isterseniz 'accepted' yapabilirsiniz
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          startAt,
          endAt,
          payment: {
            piId: piRef.id,
            amount: pi.price,
            currency: "TRY",
            provider: "iyzico",
            status: "paid",
            raw: retrieve,
          },
        });

        tr.update(lockRef, {
          status: "pending", // hold -> pending
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });

        tr.update(piRef, {
          status: "succeeded",
          iyziRetrieve: retrieve,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });

      // Bildirim tetikleyicileriniz (onBookingCreate vs) zaten çalışacak
      return res.status(200).send("ok");
    } else {
      // failed
      await db.runTransaction(async (tr) => {
        tr.update(piRef, {
          status: "failed",
          iyziRetrieve: retrieve,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        tr.update(lockRef, {
          status: "cancelled",
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });
      return res.status(200).send("failed");
    }
  } catch (e) {
    console.error(e);
    return res.status(500).send("error");
  }
});

// functions/index.js (MEVCUT dosyanıza ek)
const Iyzipay = require("iyzipay");
const db = admin.firestore();

const IYZI_API_KEY = process.env.IYZI_API_KEY || (functions.config().iyzi && functions.config().iyzi.apikey);
const IYZI_SECRET  = process.env.IYZI_SECRET  || (functions.config().iyzi && functions.config().iyzi.secret);
const IYZI_BASE    = process.env.IYZI_BASE    || (functions.config().iyzi && functions.config().iyzi.base) || "https://sandbox-api.iyzipay.com";

const iyzipay = new Iyzipay({
  apiKey: IYZI_API_KEY,
  secretKey: IYZI_SECRET,
  uri: IYZI_BASE,
});

function slotId(teacherId, dateIso, hour) {
  return `${teacherId}_${dateIso}_${hour}`;
}
function toTwo(n){ return (n<10 ? "0":"") + n; }

// ---------- 1) Checkout başlat (Callable) ----------
exports.iyziInitCheckout = functions.https.onCall(async (data, context) => {
  if (!context.auth) throw new functions.https.HttpsError("unauthenticated", "Giriş gerekli.");

  const studentId  = context.auth.uid;
  const { teacherId, subjectId, subjectName, dateIso, hour } = data || {};
  if (!teacherId || !subjectId || !subjectName || !dateIso || typeof hour !== "number") {
    throw new functions.https.HttpsError("invalid-argument", "Eksik parametre.");
  }

  // Fiyat + slot hold + paymentIntent oluştur (transaction)
  const profRef = db.collection("teacherProfiles").doc(teacherId);
  const userRef = db.collection("users").doc(studentId);

  const { price, piId } = await db.runTransaction(async (tr) => {
    const profSnap = await tr.get(profRef);
    if (!profSnap.exists) throw new functions.https.HttpsError("not-found", "Öğretmen bulunamadı.");

    const subjectsMap = profSnap.get("subjectsMap") || {};
    const price = Number(subjectsMap[subjectId]);
    if (!price || isNaN(price)) {
      throw new functions.https.HttpsError("failed-precondition", "Bu ders için fiyat bulunamadı.");
    }

    const lockRef = db.collection("slotLocks").doc(slotId(teacherId, dateIso, hour));
    const lockSnap = await tr.get(lockRef);
    if (lockSnap.exists) {
      const status = lockSnap.get("status");
      if (status && status !== "cancelled") {
        throw new functions.https.HttpsError("already-exists", "Bu saat dolu.");
      }
    }

    // 15 dk hold
    const holdMs = 15 * 60 * 1000;
    tr.set(lockRef, {
      teacherId, studentId, date: dateIso, hour,
      status: "hold",
      holdUntil: admin.firestore.Timestamp.fromMillis(Date.now() + holdMs),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const piRef = db.collection("paymentIntents").doc();
    tr.set(piRef, {
      status: "pending",
      teacherId, studentId, subjectId, subjectName, dateIso, hour,
      price,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { price, piId: piRef.id };
  });

  const userSnap = await userRef.get();
  const fullName = (userSnap.exists && userSnap.get("fullName")) || "Öğrenci";
  const phone    = (userSnap.exists && userSnap.get("phone")) || "0000000000";
  const email    = (userSnap.exists && userSnap.get("email")) || "student@example.com";

  const request = {
    locale: Iyzipay.LOCALE.TR,
    price: price.toFixed(2),
    paidPrice: price.toFixed(2),
    currency: Iyzipay.CURRENCY.TRY,
    basketId: piId,
    paymentGroup: Iyzipay.PAYMENT_GROUP.LISTING,
    // >>> Deploy sonrası URL’niz: https://us-central1-<projectId>.cloudfunctions.net/iyziCallback
    callbackUrl: `https://us-central1-${process.env.GCLOUD_PROJECT}.cloudfunctions.net/iyziCallback`,
    buyer: {
      id: studentId,
      name: fullName.split(" ")[0] || "Ad",
      surname: fullName.split(" ").slice(1).join(" ") || "Soyad",
      gsmNumber: phone,
      email,
      identityNumber: "11111111110",
      registrationAddress: "Adres",
      ip: "85.34.78.112",
      city: "Istanbul",
      country: "Turkey",
    },
    shippingAddress: {
      contactName: fullName, city: "Istanbul", country: "Turkey", address: "Adres",
    },
    billingAddress: {
      contactName: fullName, city: "Istanbul", country: "Turkey", address: "Adres",
    },
    basketItems: [
      {
        id: subjectId,
        name: `${subjectName} (${dateIso} ${toTwo(hour)}:00)`,
        category1: "Özel Ders",
        itemType: Iyzipay.BASKET_ITEM_TYPE.VIRTUAL,
        price: price.toFixed(2),
      },
    ],
  };

  const initResp = await new Promise((resolve, reject) => {
    iyzipay.checkoutFormInitialize.create(request, (err, result) => {
      if (err) return reject(err);
      resolve(result);
    });
  });
  if (!initResp || !initResp.token) {
    throw new functions.https.HttpsError("internal", "Ödeme başlatılamadı.");
  }

  await db.collection("paymentIntents").doc(piId).update({
    iyziToken: initResp.token,
    iyziRaw: initResp,
  });

  return {
    piId,
    token: initResp.token,
    checkoutFormContent: initResp.checkoutFormContent,
  };
});

// ---------- 2) Iyzi callback (HTTP) ----------
exports.iyziCallback = functions.https.onRequest(async (req, res) => {
  try {
    const token = (req.body && (req.body.token || req.body.Token)) || req.query.token;
    if (!token) return res.status(400).send("missing token");

    const retrieve = await new Promise((resolve, reject) => {
      iyzipay.checkoutForm.retrieve({ locale: Iyzipay.LOCALE.TR, token }, (err, result) => {
        if (err) return reject(err);
        resolve(result);
      });
    });

    const basketId = retrieve && retrieve.basketId;
    if (!basketId) return res.status(400).send("missing basketId");

    const piRef = db.collection("paymentIntents").doc(basketId);
    const piSnap = await piRef.get();
    if (!piSnap.exists) return res.status(404).send("pi not found");
    const pi = piSnap.data();

    const lockRef = db.collection("slotLocks").doc(slotId(pi.teacherId, pi.dateIso, pi.hour));

    if (retrieve.paymentStatus === "SUCCESS") {
      const bId = slotId(pi.teacherId, pi.dateIso, pi.hour);
      const bRef = db.collection("bookings").doc(bId);

      const startAt = new Date(`${pi.dateIso}T${toTwo(pi.hour)}:00:00`);
      const endAt   = new Date(startAt.getTime() + 60 * 60 * 1000);

      await db.runTransaction(async (tr) => {
        const lockSnap = await tr.get(lockRef);
        if (!lockSnap.exists || lockSnap.get("status") !== "hold") {
          throw new Error("slot not held anymore");
        }

        tr.set(bRef, {
          teacherId: pi.teacherId,
          studentId: pi.studentId,
          subjectId: pi.subjectId,
          subjectName: pi.subjectName,
          date: pi.dateIso,
          hour: pi.hour,
          status: "pending", // istersen "accepted"
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          startAt,
          endAt,
          payment: {
            piId: piRef.id,
            amount: pi.price,
            currency: "TRY",
            provider: "iyzico",
            status: "paid",
            raw: retrieve,
          },
        });

        tr.update(lockRef, { status: "pending", updatedAt: admin.firestore.FieldValue.serverTimestamp() });
        tr.update(piRef,   { status: "succeeded", iyziRetrieve: retrieve, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
      });

      return res.status(200).send("ok");
    } else {
      await db.runTransaction(async (tr) => {
        tr.update(piRef, { status: "failed", iyziRetrieve: retrieve, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
        tr.update(lockRef, { status: "cancelled", updatedAt: admin.firestore.FieldValue.serverTimestamp() });
      });
      return res.status(200).send("failed");
    }
  } catch (e) {
    console.error(e);
    return res.status(500).send("error");
  }
});

