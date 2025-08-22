package com.example.tutorist.ui.auth;

import static android.content.Intent.getIntent;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.google.firebase.auth.FirebaseAuth;

public class ForgotPasswordActivity extends AppCompatActivity {
    private EditText etEmail; private TextView tvMsg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_forgot_password);
        etEmail = findViewById(R.id.etEmail);
        tvMsg = findViewById(R.id.tvMsg);

        String email = getIntent().getStringExtra("email");
        if (email != null && !email.isEmpty()) etEmail.setText(email);

        findViewById(R.id.btnReset).setOnClickListener(v -> {
            String e = etEmail.getText().toString().trim();
            if (e.isEmpty()) { tvMsg.setText("Lütfen e-postanızı girin."); return; }
            FirebaseAuth.getInstance().sendPasswordResetEmail(e)
                    .addOnSuccessListener(s -> tvMsg.setText("Şifre yenileme e-postası gönderildi."))
                    .addOnFailureListener(err -> tvMsg.setText("Gönderilemedi: " + err.getMessage()));
        });
    }
}
