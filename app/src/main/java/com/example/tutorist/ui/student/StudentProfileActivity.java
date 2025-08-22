package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;

public class StudentProfileActivity extends AppCompatActivity {
    private EditText etName, etPhone;
    private TextView tvMsg;
    private final UserRepo userRepo = new UserRepo();
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final String uid = auth.getUid();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_student_profile);

        etName = findViewById(R.id.etName);
        etPhone= findViewById(R.id.etPhone);
        tvMsg  = findViewById(R.id.tvMsg);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnLogout = findViewById(R.id.btnLogout);

        loadProfile();

        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String phone= etPhone.getText().toString().trim();
            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> tvMsg.setText("Kaydedildi."))
                    .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
        });

        btnLogout.setOnClickListener(v -> {
            auth.signOut();
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
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
}
