// app/src/main/java/com/example/tutorist/ui/teacher/TeacherListActivity.java
package com.example.tutorist.ui.teacher;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.ui.student.TeacherAdapter;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TeacherListActivity extends AppCompatActivity {

    private String subjectId, subjectName;
    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvTitle, tvEmpty, tvError;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private TeacherAdapter adapter; // <-- SADECE bu adapter'ı kullanıyoruz

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_list);

        subjectId   = getIntent().getStringExtra("subjectId");
        subjectName = getIntent().getStringExtra("subjectName");

        tvTitle  = findViewById(R.id.tvTitle);
        tvEmpty  = findViewById(R.id.tvEmpty);
        tvError  = findViewById(R.id.tvError);
        rv       = findViewById(R.id.rvTeachers);
        progress = findViewById(R.id.progress);

        tvTitle.setText(subjectName != null ? subjectName : "Öğretmenler");

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TeacherAdapter(teacherId -> {
            // Öğretmen detayını bottom sheet’te aç
            TeacherDetailSheet.newInstance(teacherId)
                    .show(getSupportFragmentManager(), "teacherDetail");
        });
        rv.setAdapter(adapter);

        loadTeachers();
    }

    private void loadTeachers() {
        showLoading(true);

        // subjectsMap.{subjectId} > 0 olan öğretmenleri getir
        String mapField = "subjectsMap." + subjectId;

        db.collection("teacherProfiles")
                .whereGreaterThan(mapField, 0)
                .get()
                .addOnSuccessListener(this::processTeachers)
                .addOnFailureListener(e -> showError("Öğretmenler yüklenemedi: " + e.getMessage()));
    }

    private void processTeachers(QuerySnapshot snap) {
        List<com.example.tutorist.ui.student.TeacherAdapter.TeacherRow> list = new ArrayList<>();

        for (DocumentSnapshot d : snap.getDocuments()) {
            com.example.tutorist.ui.student.TeacherAdapter.TeacherRow row =
                    new com.example.tutorist.ui.student.TeacherAdapter.TeacherRow();
            row.id          = d.getId();
            row.fullName    = d.getString("displayName"); // teacherProfiles içindeki alan
            row.bio         = d.getString("bio");
            row.ratingAvg   = d.getDouble("ratingAvg");   // yoksa null
            row.ratingCount = d.getLong("ratingCount");   // yoksa null
            list.add(row);
        }

        adapter.submit(list);

        // Sunucu alanları boş ise, her bir öğretmen için yorumları okuyup ekranda güncelle
        for (int i = 0; i < list.size(); i++) {
            com.example.tutorist.ui.student.TeacherAdapter.TeacherRow row = list.get(i);
            if (row.ratingCount == null || row.ratingCount == 0L) {
                fetchRatingFallback(row, i); // ekranı günceller
            }
        }

        showLoading(false);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Profil alanları boşsa: review'lardan ortalama/sayıyı hesapla ve kartı güncelle. */
    private void fetchRatingFallback(
            com.example.tutorist.ui.student.TeacherAdapter.TeacherRow row,
            int position) {

        db.collection("teacherReviews")
                .whereEqualTo("teacherId", row.id)
                .get()
                .addOnSuccessListener(snap -> {
                    long cnt = 0;
                    double sum = 0;

                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Number n = (Number) d.get("rating"); // select yok; direkt alanı çekiyoruz
                        if (n != null) {
                            sum += n.doubleValue();
                            cnt++;
                        }
                    }

                    row.ratingCount = cnt;
                    row.ratingAvg   = cnt > 0 ? (sum / cnt) : 0.0;

                    // Kartı güncelle
                    adapter.notifyItemChanged(position);

                    // (opsiyonel) Profili de doldur ki bir dahaki listede direkt gelsin
                    HashMap<String, Object> up = new HashMap<>();
                    up.put("ratingAvg", row.ratingAvg);
                    up.put("ratingCount", row.ratingCount);
                    up.put("updatedAt", FieldValue.serverTimestamp());

                    db.collection("teacherProfiles").document(row.id)
                            .set(up, SetOptions.merge());
                })
                .addOnFailureListener(e ->
                        Log.e("TeacherListActivity", "fallback rating failed", e));
    }


    private void showLoading(boolean loading){
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        rv.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvError.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);
    }

    private void showError(String msg){
        showLoading(false);
        tvError.setText("Yüklenemedi: " + msg);
        tvError.setVisibility(View.VISIBLE);
    }
}
