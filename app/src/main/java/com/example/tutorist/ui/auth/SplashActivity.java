// app/src/main/java/com/example/tutorist/ui/auth/SplashActivity.java
package com.example.tutorist.ui.auth;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.tutorist.R;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherMainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.HashMap;

public class SplashActivity extends AppCompatActivity {

    // Android 13+ bildirim izni sonucu için launcher
    private final ActivityResultLauncher<String> notifPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // izin verilsin/verilmesin token kaydını deneriz
                registerFcmToken();
                // izin süreci bitti → route
                route();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Küçük bir gecikme (splash animasyonu vs) ardından izin + token + route
        new android.os.Handler().postDelayed(this::prepareAndRoute, 1200);
    }

    /** Android 13+ için izni iste, ardından token kaydet ve route et. */
    private void prepareAndRoute() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { // izin/token sormaya gerek yok, login ekranına gideceğiz
            route();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
                return; // sonuç callback’inde route edeceğiz
            }
        }
        // < Android 13 veya izin zaten verilmiş → token kaydet ve route
        registerFcmToken();
        route();
    }

    /** Mevcut kullanıcı için FCM token’ı Firestore’a yazar. */
    private void registerFcmToken() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;

        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(t -> {
                    if (t == null || t.isEmpty()) return;

                    FirebaseFirestore db = FirebaseFirestore.getInstance();

                    // users/{uid}.fcmTokens
                    db.collection("users").document(u.getUid())
                            .set(new HashMap<String, Object>() {{
                                put("fcmTokens", FieldValue.arrayUnion(t));
                            }}, SetOptions.merge());

                    // users/{uid}/devices/{token}
                    HashMap<String, Object> dev = new HashMap<>();
                    dev.put("token", t);
                    dev.put("platform", "android");
                    dev.put("lastSeen", FieldValue.serverTimestamp());
                    db.collection("users").document(u.getUid())
                            .collection("devices").document(t)
                            .set(dev, SetOptions.merge());
                });
    }

    /** Var olan yönlendirme mantığınız (doğrulama + rol) */
    private void route() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, AuthLandingActivity.class));
            finish();
            return;
        }
        if (!user.isEmailVerified()) {
            startActivity(new Intent(this, LoginActivity.class)
                    .putExtra("needsVerification", true)
                    .putExtra("email", user.getEmail()));
            finish();
            return;
        }
        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    String role = (doc.exists() && doc.getString("role") != null) ? doc.getString("role") : "";
                    if ("teacher".equals(role)) {
                        startActivity(new Intent(this, TeacherMainActivity.class));
                    } else if ("student".equals(role)) {
                        startActivity(new Intent(this, StudentMainActivity.class));
                    } else {
                        startActivity(new Intent(this, LoginActivity.class)
                                .putExtra("banner","Hesap rolü eksik. Lütfen tekrar giriş yapın."));
                    }
                    finish();
                })
                .addOnFailureListener(e -> {
                    startActivity(new Intent(this, LoginActivity.class)
                            .putExtra("email", user.getEmail())
                            .putExtra("banner", "Hesap rolü okunamadı. Lütfen tekrar giriş yapın."));
                    finish();
                });
    }
}
