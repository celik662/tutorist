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
    private TextView tvMsg, tvPayoutSummary;
    private ImageView ivAvatar;

    private final UserRepo userRepo = new UserRepo();
    private final TeacherProfileRepo teacherProfileRepo = new TeacherProfileRepo();
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private String uid;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_profile);
        setTitle("Profil");

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();
        uid  = auth.getUid();
        if (uid == null) { finish(); return; }

        // XML’de verdiğin ID’lerle birebir
        ivAvatar        = findViewById(R.id.ivAvatar);
        etName          = findViewById(R.id.etName);
        etPhone         = findViewById(R.id.etPhone);
        etBio           = findViewById(R.id.etBio);
        tvMsg           = findViewById(R.id.tvMsg);
        tvPayoutSummary = findViewById(R.id.tvPayoutSummary); // karttaki kısa özet
        Button btnSave   = findViewById(R.id.btnSave);
        Button btnLogout = findViewById(R.id.btnLogout);
        Button btnPayout = findViewById(R.id.btnPayout);

        loadProfile();
        loadPayoutSummary(); // varsa durum metnini günceller

        btnSave.setOnClickListener(v -> {
            String name  = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            String bio   = etBio.getText().toString().trim();

            tvMsg.setText("Kaydediliyor...");

            userRepo.updateUserBasic(uid, name, phone)
                    .continueWithTask(t -> teacherProfileRepo.updateDisplayName(uid, name))
                    .continueWithTask(t -> teacherProfileRepo.updateBio(uid, bio))
                    .addOnSuccessListener(x -> tvMsg.setText("Kaydedildi."))
                    .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
        });

        btnPayout.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherPayoutActivity.class)));

        btnLogout.setOnClickListener(v -> new AlertDialog.Builder(this)
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
                .show());
    }

    private void loadProfile() {
        // users: ad & telefon
        userRepo.loadUser(uid).addOnSuccessListener(data -> {
            if (data != null) {
                Object n = data.get("fullName");
                Object p = data.get("phone");
                if (n != null) etName.setText(String.valueOf(n));
                if (p != null) etPhone.setText(String.valueOf(p));
            }
        });

        // teacherProfiles: bio (+ displayName / photoUrl)
        db.collection("teacherProfiles").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String bio = doc.getString("bio");
                        if (bio != null) etBio.setText(bio);

                        if (etName.getText().length() == 0) {
                            String dn = doc.getString("displayName");
                            if (dn != null) etName.setText(dn);
                        }

                        String photo = doc.getString("photoUrl");
                        if (photo != null && ivAvatar != null) {
                            // Glide eklediysen aç:
                            // Glide.with(this).load(photo)
                            //      .placeholder(R.drawable.ic_person)
                            //      .into(ivAvatar);
                        }
                    }
                });
    }

    private void loadPayoutSummary() {
        if (tvPayoutSummary == null) return;
        // Kendi şemanı uydur: örnek “payoutAccounts/{uid} { status: 'ready'|'pending' }”
        db.collection("payoutAccounts").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String st = doc.getString("status");
                        if (st != null) tvPayoutSummary.setText("Ödeme hesabı: " + st);
                    }
                });
    }
}
