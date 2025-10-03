package com.example.tutorist.ui.teacher;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.*;
import com.example.tutorist.BuildConfig;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.*;

public class TeacherPayoutActivity extends AppCompatActivity {

    private static final String FUNCTIONS_REGION = "europe-west1";
    // DEBUG’ta host’u buradan değiştir:
    // - Android Emülatör: "10.0.2.2"
    // - Fiziksel cihaz + adb reverse: "127.0.0.1"
    // - Fiziksel cihaz + Wi-Fi/LAN: "192.168.x.x"

    private TextInputLayout tilIban, tilHolder, tilTckn;
    private TextInputEditText etIban, etHolder, etTckn;
    private EditText etEmail, etPhone, etAddress, etCity, etZip;
    private TextView tvStatus, tvMsg;
    private ProgressBar progress;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseFunctions functions;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_payout);

        // View’lar
        tilIban   = findViewById(R.id.tilIban);
        tilHolder = findViewById(R.id.tilHolder);
        tilTckn   = findViewById(R.id.tilTckn);

        etIban   = findViewById(R.id.etIban);
        etHolder = findViewById(R.id.etHolder);
        etTckn   = findViewById(R.id.etTckn);

        etEmail  = findViewById(R.id.etEmail);
        etPhone  = findViewById(R.id.etPhone);
        etAddress= findViewById(R.id.etAddress);
        etCity   = findViewById(R.id.etCity);
        etZip    = findViewById(R.id.etZip);
        tvStatus = findViewById(R.id.tvStatus);
        tvMsg    = findViewById(R.id.tvMsg);
        progress = findViewById(R.id.progress);

        // Firebase
        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();
        setupFunctions(); // <-- Mutlaka ilk çağrılmadan önce

        // Alan temizleyiciler
        attachClearer(tilIban);
        attachClearer(tilHolder);
        attachClearer(tilTckn);

        // IBAN formatlayıcı
        etIban.addTextChangedListener(new TextWatcher() {
            boolean self;
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable e) {
                if (self) return;
                self = true;
                String raw = cleanIban(e.toString());
                if (!raw.startsWith("TR")) raw = "TR" + raw;
                StringBuilder sb = new StringBuilder();
                for (int i=0;i<raw.length();i++) {
                    if (i>0 && i%4==0) sb.append(' ');
                    sb.append(raw.charAt(i));
                }
                etIban.setText(sb.toString());
                etIban.setSelection(etIban.getText().length());
                self = false;
            }
        });

        clearOnChange(etHolder);
        clearOnChange(etTckn);
        clearOnChange(etIban);
        clearOnChange(etEmail);
        clearOnChange(etPhone);
        clearOnChange(etAddress);
        clearOnChange(etCity);
        clearOnChange(etZip);

        findViewById(R.id.btnSave).setOnClickListener(v -> submit());

        // Ping (önce anon giriş)
        if (auth.getCurrentUser() != null) {
            testPing();
        } else {
            auth.signInAnonymously()
                    .addOnSuccessListener(r -> testPing())
                    .addOnFailureListener(e -> Log.e("TEST","anon sign-in failed", e));
        }

        loadPrefill();
        loadPayoutStatus();
    }
    private void setupFunctions() {
        functions = FirebaseFunctions.getInstance("europe-west1");
        if (BuildConfig.DEBUG) {                // sadece debug
            functions.useEmulator("10.0.2.2", 5001);
            // Fiziksel cihaz + adb reverse kullanırsan: "127.0.0.1"
        }
    }




    private void testPing() {
        functions.getHttpsCallable("ping")
                .call()
                .addOnSuccessListener(r -> Log.d("TEST","ping OK: " + r.getData()))
                .addOnFailureListener(e -> Log.e("TEST","ping FAIL", e));
    }

    private void attachClearer(TextInputLayout til) {
        if (til == null || til.getEditText() == null) return;
        til.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                til.setError(null);
                til.setErrorEnabled(false);
                if (tvMsg != null) tvMsg.setText("");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadPrefill() {
        String uid = auth.getUid(); if (uid == null) return;
        db.collection("users").document(uid).get().addOnSuccessListener(d -> {
            if (d != null && d.exists()) {
                if (etHolder.getText().length()==0) etHolder.setText(d.getString("fullName"));
                if (etEmail.getText().length()==0)  etEmail.setText(d.getString("email"));
                if (etPhone.getText().length()==0)  etPhone.setText(d.getString("phone"));
            }
        });
    }

    private void loadPayoutStatus() {
        String uid = auth.getUid(); if (uid == null) return;
        db.collection("teacherPayouts").document(uid).addSnapshotListener((snap, e) -> {
            if (e != null || snap == null) return;
            String status = snap.getString("status");
            String ibanMasked = snap.getString("ibanMasked");
            String reason = snap.getString("rejectionReason");
            String s = status != null ? status : "—";
            if (ibanMasked != null) s += " • " + ibanMasked;
            tvStatus.setText(s);
            tvMsg.setText(reason != null ? ("Not: " + reason) : "");
        });
    }

    @SuppressLint("SetTextI18n")
    private void submit() {
        String holder = etHolder.getText().toString().trim();
        String tckn   = etTckn.getText().toString().replaceAll("\\D+","");
        String iban   = cleanIban(etIban.getText().toString()).toUpperCase(Locale.ROOT);
        String email  = etEmail.getText().toString().trim();
        String phone  = etPhone.getText().toString().trim();
        String addr   = etAddress.getText().toString().trim();
        String city   = etCity.getText().toString().trim();
        String zip    = etZip.getText().toString().trim();

        View first = null;
        if (holder.length() < 3) { etHolder.setError("Hesap sahibi girin"); first = first==null?etHolder:first; }
        if (!isValidTCKN(tckn))  { etTckn.setError("Geçersiz T.C. Kimlik No"); first = first==null?etTckn:first; }
        if (!isValidIbanTR(iban)){ etIban.setError("Geçersiz IBAN (TR ile başlayıp 26 hane)"); first = first==null?etIban:first; }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Geçerli e-posta girin"); first = first==null?etEmail:first;
        }
        if (phone.isEmpty())  { etPhone.setError("Telefon girin"); first = first==null?etPhone:first; }
        if (addr.isEmpty())   { etAddress.setError("Adres girin"); first = first==null?etAddress:first; }
        if (city.isEmpty())   { etCity.setError("Şehir girin"); first = first==null?etCity:first; }
        if (zip.isEmpty())    { etZip.setError("Posta kodu girin"); first = first==null?etZip:first; }

        if (first != null) { first.requestFocus(); tvMsg.setText("Lütfen kırmızı alanları düzeltin."); return; }

        progress.setVisibility(View.VISIBLE);
        tvMsg.setText("");

        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", holder);
        payload.put("nationalId", tckn);
        payload.put("iban", iban);
        payload.put("email", email);
        payload.put("gsmNumber", phone);
        payload.put("address", addr);
        payload.put("city", city);
        payload.put("country", "Turkey");
        payload.put("zipCode", zip);

        functions.getHttpsCallable("iyziCreateSubmerchant")
                .call(payload)
                .addOnSuccessListener(r -> {
                    progress.setVisibility(View.GONE);
                    tvMsg.setText("Bilgileriniz gönderildi. Onay sonrası ödeme alabilirsiniz.");
                })
                .addOnFailureListener(e -> {
                    progress.setVisibility(View.GONE);
                    if (e instanceof com.google.firebase.functions.FirebaseFunctionsException) {
                        com.google.firebase.functions.FirebaseFunctionsException fe =
                                (com.google.firebase.functions.FirebaseFunctionsException) e;
                        tvMsg.setText("Kaydedilemedi: " + fe.getCode() +
                                (fe.getDetails()!=null ? (" • " + fe.getDetails()) : ""));
                    } else {
                        tvMsg.setText("Kaydedilemedi: " + e.getMessage());
                    }
                });
    }

    // --- Basit doğrulamalar ---
    private boolean isValidTCKN(String t){
        if (t == null || !t.matches("^\\d{11}$") || t.charAt(0)=='0') return false;
        int[] d = new int[11]; for (int i=0;i<11;i++) d[i]=t.charAt(i)-'0';
        int sumOdd=d[0]+d[2]+d[4]+d[6]+d[8], sumEven=d[1]+d[3]+d[5]+d[7];
        int d10 = ((sumOdd*7) - sumEven) % 10; if (d10<0) d10+=10;
        if (d10 != d[9]) return false;
        int d11 = (d[0]+d[1]+d[2]+d[3]+d[4]+d[5]+d[6]+d[7]+d[8]+d[9]) % 10;
        return d11 == d[10];
    }

    private String cleanIban(String s) {
        if (s == null) return "";
        return s.replace('\u00A0',' ')
                .replaceAll("[\\s\\p{Zs}]+","")
                .toUpperCase(Locale.ROOT)
                .replaceFirst("^TR", "TR"); // başta tek TR kalsın
    }

    private boolean isValidIbanTR(String ibanInput){
        String s = cleanIban(ibanInput);
        if (!s.matches("^TR\\d{24}$")) return false; // TR + 24 rakam (toplam 26)
        String moved = s.substring(4) + s.substring(0,4);
        StringBuilder num = new StringBuilder();
        for (char ch : moved.toCharArray()) {
            if (Character.isDigit(ch)) num.append(ch);
            else num.append((ch - 'A') + 10);
        }
        int rem = 0;
        for (int i = 0; i < num.length(); i++) {
            rem = (rem * 10 + (num.charAt(i) - '0')) % 97;
        }
        return rem == 1;
    }

    private void clearOnChange(EditText et) {
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                et.setError(null);
                if (tvMsg != null) tvMsg.setText("");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
}
