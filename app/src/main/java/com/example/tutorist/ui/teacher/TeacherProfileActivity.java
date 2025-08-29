// app/src/main/java/com/example/tutorist/ui/teacher/TeacherProfileActivity.java
package com.example.tutorist.ui.teacher;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.example.tutorist.repo.TeacherProfileRepo;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class TeacherProfileActivity extends AppCompatActivity {

    private EditText etName, etPhone, etBio;
    private TextView tvMsg;

    private final UserRepo userRepo = new UserRepo();
    private final TeacherProfileRepo teacherProfileRepo = new TeacherProfileRepo();
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String uid;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_profile);

        uid = auth.getUid();
        if (uid == null) { finish(); return; }

        etName  = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        etBio   = findViewById(R.id.etBio);
        tvMsg   = findViewById(R.id.tvMsg);
        Button btnSave   = findViewById(R.id.btnSave);
        Button btnLogout = findViewById(R.id.btnLogout);

        loadProfile();

        btnSave.setOnClickListener(v -> {
            String name  = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            String bio   = etBio.getText().toString().trim();
            tvMsg.setText("Kaydediliyor...");

            // 1) users -> ad & telefon
            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> {
                        // 2) teacherProfiles -> displayName & bio
                        teacherProfileRepo.updateDisplayName(uid, name)
                                .continueWithTask(t -> teacherProfileRepo.updateBio(uid, bio))
                                .addOnSuccessListener(x -> tvMsg.setText("Kaydedildi."))
                                .addOnFailureListener(e ->
                                        tvMsg.setText("Profil güncellendi ama bazı alanlar yazılamadı: " + e.getMessage()));
                    })
                    .addOnFailureListener(e ->
                            tvMsg.setText("Kullanıcı güncellenemedi: " + e.getMessage()));
        });

        btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Çıkış yapılsın mı?")
                    .setMessage("Hesabınızdan çıkış yapacaksınız.")
                    .setNegativeButton("İptal", null)
                    .setPositiveButton("Çıkış yap", (d, w) -> {
                        auth.signOut();
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    })
                    .show();
        });
    }

    private void loadProfile() {
        // users: ad & telefon
        userRepo.loadUser(uid)
                .addOnSuccessListener(data -> {
                    if (data != null) {
                        Object n = data.get("fullName");
                        Object p = data.get("phone");
                        if (n != null) etName.setText(String.valueOf(n));
                        if (p != null) etPhone.setText(String.valueOf(p));
                    }
                })
                .addOnFailureListener(e -> tvMsg.setText("Profil okunamadı: " + e.getMessage()));

        // teacherProfiles: bio (+ displayName gerekirse)
        db.collection("teacherProfiles").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String bio = doc.getString("bio");
                        if (bio != null) etBio.setText(bio);
                        // displayName de var ise, boşsa name'e düşebiliriz
                        if (etName.getText().toString().trim().isEmpty()) {
                            String dn = doc.getString("displayName");
                            if (dn != null) etName.setText(dn);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    // sessiz geçebiliriz; kritik değil
                });
    }
}
