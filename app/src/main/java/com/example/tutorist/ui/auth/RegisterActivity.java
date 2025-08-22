package com.example.tutorist.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {
    private EditText etEmail, etPass, etPass2;
    private TextView tvMsg;
    private RadioGroup rgRole;
    private FirebaseAuth auth;
    private FirebaseFirestore db;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_register);

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        etEmail = findViewById(R.id.etEmail);
        etPass  = findViewById(R.id.etPass);
        etPass2 = findViewById(R.id.etPass2);
        rgRole  = findViewById(R.id.rgRole);
        tvMsg   = findViewById(R.id.tvMsg);

        Button btn = findViewById(R.id.btnRegister);
        btn.setOnClickListener(v -> doRegister(btn));
    }

    private void doRegister(Button btn) {
        String email = etEmail.getText().toString().trim();
        String p1 = etPass.getText().toString();
        String p2 = etPass2.getText().toString();

        int checkedId = rgRole.getCheckedRadioButtonId();
        String role = (checkedId == R.id.rbTeacher) ? "teacher"
                : (checkedId == R.id.rbStudent) ? "student" : null;

        if (email.isEmpty() || p1.isEmpty() || p2.isEmpty()) { tvMsg.setText("Tüm alanları doldurun."); return; }
        if (!p1.equals(p2)) { tvMsg.setText("Şifreler eşleşmiyor."); return; }
        if (role == null) { tvMsg.setText("Lütfen rol seçin."); return; }

        btn.setEnabled(false); tvMsg.setText("Kaydediliyor...");

        auth.createUserWithEmailAndPassword(email, p1)
                .addOnSuccessListener(res -> {
                    FirebaseUser user = res.getUser();
                    if (user == null) { tvMsg.setText("Kullanıcı oluşturulamadı."); btn.setEnabled(true); return; }

                    // 1) Rolü ve maili users/{uid} altına yaz
                    Map<String, Object> data = new HashMap<>();
                    data.put("role", role);
                    data.put("email", email);
                    data.put("createdAt", FieldValue.serverTimestamp());

                    db.collection("users").document(user.getUid())
                            .set(data, SetOptions.merge())
                            .addOnSuccessListener(w -> {
                                // 2) Doğrulama e-postası gönder
                                user.sendEmailVerification()
                                        .addOnSuccessListener(v -> {
                                            // 3) Oturumu kapat + Login’a dön (banner + e-posta önden dolu)
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
}
