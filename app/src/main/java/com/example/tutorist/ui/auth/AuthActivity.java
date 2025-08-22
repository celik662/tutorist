package com.example.tutorist.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;
import com.google.firebase.auth.*;

public class AuthActivity extends AppCompatActivity {

    private EditText etEmail, etPassword;
    private TextView tvInfo;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvInfo = findViewById(R.id.tvInfo);
        Button btnLogin = findViewById(R.id.btnLogin);
        Button btnRegister = findViewById(R.id.btnRegister);

        auth = FirebaseAuth.getInstance();

        btnRegister.setOnClickListener(v -> register());
        btnLogin.setOnClickListener(v -> login());
    }

    private void register() {
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(pass)) {
            tvInfo.setText("Lütfen e-posta ve şifre girin.");
            return;
        }

        auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(res -> {
                    FirebaseUser user = res.getUser();
                    if (user != null) {
                        user.sendEmailVerification();
                        tvInfo.setText("E-posta doğrulama linki gönderildi. Lütfen e-postanızı doğrulayın.");
                    }
                })
                .addOnFailureListener(e -> tvInfo.setText("Kayıt başarısız: " + e.getMessage()));
    }

    private void login() {
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(pass)) {
            tvInfo.setText("Lütfen e-posta ve şifre girin.");
            return;
        }

        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(res -> {
                    FirebaseUser user = res.getUser();
                    if (user != null && user.isEmailVerified()) {
                        startActivity(new Intent(this, RoleSelectActivity.class));
                        finish();
                    } else {
                        tvInfo.setText("Lütfen e-posta adresinizi doğrulayın.");
                    }
                })
                .addOnFailureListener(e -> tvInfo.setText("Giriş başarısız: " + e.getMessage()));
    }
}
