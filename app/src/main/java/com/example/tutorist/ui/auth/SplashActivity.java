package com.example.tutorist.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherMainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

public class SplashActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler().postDelayed(() -> {
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

            // Doğrulanmış → rol oku ve yönlendir
            FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                    .addOnSuccessListener(doc -> {
                        String role = (doc.exists() && doc.getString("role") != null) ? doc.getString("role") : "";
                        routeByRole(role);
                    })
                    .addOnFailureListener(e -> {
                        // güvenli fallback: Login’a dön ve bilgi ver
                        Intent i = new Intent(this, LoginActivity.class)
                                .putExtra("email", user.getEmail())
                                .putExtra("banner", "Hesap rolü okunamadı. Lütfen tekrar giriş yapın.");
                        startActivity(i);
                        finish();
                    });
        }, 2000);
    }

    private void routeByRole(String role) {
        if ("teacher".equals(role)) {
            startActivity(new Intent(this, TeacherMainActivity.class));
        } else if ("student".equals(role)) {
            startActivity(new Intent(this, StudentMainActivity.class));
        } else {
            // beklenmeyen durum
            Intent i = new Intent(this, LoginActivity.class)
                    .putExtra("banner", "Hesap rolü eksik. Lütfen tekrar giriş yapın.");
            startActivity(i);
        }
        finish();
    }
}

