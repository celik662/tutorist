// functions/index.js
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const Iyzipay = require("iyzipay");

admin.initializeApp();
const db = admin.firestore();

/* -------------------- Iyzi config & helpers -------------------- */
function getIyziCfg() {
  const c = (functions.config && functions.config().iyzico) || {};
  return {
    apiKey: c.api_key || process.env.IYZI_API_KEY,
    secretKey: c.secret_key || process.env.IYZI_SECRET_KEY,
    uri: c.base_url || process.env.IYZI_BASE_URL || "https://sandbox-api.iyzipay.com",
  };
}

function createIyziClientOrThrow() {
  const { apiKey, secretKey, uri } = getIyziCfg();
  if (!apiKey || !secretKey) {
    throw new HttpsError(
      "failed-precondition",
      "Iyzi API anahtarları tanımlı değil (IYZI_API_KEY / IYZI_SECRET_KEY)."
    );
  }
  return new Iyzipay({ apiKey, secretKey, uri });
}

function asPromise(fn, payload) {
  return new Promise((resolve, reject) => {
    fn(payload, (err, res) => {
      if (err) return reject(err);
      if (!res || res.status !== "success") {
        const e = new Error((res && (res.errorMessage || res.consumerErrorMessage)) || "Iyzi error");
        e.details = res;
        return reject(e);
      }
      resolve(res);
    });
  });
}

// Iyzi tutarlar string ve 2 ondalık bekler
function fmtTryAmount(x, fieldName) {
  const n = Number(x);
  if (!isFinite(n)) {
    throw new HttpsError("invalid-argument", `${fieldName || "amount"} sayısal olmalı`);
  }
  return n.toFixed(2);
}

/** callback base öncelik:
 *  1) functions.config().app.callback_base
 *  2) env CALLBACK_BASE
 *  3) prod functions URL (emülatörde uyarı düşer)
 */
function computeCallbackBase() {
  const cfg = functions.config && functions.config().app && functions.config().app.callback_base;
  const env = process.env.CALLBACK_BASE;
  if (cfg) return String(cfg).replace(/\/$/, "");
  if (env) return String(env).replace(/\/$/, "");

  const region = "europe-west1";
  const projectId =
    process.env.GCLOUD_PROJECT ||
    (process.env.FIREBASE_CONFIG && JSON.parse(process.env.FIREBASE_CONFIG).projectId);

  const isEmu = process.env.FUNCTIONS_EMULATOR === "true" || !!process.env.FIREBASE_EMULATOR_HUB;
  if (isEmu) {
    console.warn(
      "[computeCallbackBase] Emülatörde geri çağrı için public HTTPS gerekli. " +
      "ngrok tünel kurup:  firebase functions:config:set app.callback_base=\"https://<ngrok>/<projectId>\""
    );
  }
  return `https://${region}-${projectId}.cloudfunctions.net`;
}

/* -------------------- Basit ping -------------------- */
exports.ping = onCall({ region: "europe-west1" }, (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
  return { ok: true };
});

/* -------------------- (Örnek) Firestore tetikleyiciler -------------------- */
exports.onBookingCreate = onDocumentCreated(
  { region: "europe-west1", document: "bookings/{bookingId}" },
  async () => {}
);

exports.onBookingStatusChange = onDocumentUpdated(
  { region: "europe-west1", document: "bookings/{bookingId}" },
  async () => {}
);

