// app/src/main/java/com/example/tutorist/ui/auth/RegisterActivity.java
package com.example.tutorist.ui.auth;

import android.content.Intent;
import android.net.Uri; // FIX: KVKK linki için
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.example.tutorist.BuildConfig; // FIX: Doğru BuildConfig
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private EditText etEmail, etPass, etPass2;
    private TextView tvMsg;
    private RadioGroup rgRole;
    private MaterialCheckBox cbKvkk;           // FIX: sınıf alanı
    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private static final String KVKK_VERSION = "v1";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_register);

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        etEmail = findViewById(R.id.etEmail);
        etPass  = findViewById(R.id.etPass);
        etPass2 = findViewById(R.id.etPass2);
        rgRole  = findViewById(R.id.rgRole);
        tvMsg   = findViewById(R.id.tvMsg);
        cbKvkk  = findViewById(R.id.cbKvkk);   // FIX: önce al, sonra kullan

        Button btnRegister = findViewById(R.id.btnRegister);
        btnRegister.setOnClickListener(v -> doRegister(btnRegister));

        // “Giriş yap” linki: KVKK kontrolü yok; sadece login’e götürür
        TextView tvGoLogin = findViewById(R.id.tvGoLogin);
        tvGoLogin.setOnClickListener(v -> {
            Intent i = new Intent(RegisterActivity.this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });

        // KVKK linkini dış tarayıcıda aç
        TextView tvKvkkLink = findViewById(R.id.tvKvkkLink);
        tvKvkkLink.setOnClickListener(v -> {
            String url = getString(R.string.kvkk_url);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) { }
        });

        // Başlangıçta KVKK işaretli değilse butonu kilitle
        btnRegister.setEnabled(cbKvkk.isChecked());
        cbKvkk.setOnCheckedChangeListener((buttonView, checked) -> btnRegister.setEnabled(checked));
    }

    private void doRegister(Button btn) {
        String email = etEmail.getText().toString().trim();
        String p1    = etPass.getText().toString();
        String p2    = etPass2.getText().toString();

        int checkedId = rgRole.getCheckedRadioButtonId();
        String role = (checkedId == R.id.rbTeacher) ? "teacher"
                : (checkedId == R.id.rbStudent) ? "student" : null;

        if (!cbKvkk.isChecked()) { // FIX: KVKK zorunlu kontrol burada
            tvMsg.setTextColor(getColor(R.color.tutorist_error));
            tvMsg.setText("Devam etmek için KVKK metnini onaylamalısın.");
            return;
        }
        if (email.isEmpty() || p1.isEmpty() || p2.isEmpty()) { tvMsg.setText("Tüm alanları doldurun."); return; }
        if (!p1.equals(p2)) { tvMsg.setText("Şifreler eşleşmiyor."); return; }
        if (role == null) { tvMsg.setText("Lütfen rol seçin."); return; }

        btn.setEnabled(false);
        tvMsg.setText("Kaydediliyor...");

        auth.createUserWithEmailAndPassword(email, p1)
                .addOnSuccessListener(res -> {
                    FirebaseUser user = res.getUser();
                    if (user == null) { tvMsg.setText("Kullanıcı oluşturulamadı."); btn.setEnabled(true); return; }

                    // 1) Rol + email’i kaydet
                    Map<String, Object> data = new HashMap<>();
                    data.put("role", role);
                    data.put("email", email);
                    data.put("createdAt", FieldValue.serverTimestamp());

                    db.collection("users").document(user.getUid())
                            .set(data, SetOptions.merge())
                            .addOnSuccessListener(w -> {
                                // 2) KVKK onayını kaydet (FIX: ekledik)
                                recordKvkkConsent(user.getUid());

                                // 3) Doğrulama e-postası gönder
                                user.sendEmailVerification()
                                        .addOnSuccessListener(v -> {
                                            // 4) Oturum kapat + Login’e dön
                                            FirebaseAuth.getInstance().signOut();
                                            Intent i = new Intent(this, LoginActivity.class)
                                                    .putExtra("verificationSent", true)
                                                    .putExtra("email", email);
                                            startActivity(i);
                                            finish();
                                        })
                                        .addOnFailureListener(e -> {
                                            tvMsg.setText("Doğrulama e-postası gönderilemedi: " + e.getMessage());
                                            btn.setEnabled(true);
                                        });
                            })
                            .addOnFailureListener(e -> {
                                tvMsg.setText("Rol kaydı başarısız: " + e.getMessage());
                                btn.setEnabled(true);
                            });
                })
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        tvMsg.setText("Bu e-posta zaten kullanılıyor.");
                    } else {
                        tvMsg.setText("Kayıt başarısız: " + e.getMessage());
                    }
                    btn.setEnabled(true);
                });
    }

    private void recordKvkkConsent(String uid) {
        Map<String, Object> kvkk = new HashMap<>();
        kvkk.put("accepted", true);
        kvkk.put("version", KVKK_VERSION);
        kvkk.put("url", getString(R.string.kvkk_url));
        kvkk.put("acceptedAt", FieldValue.serverTimestamp());
        kvkk.put("appVersion", BuildConfig.VERSION_NAME);
        kvkk.put("locale", Locale.getDefault().toLanguageTag());

        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .set(java.util.Collections.singletonMap("kvkk", kvkk), SetOptions.merge());
    }
}
