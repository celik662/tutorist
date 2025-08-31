package com.example.tutorist.payment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class PaymentActivity extends AppCompatActivity {
    private static final String EX_PI = "pi";
    private static final String EX_HTML = "html";

    public static void start(Context c, String piId, String html) {
        Intent i = new Intent(c, PaymentActivity.class);
        i.putExtra(EX_PI, piId);
        i.putExtra(EX_HTML, html);
        c.startActivity(i);
    }

    private String piId;
    private ListenerRegistration reg;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView wv = new WebView(this);
        setContentView(wv);

        piId = getIntent().getStringExtra(EX_PI);
        String html = getIntent().getStringExtra(EX_HTML);
        if (piId == null || html == null) {
            Toast.makeText(this, "Ödeme bilgisi bulunamadı.", Toast.LENGTH_LONG).show();
            finish(); return;
        }

        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        wv.loadDataWithBaseURL("https://sandbox-iyzipay.com", html, "text/html", "utf-8", null);

        reg = FirebaseFirestore.getInstance()
                .collection("paymentIntents").document(piId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    String status = snap.getString("status");
                    if ("succeeded".equals(status)) {
                        Toast.makeText(this, "Ödeme alındı, rezervasyon oluşturuldu.", Toast.LENGTH_LONG).show();
                        finish();
                    } else if ("failed".equals(status)) {
                        Toast.makeText(this, "Ödeme başarısız.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
    }

    @Override protected void onDestroy() {
        if (reg != null) reg.remove();
        super.onDestroy();
    }
}