/* -------------------- Sub-merchant oluştur -------------------- */
exports.iyziCreateSubmerchant = onCall({ region: "europe-west1" }, async (req) => {
  try {
    if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
    const uid = req.auth.uid;
    const { fullName, nationalId, iban, email, gsmNumber, address, city, country, zipCode } = req.data || {};

    if (!fullName || !nationalId || !iban || !email || !gsmNumber || !address || !city || !zipCode) {
      throw new HttpsError("invalid-argument", "Eksik alan(lar) var.");
    }

    const parts = String(fullName).trim().split(/\s+/);
    const contactName = parts.shift() || fullName;
    const contactSurname = parts.length ? parts.join(" ") : ".";

    const iyzi = createIyziClientOrThrow();
    const payload = {
      locale: Iyzipay.LOCALE.TR,
      subMerchantExternalId: uid,
      subMerchantType: Iyzipay.SUB_MERCHANT_TYPE.PERSONAL,
      contactName,
      contactSurname,
      legalCompanyTitle: fullName,
      email,
      gsmNumber,
      address,
      iban,
      identityNumber: nationalId,
      currency: Iyzipay.CURRENCY.TRY,
      name: fullName,
    };

    const res = await asPromise(iyzi.subMerchant.create.bind(iyzi.subMerchant), payload);

    const ibanMasked = iban.replace(/^(.{6}).+(.{4})$/, "$1**** **** ****$2");
    await db.collection("teacherPayouts").doc(uid).set(
      {
        subMerchantKey: res.subMerchantKey,
        status: "ACTIVE",
        ibanMasked,
        city,
        zipCode,
        country: country || "Turkey",
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return { subMerchantKey: res.subMerchantKey };
  } catch (e) {
    console.error("iyziCreateSubmerchant:", e?.message, e?.details);
    if (e instanceof HttpsError) throw e;
    throw new HttpsError("internal", "Iyzico isteği başarısız.", {
      message: String(e?.message || e),
      iyzi: e?.details,
    });
  }
});

/* -------------------- Checkout başlat (ödeme) -------------------- */
exports.iyziInitCheckout = onCall({ region: "europe-west1" }, async (req) => {
  try {
    if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
    const uid = req.auth.uid;

    const {
      bookingId, teacherId, subjectId, subjectName,
      price, teacherShare, saveCard, cardUserKey,
      buyer, billingAddress, callbackBase // opsiyonel override
    } = req.data || {};

    if (!bookingId || !teacherId || !subjectId || !subjectName || price == null || teacherShare == null) {
      throw new HttpsError("invalid-argument", "Eksik alan(lar).");
    }
    if (String(bookingId).length > 64) {
      throw new HttpsError("invalid-argument", "bookingId çok uzun (<=64).");
    }

    const priceStr = fmtTryAmount(price, "price");
    const teacherShareStr = fmtTryAmount(teacherShare, "teacherShare");

    const tDoc = await db.collection("teacherPayouts").doc(teacherId).get();
    const subMerchantKey = tDoc.get("subMerchantKey");
    if (!subMerchantKey) throw new HttpsError("failed-precondition", "Öğretmen ödeme için aktif değil.");

    const cbBase = (callbackBase && String(callbackBase).replace(/\/$/, "")) || computeCallbackBase();
    const iyzi = createIyziClientOrThrow();

    const request = {
      locale: Iyzipay.LOCALE.TR,
      price: priceStr,
      paidPrice: priceStr,
      currency: Iyzipay.CURRENCY.TRY,
      basketId: bookingId,
      paymentGroup: Iyzipay.PAYMENT_GROUP.PRODUCT,
      callbackUrl: `${cbBase}/europe-west1/iyziCallback`,
      enabledInstallments: [1],
      ...(saveCard ? { registerCard: 1 } : {}),
      ...(cardUserKey ? { cardUserKey } : {}),
      buyer: {
        id: uid,
        name: buyer?.name || "Ad",
        surname: buyer?.surname || "Soyad",
        gsmNumber: buyer?.gsmNumber || "+905000000000",
        email: buyer?.email || "test@example.com",
        identityNumber: buyer?.nationalId || "11111111111",
        registrationAddress: billingAddress?.address || "Adres",
        ip: buyer?.ip || "85.34.78.112",
        city: billingAddress?.city || "Istanbul",
        country: billingAddress?.country || "Turkey",
        zipCode: billingAddress?.zipCode || "00000",
      },
      billingAddress: {
        contactName: `${buyer?.name || "Ad"} ${buyer?.surname || "Soyad"}`,
        city: billingAddress?.city || "Istanbul",
        country: billingAddress?.country || "Turkey",
        address: billingAddress?.address || "Adres",
        zipCode: billingAddress?.zipCode || "00000",
      },
      basketItems: [
        {
          id: subjectId,
          name: subjectName,
          category1: "Ders",
          itemType: Iyzipay.BASKET_ITEM_TYPE.VIRTUAL,
          price: priceStr,
          subMerchantKey,
          subMerchantPrice: teacherShareStr,
        },
      ],
    };

    const res = await asPromise(
      iyzi.checkoutFormInitialize.create.bind(iyzi.checkoutFormInitialize),
      request
    );

    await db.collection("bookings").doc(bookingId).set(
      {
        status: "PENDING",
        studentId: uid,
        teacherId, subjectId, subjectName,
        price: Number(price),
        teacherShare: Number(teacherShare),
        payment: {
          token: res.token,
          iyziStatus: "INIT",
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return { token: res.token, checkoutFormContent: res.checkoutFormContent };
  } catch (e) {
    console.error("iyziInitCheckout error:", e && e.message, e && e.details);
    if (e instanceof HttpsError) throw e;
    throw new HttpsError("internal", "Checkout init failed.", {
      message: String((e && e.message) || e),
      iyzi: e && e.details ? e.details : undefined,
    });
  }
});

/* -------------------- Kart Kaydet (Hosted Form) -------------------- */
exports.iyziInitCardSave = onCall({ region: "europe-west1" }, async (req) => {
  try {
    if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
    const uid = req.auth.uid;

    const cbBase = (req.data && req.data.callbackBase) || computeCallbackBase();

    const uDoc = await db.collection("users").doc(uid).get();
    const email = (uDoc.exists && uDoc.get("email")) || "test@example.com";
    const existingKey = uDoc.exists && uDoc.get("iyzico.cardUserKey");

    const iyzi = createIyziClientOrThrow();
    const opsId = `cardsave_${uid}_${Date.now()}`;
    const priceStr = fmtTryAmount(1, "price"); // sandbox sembolik 1 TL

    const request = {
      locale: Iyzipay.LOCALE.TR,
      price: priceStr,
      paidPrice: priceStr,
      currency: Iyzipay.CURRENCY.TRY,
      basketId: opsId,
      paymentGroup: Iyzipay.PAYMENT_GROUP.PRODUCT,
      callbackUrl: `${String(cbBase).replace(/\/$/, "")}/europe-west1/iyziCallback`,
      enabledInstallments: [1],
      registerCard: 1,
      ...(existingKey ? { cardUserKey: existingKey } : {}),
      buyer: {
        id: uid,
        name: "Ad",
        surname: "Soyad",
        gsmNumber: "+905000000000",
        email,
        identityNumber: "11111111111",
        registrationAddress: "Adres",
        ip: "85.34.78.112",
        city: "Istanbul",
        country: "Turkey",
        zipCode: "00000",
      },
      billingAddress: {
        contactName: "Ad Soyad",
        city: "Istanbul",
        country: "Turkey",
        address: "Adres",
        zipCode: "00000",
      },
      basketItems: [
        {
          id: "card_save",
          name: "Kart Kaydetme",
          category1: "Cüzdan",
          itemType: Iyzipay.BASKET_ITEM_TYPE.VIRTUAL,
          price: priceStr,
        },
      ],
    };

    const res = await asPromise(
      iyzi.checkoutFormInitialize.create.bind(iyzi.checkoutFormInitialize),
      request
    );

    await db.collection("cardOps").doc(opsId).set({
      status: "INIT",
      token: res.token,
      uid,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { opsId, token: res.token, checkoutFormContent: res.checkoutFormContent };
  } catch (e) {
    console.error("iyziInitCardSave error:", e && e.message, e && e.details);
    if (e instanceof HttpsError) throw e;
    throw new HttpsError("internal", "Card save init failed.", {
      message: String((e && e.message) || e),
      iyzi: e && e.details ? e.details : undefined,
    });
  }
});

/* -------------------- Tek callback (ödeme + kart kaydet) -------------------- */
exports.iyziCallback = onRequest({ region: "europe-west1" }, async (req, res) => {
  try {
    const token = req.body?.token || req.query?.token;
    if (!token) return res.status(400).send("token required");

    const iyzi = createIyziClientOrThrow();
    const result = await asPromise(
      iyzi.checkoutFormRetrieve.retrieve.bind(iyzi.checkoutFormRetrieve),
      { token }
    );

    const basketId = result?.basketId || result?.conversationId || "unknown";
    const paid = result?.paymentStatus === "SUCCESS";

    // ---- Kart kaydetme akışı ----
    if (String(basketId).startsWith("cardsave_")) {
      const opsSnap = await db.collection("cardOps").doc(basketId).get();
      const uidFromOps = opsSnap.exists ? opsSnap.get("uid") : null;
      const uid = result?.buyer?.id || uidFromOps;

      if (uid && paid && result?.cardUserKey) {
        const update = { "iyzico.cardUserKey": result.cardUserKey };
        if (result.cardToken) {
          update[`iyzico.cards.${result.cardToken}`] = {
            lastFour: result.lastFourDigits || null,
            bin: result.binNumber || null,
            scheme: result.cardAssociation || null,
            bank: result.cardBankName || null,
            savedAt: admin.firestore.FieldValue.serverTimestamp(),
          };
        }
        await db.collection("users").doc(uid).set(update, { merge: true });
      }

      await db.collection("cardOps").doc(basketId).set(
        {
          status: paid ? "SUCCEEDED" : "FAILED",
          iyziStatus: result?.status || null,
          error: paid ? null : (result?.errorMessage || null),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      return res.status(200).send("OK");
    }

    // ---- Rezervasyon ödemesi ----
    const status = paid ? "PAID" : "FAILED";
    await db.collection("bookings").doc(basketId).set(
      {
        status,
        payment: {
          status,
          token,
          iyziStatus: result?.status,
          paymentId: result?.paymentId || null,
          errorMessage: result?.errorMessage || null,
          cardUserKey: result?.cardUserKey || null,
          cardToken: result?.cardToken || null,
          lastFour: result?.lastFourDigits || null,
          bin: result?.binNumber || null,
          scheme: result?.cardAssociation || null,
          bank: result?.cardBankName || null,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
      },
      { merge: true }
    );

    return res.status(200).send("OK");
  } catch (e) {
    console.error("iyziCallback error", e);
    return res.status(500).send("ERR");
  }
});
