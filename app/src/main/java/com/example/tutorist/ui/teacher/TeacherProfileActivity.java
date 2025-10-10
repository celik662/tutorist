// app/src/main/java/com/example/tutorist/ui/teacher/TeacherProfileActivity.java
package com.example.tutorist.ui.teacher;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.tutorist.R;
import com.example.tutorist.push.AppMessagingService;
import com.example.tutorist.repo.TeacherProfileRepo;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class TeacherProfileActivity extends AppCompatActivity {

    private EditText etName, etPhone, etBio;
    private TextView tvMsg, tvPayoutSummary;
    private ImageView ivAvatar;

    private final UserRepo userRepo = new UserRepo();
    private final TeacherProfileRepo teacherProfileRepo = new TeacherProfileRepo();
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private String uid;

    private ActivityResultLauncher<String> pickImageLauncher;
    private final FirebaseStorage storage = FirebaseStorage.getInstance();

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

        // 1) Görsel seçici (gallery)
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        uploadAvatar(uri);
                    }
                });

        // 2) Avatar’a tıklayınca galeriyi aç
        ivAvatar.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        loadProfile();
        loadPayoutSummary(); // varsa durum metnini günceller

        btnSave.setOnClickListener(v -> {
            String name  = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            String bio   = etBio.getText().toString().trim();

            if (name.isEmpty()) { tvMsg.setText("Lütfen ad-soyad girin."); return; }

            tvMsg.setText("Kaydediliyor...");

            userRepo.updateUserBasic(uid, name, phone)
                    .continueWithTask(t -> teacherProfileRepo.updateDisplayName(uid, name))
                    .continueWithTask(t -> teacherProfileRepo.updateBio(uid, bio))
                    .addOnSuccessListener(x -> tvMsg.setText("Kaydedildi."))
                    .addOnFailureListener(e -> tvMsg.setText("Hata: " + (e != null ? e.getMessage() : "")));
        });

        uid = auth.getUid();
        if (uid == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }

        btnPayout.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherPayoutActivity.class)));

        btnLogout.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Çıkış yapılsın mı?")
                .setMessage("Hesabınızdan çıkış yapacaksınız.")
                .setNegativeButton("İptal", null)
                .setPositiveButton("Çıkış yap", (d, w) -> {
                    v.setEnabled(true); // diyalog kapanınca buton context’i kaçmasın

                    // 1) Token’ı kullanıcıdan kopar + cihazdan sil
                    AppMessagingService.detachTokenFromCurrentUserAndDeleteAsync(getApplicationContext())
                            .addOnCompleteListener(t -> {
                                // 2) Artık güvenle signOut
                                FirebaseAuth.getInstance().signOut();

                                // 3) Login’e tertemiz dön (back stack’i temizle)
                                Intent i = new Intent(this, LoginActivity.class);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(i);
                                finish();
                            });
                })
                .show());

    }

    /** Seçilen resmi Storage'a yükle, URL’i teacherProfiles.photoUrl’e yaz */
    private void uploadAvatar(Uri imageUri) {
        if (imageUri == null) return;

        tvMsg.setText("Fotoğraf yükleniyor...");

        String curUid = FirebaseAuth.getInstance().getUid();
        if (curUid == null) {
            tvMsg.setText("Oturum bulunamadı.");
            return;
        }

        StorageReference ref = storage.getReference()
                .child("teachers")
                .child(curUid)
                .child("avatar.jpg");

        ref.putFile(imageUri)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    return ref.getDownloadUrl();
                })
                .continueWithTask(task -> {
                    Uri download = task.getResult();
                    return FirebaseFirestore.getInstance()
                            .collection("teacherProfiles")
                            .document(curUid)
                            .update("photoUrl", download.toString());
                })
                .addOnSuccessListener(x -> {
                    tvMsg.setText("Fotoğraf güncellendi.");
                    Glide.with(this)
                            .load(imageUri) // hızlı his için seçilen resmi göstermek de olur
                            .placeholder(R.drawable.ic_person)
                            .circleCrop()
                            .into(ivAvatar);
                })
                .addOnFailureListener(e ->
                        tvMsg.setText("Fotoğraf yüklenemedi: " + (e != null ? e.getMessage() : "")));
    }

    @Override protected void onResume() {
        super.onResume();
        AppMessagingService.syncCurrentFcmToken(); // sessizce token’i günceller
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        }
    }

    private void loadProfile() {
        // ... mevcut kodun
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
                            // Glide ile resmi çek
                            Glide.with(this)
                                    .load(photo)
                                    .placeholder(R.drawable.ic_person)
                                    .circleCrop()
                                    .into(ivAvatar);
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
