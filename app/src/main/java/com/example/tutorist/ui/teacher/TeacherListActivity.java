package com.example.tutorist.ui.teacher;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
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
import java.util.Map;

public class TeacherListActivity extends AppCompatActivity {

    private String subjectId, subjectName;
    private RecyclerView rv;
    private View progress; // <-- XML’de ProgressBar ya da CircularProgressIndicator olabilir
    private TextView tvTitle, tvEmpty, tvError;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private TeacherAdapter adapter; // <-- SADECE bu adapter'ı kullanıyoruz

    private static final String TAG = "TeacherListActivity";

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
            TeacherDetailSheet.newInstance(teacherId, subjectId, subjectName)
                    .show(getSupportFragmentManager(), "teacherDetail");
        });
        rv.setAdapter(adapter);

        if (subjectId == null || subjectId.trim().isEmpty()) {
            showError("Ders bilgisi eksik (subjectId yok).");
            return;
        }

        loadTeachers();
    }

    @Nullable
    private Integer toIntOrNull(Object o){
        if (o == null) return null;
        if (o instanceof Number) return ((Number)o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignore){ return null; }
    }

    @Nullable
    private Integer extractPriceFromProfile(DocumentSnapshot d, String subjectId){
        if (d == null || subjectId == null) return null;

        // 1) teacherProfiles.prices.{subjectId} (varsa)
        Integer n = toIntOrNull(d.get("prices." + subjectId));
        if (n != null && n > 0) return n;

        // 2) teacherProfiles.subjectsMap.{subjectId}.price (nested obje ise)
        Object sm = d.get("subjectsMap." + subjectId);
        if (sm instanceof Map) {
            Integer n2 = toIntOrNull(((Map<?,?>) sm).get("price"));
            if (n2 != null && n2 > 0) return n2;
        }

        // 3) teacherProfiles.subjectsMap.{subjectId} doğrudan sayı ise
        Integer n3 = toIntOrNull(sm);
        if (n3 != null && n3 > 0) return n3;

        return null;
    }

    private void processTeachers(QuerySnapshot snap) {
        List<TeacherAdapter.TeacherRow> list = new ArrayList<>();

        for (DocumentSnapshot d : snap.getDocuments()) {
            TeacherAdapter.TeacherRow row = new TeacherAdapter.TeacherRow();
            row.id          = d.getId();
            row.fullName    = d.getString("displayName"); // sabah çalışan şema
            row.bio         = d.getString("bio");
            row.ratingAvg   = d.getDouble("ratingAvg");
            row.ratingCount = d.getLong("ratingCount");

            // profilden fiyatı dene
            row.price = extractPriceFromProfile(d, subjectId);

            list.add(row);
        }

        adapter.submit(list);

        Log.d(TAG, "processTeachers subjectId=" + subjectId + " teacherCount=" + list.size());

        // profilden gelmeyenler için A/B/C fallback
        for (TeacherAdapter.TeacherRow row : list) {
            if (row.price == null || row.price <= 0) {
                loadPriceForRow(row.id, subjectId);
            }
        }

        for (int i = 0; i < list.size(); i++) {
            TeacherAdapter.TeacherRow row = list.get(i); // 'var' yerine açık tip
            if (row.ratingCount == null || row.ratingCount == 0L) {
                fetchRatingFallback(row, i);
            }
        }

        showLoading(false);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // row kullanılmadığı için sade imza
    private void loadPriceForRow(String teacherId, String subjectId){
        if (teacherId == null || subjectId == null) {
            Log.w(TAG, "loadPriceForRow: teacherId/subjectId null");
            return;
        }
        final String tsId = teacherId + "_" + subjectId;
        Log.d(TAG, "price: try A teacherSubjects/" + tsId);

        db.collection("teacherSubjects").document(tsId).get()
                .addOnSuccessListener(d -> {
                    Log.d(TAG, "A exists=" + d.exists() + " data=" + (d.exists()? d.getData() : null));
                    Integer price = toIntOrNull(d.get("price"));
                    if (price != null && price > 0) {
                        adapter.updatePriceById(teacherId, price);
                    } else {
                        Log.d(TAG, "price: try B teacherSubjects/" + teacherId + "/subjects/" + subjectId);
                        db.collection("teacherSubjects").document(teacherId)
                                .collection("subjects").document(subjectId).get()
                                .addOnSuccessListener(sd -> {
                                    Log.d(TAG, "B exists=" + sd.exists() + " data=" + (sd.exists()? sd.getData() : null));
                                    Integer p2 = toIntOrNull(sd.get("price"));
                                    if (p2 != null && p2 > 0) {
                                        adapter.updatePriceById(teacherId, p2);
                                    } else {
                                        Log.d(TAG, "price: try C subjects/" + subjectId);
                                        db.collection("subjects").document(subjectId).get()
                                                .addOnSuccessListener(ss -> {
                                                    Log.d(TAG, "C exists=" + ss.exists() + " data=" + (ss.exists()? ss.getData() : null));
                                                    Integer p3 = toIntOrNull(ss.get("price"));
                                                    if (p3 != null && p3 > 0) {
                                                        adapter.updatePriceById(teacherId, p3);
                                                    } else {
                                                        Log.w(TAG, "price not found for teacher=" + teacherId + " subject=" + subjectId);
                                                    }
                                                })
                                                .addOnFailureListener(e -> Log.e(TAG, "C subjects fetch failed", e));
                                    }
                                })
                                .addOnFailureListener(e -> Log.e(TAG, "B subcollection fetch failed", e));
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "A teacherSubjects fetch failed", e));
    }

    private void loadTeachers() {
        showLoading(true);

        if (subjectId == null || subjectId.trim().isEmpty()) {
            showError("Ders bilgisi eksik.");
            return;
        }

        // sabah çalışan sorgu: teacherProfiles.subjectsMap.{subjectId} > 0
        String mapField = "subjectsMap." + subjectId;

        db.collection("teacherProfiles")
                .whereGreaterThan(mapField, 0)
                .get()
                .addOnSuccessListener(this::processTeachers)
                .addOnFailureListener(e -> showError("Öğretmenler yüklenemedi: " + e.getMessage()));
    }

    /** Profil alanları boşsa: review'lardan ortalama/sayıyı hesapla ve kartı güncelle. */
    private void fetchRatingFallback(TeacherAdapter.TeacherRow row, int position) {
        db.collection("teacherReviews")
                .whereEqualTo("teacherId", row.id)
                .get()
                .addOnSuccessListener(snap -> {
                    long cnt = 0;
                    double sum = 0;

                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Number n = (Number) d.get("rating");
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
                        Log.e(TAG, "fallback rating failed", e));
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
