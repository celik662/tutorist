package com.example.tutorist.payment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class PaymentActivity extends AppCompatActivity {

    // ---- Mod & Extras ----
    private static final String EX_MODE = "ex_mode";       // "payment" | "card" | (legacy)
    private static final String MODE_PAYMENT = "payment";
    private static final String MODE_CARD    = "card";

    // id: MODE_PAYMENT => bookingId, MODE_CARD => opsId
    private static final String EX_ID   = "ex_id";
    private static final String EX_HTML = "ex_html";

    // Legacy (eski kullanım) — mümkünse bunu bırak
    private static final String EX_PI_LEGACY = "ex_pi";

    // ---- Public starters ----
    /** Ödeme akışı (rezervasyon) */
    public static void startPayment(Context c, String bookingId, String html) {
        Intent i = new Intent(c, PaymentActivity.class);
        i.putExtra(EX_MODE, MODE_PAYMENT);
        i.putExtra(EX_ID, bookingId);
        i.putExtra(EX_HTML, html);
        c.startActivity(i);
    }

    /** Kart kaydetme akışı */
    public static void startCardSave(Context c, String opsId, String html) {
        Intent i = new Intent(c, PaymentActivity.class);
        i.putExtra(EX_MODE, MODE_CARD);
        i.putExtra(EX_ID, opsId);
        i.putExtra(EX_HTML, html);
        c.startActivity(i);
    }

    /** Geriye dönük: eski çağrı (booking için) */
    public static void start(Context c, String bookingId, String html) {
        startPayment(c, bookingId, html);
    }

    /** Geriye dönük: eski PI yapısı */
    public static void startWithPi(Context c, String piId, String html) {
        Intent i = new Intent(c, PaymentActivity.class);
        i.putExtra(EX_PI_LEGACY, piId);
        i.putExtra(EX_HTML, html);
        c.startActivity(i);
    }

    // ---- Instance ----
    private ListenerRegistration reg;
    private WebView web;
    private ProgressBar bar;

    private String mode;     // payment | card | null(legacy)
    private String id;       // bookingId | opsId | null(legacy)
    private String piLegacy; // legacy PI id

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Basit UI: WebView + ProgressBar
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        bar.setVisibility(View.GONE);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bar, lp);
        setContentView(root);

        // Extras
        mode     = getIntent().getStringExtra(EX_MODE);
        id       = getIntent().getStringExtra(EX_ID);
        String html = getIntent().getStringExtra(EX_HTML);
        piLegacy = getIntent().getStringExtra(EX_PI_LEGACY);

        if ((mode == null && piLegacy == null) || html == null) {
            Toast.makeText(this, "Ödeme bilgisi eksik.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // WebView ayarları
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        web.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Prod ortamda geçerli sertifika olacağı için bu düşmemeli.
                Toast.makeText(PaymentActivity.this, "SSL hatası: " + error, Toast.LENGTH_LONG).show();
                handler.cancel();
                finish();
            }

            @Override // API 23+
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Toast.makeText(PaymentActivity.this, "Yüklenemedi: " + error, Toast.LENGTH_LONG).show();
            }

            @SuppressWarnings("deprecation")
            @Override // Eski imza (minSdk 24 olsa da bazı WebView'lar çağırabilir)
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(PaymentActivity.this, "Yüklenemedi: " + description, Toast.LENGTH_LONG).show();
            }
        });

        // Hosted form HTML
        web.loadDataWithBaseURL("https://sandbox-iyzipay.com", html, "text/html", "UTF-8", null);

        // Firestore dinleme
        if (MODE_PAYMENT.equals(mode)) {
            if (id == null) {
                Toast.makeText(this, "bookingId eksik.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            listenBooking(id);
        } else if (MODE_CARD.equals(mode)) {
            if (id == null) {
                Toast.makeText(this, "işlem kimliği eksik.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            listenCardOps(id);
        } else {
            // Legacy PI takibi
            if (piLegacy == null) {
                Toast.makeText(this, "Geçersiz çağrı.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            listenLegacyPaymentIntent(piLegacy);
            Toast.makeText(this, "Uyarı: Legacy ödeme izleme (PI).", Toast.LENGTH_SHORT).show();
        }
    }

    private void listenBooking(String bookingId) {
        reg = FirebaseFirestore.getInstance()
                .collection("bookings").document(bookingId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null || !snap.exists()) return;
                    String status = snap.getString("status");
                    if ("PAID".equalsIgnoreCase(status)) {
                        Toast.makeText(this, "Ödeme alındı.", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    } else if ("FAILED".equalsIgnoreCase(status)) {
                        Toast.makeText(this, "Ödeme başarısız.", Toast.LENGTH_LONG).show();
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });
    }

    private void listenCardOps(String opsId) {
        reg = FirebaseFirestore.getInstance()
                .collection("cardOps").document(opsId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null || !snap.exists()) return;
                    String status = snap.getString("status");
                    if ("SUCCEEDED".equalsIgnoreCase(status)) {
                        Toast.makeText(this, "Kart eklendi.", Toast.LENGTH_LONG).show();
                        setResult(RESULT_OK);
                        finish();
                    } else if ("FAILED".equalsIgnoreCase(status)) {
                        String msg = snap.getString("error");
                        Toast.makeText(this, "Kart eklenemedi: " + (msg != null ? msg : ""), Toast.LENGTH_LONG).show();
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });
    }

    // Geçici – eski Stripe benzeri yapı
    private void listenLegacyPaymentIntent(String piId) {
        reg = FirebaseFirestore.getInstance()
                .collection("paymentIntents").document(piId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    String status = snap.getString("status");
                    if ("succeeded".equalsIgnoreCase(status)) {
                        Toast.makeText(this, "Ödeme alındı.", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    } else if ("failed".equalsIgnoreCase(status)) {
                        Toast.makeText(this, "Ödeme başarısız.", Toast.LENGTH_LONG).show();
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (reg != null) reg.remove();
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
