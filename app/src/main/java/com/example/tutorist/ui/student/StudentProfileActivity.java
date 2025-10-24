package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.BuildConfig;
import com.example.tutorist.R;
import com.example.tutorist.payment.PaymentActivity;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;

public class StudentProfileActivity extends AppCompatActivity {

    private static final String FUNCTIONS_REGION = "europe-west1";

    // DEBUG (emülatör) ve PROD callback base — proje id'nizi girin


    private EditText etName, etPhone;
    private TextView tvMsg;
    private LinearLayout llCards;
    private Button btnAddCard, btnSave, btnLogout;

    private final UserRepo userRepo = new UserRepo();
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseFunctions functions;

    private String uid;
    private ListenerRegistration userReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_student_profile);

        // UI
        etName   = findViewById(R.id.etName);
        etPhone  = findViewById(R.id.etPhone);
        tvMsg    = findViewById(R.id.tvMsg);
        llCards  = findViewById(R.id.llCards);
        btnAddCard = findViewById(R.id.btnAddCard);
        btnSave  = findViewById(R.id.btnSave);
        btnLogout= findViewById(R.id.btnLogout);

        // Firebase
        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();
        functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION);


        uid = auth.getUid();
        if (uid == null) {
            goLogin();
            return;
        }

        loadProfile();
        listenUserCards(uid);

        btnAddCard.setOnClickListener(v -> startAddCardFlow());

        btnSave.setOnClickListener(v -> {
            String name  = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> tvMsg.setText("Kaydedildi."))
                    .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
        });

        btnLogout.setOnClickListener(v -> {
            if (userReg != null) { userReg.remove(); userReg = null; }

            FirebaseAuth.getInstance().signOut();
            goLogin();
        });
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        // Eski activity zincirini kesin kapat
        finishAffinity();
    }

    private void loadProfile() {
        userRepo.loadUser(uid).addOnSuccessListener(data -> {
            if (data != null) {
                Object n = data.get("fullName");
                Object p = data.get("phone");
                if (n != null) etName.setText(String.valueOf(n));
                if (p != null) etPhone.setText(String.valueOf(p));
            }
        });
    }

    private void listenUserCards(String uid) {
        userReg = db.collection("users").document(uid)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> iyz = (Map<String, Object>) snap.get("iyzico");
                    renderCards(iyz);
                });
    }

    private void renderCards(Map<String, Object> iyz) {
        llCards.removeAllViews();

        if (iyz == null) {
            TextView tv = new TextView(this);
            tv.setText("Kayıtlı kart yok.");
            llCards.addView(tv);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> cards = (Map<String, Object>) iyz.get("cards");

        if (cards == null || cards.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Kayıtlı kart yok.");
            llCards.addView(tv);
            return;
        }

        for (Map.Entry<String, Object> entry : cards.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) entry.getValue();
            String last4  = c.get("lastFour") != null ? String.valueOf(c.get("lastFour")) : "••••";
            String bank   = c.get("bank") != null ? String.valueOf(c.get("bank")) : "";
            String scheme = c.get("scheme") != null ? String.valueOf(c.get("scheme")) : "";

            TextView tv = new TextView(this);
            tv.setText((bank + " " + scheme + " •••• " + last4).trim());
            tv.setPadding(0, 12, 0, 12);
            llCards.addView(tv);
        }
    }

    private void startAddCardFlow() {
        Map<String, Object> payload = new HashMap<>();
        functions.getHttpsCallable("iyziInitCardSave")
                .call(payload)
                .addOnSuccessListener(r -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> res = (Map<String, Object>) r.getData();
                    String opsId = res != null ? (String) res.get("opsId") : null;
                    String html  = res != null ? (String) res.get("checkoutFormContent") : null;

                    if (opsId == null || html == null) {
                        Toast.makeText(this, "Form açılamadı.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    PaymentActivity.startCardSave(this, opsId, html);
                })
                .addOnFailureListener(err ->
                        Toast.makeText(this, "Hata: " + err.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        if (userReg != null) userReg.remove();
        super.onDestroy();
    }
}
