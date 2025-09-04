// functions/index.js

const admin = require("firebase-admin");
admin.initializeApp();

const functions = require("firebase-functions"); // sadece config() için
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const Iyzipay = require("iyzipay");

const db = admin.firestore();

/** ---------- Config / ENV okuma ---------- */
const cfg = (functions.config && functions.config().iyzico) || {};
function getIyziConfig() {
  const apiKey = cfg.api_key || process.env.IYZI_API_KEY;
  const secretKey = cfg.secret_key || process.env.IYZI_SECRET_KEY;
  const uri = cfg.base_url || process.env.IYZI_BASE_URL || "https://sandbox-api.iyzipay.com";
  return { apiKey, secretKey, uri };
}

function createIyziClientOrThrow() {
  const { apiKey, secretKey, uri } = getIyziConfig();
  if (!apiKey || !secretKey) {
    throw new HttpsError(
      "failed-precondition",
      "Iyzi API anahtarları tanımlı değil (IYZI_API_KEY / IYZI_SECRET_KEY)."
    );
  }
  return new Iyzipay({ apiKey, secretKey, uri });
}

/** ---------- Iyzi callback→promise yardımcı ---------- */
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

/** ---------- Basit ping (callable) ---------- */
exports.ping = onCall({ region: "europe-west1" }, (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
  return { ok: true };
});

/** ---------- Firestore tetikleyicileri (örnek) ---------- */
exports.onBookingCreate = onDocumentCreated(
  { region: "europe-west1", document: "bookings/{bookingId}" },
  async (event) => {
    // ...
    return;
  }
);

exports.onBookingStatusChange = onDocumentUpdated(
  { region: "europe-west1", document: "bookings/{bookingId}" },
  async (event) => {
    const before = event.data.before.data();
    const after  = event.data.after.data();
    if (!before || !after || before.status === after.status) return;
    // ...
    return;
  }
);

/** ---------- Callable: sub-merchant ---------- */
exports.iyziCreateSubmerchant = onCall({ region: "europe-west1" }, async (req) => {
  try {
    if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
    const uid = req.auth.uid;
    const {
      fullName, nationalId, iban, email, gsmNumber,
      address, city, country, zipCode,
    } = req.data || {};

    if (!fullName || !nationalId || !iban || !email || !gsmNumber || !address || !city || !zipCode) {
      throw new HttpsError("invalid-argument", "Eksik alan(lar) var.");
    }

    // İsim/soyisim ayrıştır (Iyzi bazı durumlarda surname isteyebilir)
    const parts = String(fullName).trim().split(/\s+/);
    const contactName = parts.shift() || fullName;
    const contactSurname = parts.length ? parts.join(" ") : contactName;

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

    console.log("iyziCreateSubmerchant payload for", uid);
    const res = await asPromise(iyzi.subMerchant.create.bind(iyzi.subMerchant), payload);

    const ibanMasked = iban.replace(/^(.{6}).+(.{4})$/, "$1**** **** ****$2");
    await db.collection("teacherPayouts").doc(uid).set({
      subMerchantKey: res.subMerchantKey,
      status: "ACTIVE",
      ibanMasked,
      city,
      zipCode,
      country: country || "Turkey",
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });

    return { subMerchantKey: res.subMerchantKey };
  } catch (e) {
    console.error("iyziCreateSubmerchant error:", e && e.message, e && e.details);
    if (e instanceof HttpsError) throw e;
    throw new HttpsError("internal", "Iyzico isteği başarısız.", {
      message: String(e && e.message || e),
      iyzi: e && e.details ? e.details : undefined,
    });
  }
});

/** ---------- Checkout init (iskelet) ---------- */
exports.iyziInitCheckout = onCall({ region: "europe-west1" }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Giriş gerekli");
  // ... burada sipariş token vs. oluşturma mantığını kuracağız ...
  return {};
});

/** ---------- HTTP callback ---------- */
exports.iyziCallback = onRequest({ region: "europe-west1" }, async (req, res) => {
  try {
    const token = (req.body && (req.body.token || req.body.paymentToken || req.body["token"])) || req.query.token;
    if (!token) return res.status(400).send("token required");
    // ... retrieve + Firestore ...
    return res.status(200).send("OK");
  } catch (e) {
    console.error(e);
    return res.status(500).send("ERR");
  }
});

