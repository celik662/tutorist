package com.example.tutorist.ui.teacher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class TeacherMainActivity extends AppCompatActivity {
    private ListenerRegistration pendingReg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_main);
        ensureTeacherProfileDoc();

        Button btnSubjects = findViewById(R.id.btnSubjects);
        Button btnAvailability = findViewById(R.id.btnAvailability);
        Button btnProfile = findViewById(R.id.btnProfile);

        findViewById(R.id.btnRequests).setOnClickListener(v ->
                startActivity(new Intent(this, TeacherRequestsActivity.class)));

        // Savunmacı kontrol – biri null ise doğrudan uyarı verelim:
        if (btnSubjects == null || btnAvailability == null || btnProfile == null) {
            Toast.makeText(this, "activity_teacher_main.xml içinde buton id'leri bulunamadı.", Toast.LENGTH_LONG).show();
            throw new IllegalStateException("Missing required views in activity_teacher_main.xml");
        }

        btnSubjects.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherSubjectsActivity.class)));

        btnAvailability.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherAvailabilityActivity.class)));


        btnProfile.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherProfileActivity.class)));
    }

    private void ensureTeacherProfileDoc() {
        String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        var db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        var ref = db.collection("teacherProfiles").document(uid);

        ref.get().addOnSuccessListener(doc -> {
            if (doc.exists()) return;

            db.collection("users").document(uid).get().addOnSuccessListener(u -> {
                String name = "Öğretmen";
                if (u != null && u.exists()) {
                    Object n = u.get("fullName");
                    Object e = u.get("email");
                    name = n != null ? String.valueOf(n) : (e != null ? String.valueOf(e) : name);
                }
                java.util.Map<String,Object> m = new java.util.HashMap<>();
                m.put("displayName", name);
                m.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                ref.set(m, com.google.firebase.firestore.SetOptions.merge());
            });
        });
    }


    private void subscribePendingBadge() {
        TextView badge = findViewById(R.id.badgeRequests);
        String uid = FirebaseAuth.getInstance().getUid();
        if (pendingReg != null) pendingReg.remove();

        pendingReg = FirebaseFirestore.getInstance().collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "pending")
                .addSnapshotListener((snap, e) -> {
                    int count = (snap != null) ? snap.size() : 0;
                    if (count > 0) {
                        badge.setText(String.valueOf(Math.min(count, 99)));
                        badge.setVisibility(View.VISIBLE);
                    } else {
                        badge.setVisibility(View.GONE);
                    }
                });
        pendingReg = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "pending")
                .addSnapshotListener((snap, e) -> {
                    int count = (snap != null) ? snap.size() : 0;
                    if (count > 0) {
                        badge.setText(String.valueOf(Math.min(count, 99)));
                        badge.setVisibility(View.VISIBLE);
                    } else {
                        badge.setVisibility(View.GONE);
                    }
                });

    }


    @Override protected void onStart() { super.onStart(); subscribePendingBadge(); }
    @Override protected void onDestroy() { if (pendingReg!=null) pendingReg.remove(); super.onDestroy(); }


}
