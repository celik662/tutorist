package com.example.tutorist.payment;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.ProgressBar;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;
import com.google.firebase.firestore.*;

public class CheckoutActivity extends AppCompatActivity {
    private WebView web; private ProgressBar bar; private ListenerRegistration reg;
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_checkout);
        web = findViewById(R.id.web); bar = findViewById(R.id.progress);

        String html = getIntent().getStringExtra("html");
        String bookingId = getIntent().getStringExtra("bookingId");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView v, String u, Bitmap f){ bar.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView v, String u){ bar.setVisibility(View.GONE); }
        });
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        reg = FirebaseFirestore.getInstance().collection("bookings").document(bookingId)
                .addSnapshotListener((snap, err) -> {
                    if (err!=null || snap==null || !snap.exists()) return;
                    String status = snap.getString("status");
                    if ("PAID".equals(status)) { setResult(RESULT_OK); finish(); }
                    if ("FAILED".equals(status)) { setResult(RESULT_CANCELED); finish(); }
                });
    }
    @Override protected void onDestroy() { if (reg!=null) reg.remove(); super.onDestroy(); }
}
