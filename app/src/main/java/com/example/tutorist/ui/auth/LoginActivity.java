package com.example.tutorist.ui.auth;

import android.widget.Toast;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherMainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {
    private EditText etEmail, etPass;
    private TextView tvMsg, tvBanner;
    private Button btnForgot, btnResendVerify, btnLogin;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        auth = FirebaseAuth.getInstance();

        etEmail = findViewById(R.id.etEmail);
        etPass  = findViewById(R.id.etPass);
        tvMsg   = findViewById(R.id.tvMsg);
        tvBanner= findViewById(R.id.tvBanner);
        btnForgot = findViewById(R.id.btnForgot);
        btnLogin = findViewById(R.id.btnLogin);
        btnResendVerify = findViewById(R.id.btnResendVerify);

        // Splash/Register’dan gelen prefill / banner
        Intent i = getIntent();
        String prefill = i.getStringExtra("email");
        if (prefill != null) etEmail.setText(prefill);

        if (i.getBooleanExtra("verificationSent", false)) {
            tvBanner.setText("Doğrulama e-postası gönderildi. Lütfen posta kutunuzu kontrol edin.");
        } else if (i.getBooleanExtra("needsVerification", false)) {
            tvBanner.setText("Lütfen e-posta adresinizi doğrulayın ve tekrar giriş yapın.");
            btnResendVerify.setVisibility(View.VISIBLE);
        }
        String banner = i.getStringExtra("banner");
        if (banner != null && !banner.isEmpty()) tvBanner.setText(banner);

        btnLogin.setOnClickListener(v -> doLogin());
        btnForgot.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            startActivity(new Intent(this, ForgotPasswordActivity.class).putExtra("email", email));
        });
        btnResendVerify.setOnClickListener(v -> resendVerification());

        // FCM token’ı kaydet (yedek)
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(t -> {
                    new com.example.tutorist.push.AppMessagingService().onNewToken(t);
                });
    }

    private void doLogin() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPass.getText().toString();

        if (email.isEmpty() || pass.isEmpty()) {
            tvMsg.setText("E-posta ve şifre girin.");
            return;
        }
        if (!com.example.tutorist.util.NetworkUtil.isOnline(this)) {
            tvMsg.setText("İnternet bağlantısı yok. Lütfen ağınızı kontrol edin.");
            return;
        }

        tvMsg.setText("Giriş yapılıyor…");
        tvBanner.setText("");
        btnResendVerify.setVisibility(View.GONE);
        btnLogin.setEnabled(false);

        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        tvMsg.setText("Kullanıcı bulunamadı.");
                        btnLogin.setEnabled(true);
                        return;
                    }

                    user.reload()
                            .addOnSuccessListener(v -> {
                                if (!user.isEmailVerified()) {
                                    tvMsg.setText("");
                                    tvBanner.setText("E-postanız doğrulanmamış. Lütfen posta kutunuzu kontrol edin.");
                                    btnResendVerify.setVisibility(View.VISIBLE);
                                    btnResendVerify.setOnClickListener(xx ->
                                            user.sendEmailVerification()
                                                    .addOnSuccessListener(x -> Toast.makeText(this, "Doğrulama e-postası gönderildi.", Toast.LENGTH_SHORT).show())
                                                    .addOnFailureListener(e -> Toast.makeText(this, "E-posta gönderilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show())
                                    );
                                    auth.signOut();
                                    btnLogin.setEnabled(true);
                                    return;
                                }

                                // E-posta doğrulandı → users/{uid} oku
                                FirebaseFirestore db = FirebaseFirestore.getInstance();
                                db.collection("users").document(user.getUid())
                                        .get()
                                        .addOnSuccessListener(doc -> {
                                            // 1) Adı garanti altına al → sonra güncel dokümanla role’ye göre yönlendir + mirror
                                            ensureDisplayName(user, doc, /*assumedRole*/ null, () -> {
                                                db.collection("users").document(user.getUid())
                                                        .get()
                                                        .addOnSuccessListener(freshDoc -> {
                                                            routeByRoleDocWithMirror(user, freshDoc);
                                                            btnLogin.setEnabled(true);
                                                        })
                                                        .addOnFailureListener(e -> {
                                                            tvBanner.setText("Rol bilgisi okunamadı: " + e.getMessage());
                                                            auth.signOut();
                                                            btnLogin.setEnabled(true);
                                                        });
                                            });
                                        })
                                        .addOnFailureListener(e -> {
                                            tvBanner.setText("Rol bilgisi okunamadı: " + e.getMessage());
                                            auth.signOut();
                                            btnLogin.setEnabled(true);
                                        });
                            })
                            .addOnFailureListener(e -> {
                                tvMsg.setText("Hesap bilgisi yenilenemedi: " + e.getMessage());
                                auth.signOut();
                                btnLogin.setEnabled(true);
                            });
                })
                .addOnFailureListener(e -> {
                    tvMsg.setText(mapAuthError(e));
                    btnLogin.setEnabled(true);
                });
    }

    /* ---------- İSİM GARANTİSİ & AYNALAMA ---------- */

    private static boolean isBlank(@Nullable String s){
        return s == null || s.trim().isEmpty();
    }

    /** users/{uid}.fullName’i garanti altına alır (Google displayName → fallback “Yeni …”). */
    private void ensureDisplayName(FirebaseUser user,
                                   @Nullable DocumentSnapshot userDoc,
                                   @Nullable String assumedRole,
                                   Runnable onDone){
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        String current = (userDoc != null) ? userDoc.getString("fullName") : null;
        if (!isBlank(current)) { onDone.run(); return; }

        String fromProvider = user.getDisplayName();
        String fallback;
        if (!isBlank(fromProvider)) {
            fallback = fromProvider.trim();
        } else {
            if ("teacher".equals(assumedRole)) fallback = "Yeni Öğretmen";
            else if ("student".equals(assumedRole)) fallback = "Yeni Öğrenci";
            else fallback = "Yeni eğitmen";
        }

        Map<String,Object> up = new HashMap<>();
        up.put("fullName", fallback);
        up.put("fullNameLower", fallback.toLowerCase(new Locale("tr","TR")));
        up.put("updatedAt", FieldValue.serverTimestamp());

        db.collection("users").document(user.getUid())
                .set(up, SetOptions.merge())
                .addOnSuccessListener(v -> onDone.run())
                .addOnFailureListener(e -> onDone.run()); // hata olsa da akışı bloklama
    }

    /** Öğretmense adını teacherProfiles/{uid}.displayName alanına da yazar (mirror). */
    private void mirrorNameToTeacherProfile(String uid, String name){
        if (isBlank(uid) || isBlank(name)) return;
        FirebaseFirestore.getInstance()
                .collection("teacherProfiles").document(uid)
                .set(new HashMap<String,Object>() {{
                         put("displayName", name);
                         put("displayNameLower", name.toLowerCase(new Locale("tr","TR")));
                         put("updatedAt", FieldValue.serverTimestamp());
                     }},
                        SetOptions.merge());
    }

    /** Rol dokümanına göre yönlendirir; öğretmense isim mirror’ını yapar. */
    private void routeByRoleDocWithMirror(FirebaseUser user, DocumentSnapshot doc){
        String role = (doc.exists() && doc.getString("role") != null) ? doc.getString("role") : "";
        String name = doc.getString("fullName");

        if ("teacher".equals(role) && !isBlank(name)) {
            mirrorNameToTeacherProfile(user.getUid(), name);
            startActivity(new Intent(this, TeacherMainActivity.class));
            finish();
        } else if ("student".equals(role)) {
            startActivity(new Intent(this, StudentMainActivity.class));
            finish();
        } else {
            tvBanner.setText("Hesap rolü eksik veya geçersiz. Lütfen tekrar giriş yapın.");
            FirebaseAuth.getInstance().signOut();
        }
    }

    /* ---------- MEVCUTLAR ---------- */

    private String mapAuthError(Exception e) {
        if (e instanceof com.google.firebase.FirebaseNetworkException) {
            return "Ağ hatası: İnternet bağlantınızı kontrol edin.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            return "E-posta veya şifre hatalı.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            return "Böyle bir kullanıcı bulunamadı.";
        }
        if (e instanceof com.google.firebase.FirebaseTooManyRequestsException) {
            return "Çok fazla deneme yapıldı. Lütfen biraz sonra tekrar deneyin.";
        }
        return "Giriş başarısız: " + e.getMessage();
    }

    private void resendVerification() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPass.getText().toString();
        if (email.isEmpty() || pass.isEmpty()) {
            tvMsg.setText("Yeniden göndermek için önce giriş bilgilerinizi girin.");
            return;
        }

        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(res -> {
                    FirebaseUser u = res.getUser();
                    if (u == null) { tvMsg.setText("Kullanıcı bulunamadı."); return; }

                    u.reload().addOnSuccessListener(r -> {
                        if (u.isEmailVerified()) {
                            tvMsg.setText("E-posta zaten doğrulanmış görünüyor.");
                            FirebaseAuth.getInstance().signOut();
                        } else {
                            u.sendEmailVerification()
                                    .addOnSuccessListener(v -> {
                                        tvMsg.setText("Doğrulama e-postası yeniden gönderildi.");
                                        btnResendVerify.setVisibility(View.VISIBLE);
                                        FirebaseAuth.getInstance().signOut();
                                    })
                                    .addOnFailureListener(e -> {
                                        tvMsg.setText("Gönderilemedi: " + e.getMessage());
                                        FirebaseAuth.getInstance().signOut();
                                    });
                        }
                    });
                })
                .addOnFailureListener(e -> tvMsg.setText("Oturum açılamadı: " + e.getMessage()));
    }
}
