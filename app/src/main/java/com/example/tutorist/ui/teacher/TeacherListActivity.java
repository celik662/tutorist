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
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TeacherListActivity extends AppCompatActivity {

    private String subjectId, subjectName;
    private RecyclerView rv;
    private View progress;
    private TextView tvTitle, tvEmpty, tvError;
    private TextInputEditText etSearch; // << eklenen

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private TeacherAdapter adapter;

    private static final String TAG = "TeacherListActivity";

    // Filtre için bellekte tutulan tam liste
    private final List<TeacherAdapter.TeacherRow> all = new ArrayList<>();

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
        etSearch = findViewById(R.id.etSearch); // << eklenen

        tvTitle.setText(subjectName != null ? subjectName : "Öğretmenler");

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TeacherAdapter(teacherId -> {
            TeacherDetailSheet.newInstance(teacherId, subjectId, subjectName)
                    .show(getSupportFragmentManager(), "teacherDetail");
        });
        rv.setAdapter(adapter);

        // Arama: canlı filtre
        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

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

        Integer n = toIntOrNull(d.get("prices." + subjectId));
        if (n != null && n > 0) return n;

        Object sm = d.get("subjectsMap." + subjectId);
        if (sm instanceof Map) {
            Integer n2 = toIntOrNull(((Map<?,?>) sm).get("price"));
            if (n2 != null && n2 > 0) return n2;
        }

        Integer n3 = toIntOrNull(sm);
        if (n3 != null && n3 > 0) return n3;

        return null;
    }

    private void processTeachers(QuerySnapshot snap) {
        List<TeacherAdapter.TeacherRow> list = new ArrayList<>();

        for (DocumentSnapshot d : snap.getDocuments()) {
            TeacherAdapter.TeacherRow row = new TeacherAdapter.TeacherRow();
            row.id          = d.getId();
            row.fullName    = d.getString("displayName");
            row.bio         = d.getString("bio");
            row.ratingAvg   = d.getDouble("ratingAvg");
            row.ratingCount = d.getLong("ratingCount");
            row.price       = extractPriceFromProfile(d, subjectId);
            row.completedCount = d.getLong("completedCount");

            list.add(row);
        }

        // Tam listeyi güncelle ve filtre uygula
        all.clear();
        all.addAll(list);
        applyFilter(); // arama metni varsa ona göre, yoksa tam liste

        Log.d(TAG, "processTeachers subjectId=" + subjectId + " teacherCount=" + list.size());

        // Fiyat fallback
        for (TeacherAdapter.TeacherRow row : list) {
            if (row.price == null || row.price <= 0) {
                loadPriceForRow(row.id, subjectId);
            }
        }

        // Rating fallback
        for (int i = 0; i < list.size(); i++) {
            TeacherAdapter.TeacherRow row = list.get(i);
            if (row.ratingCount == null || row.ratingCount == 0L) {
                fetchRatingFallback(row, i);
            }
        }

        for (int i = 0; i < list.size(); i++) {
            TeacherAdapter.TeacherRow row = list.get(i);
            if (row.completedCount == null) {
                fetchCompletedFallback(row);
            }
        }

        showLoading(false);
        tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void fetchCompletedFallback(TeacherAdapter.TeacherRow row) {
        db.collection("bookings")
                .whereEqualTo("teacherId", row.id)
                .whereEqualTo("status", "completed")
                .get()
                .addOnSuccessListener(snap -> {
                    long cnt = snap.getDocuments().size();
                    // UI'ı güncelle
                    adapter.updateCompletedById(row.id, cnt);

                    // İstersen cache için profiline de yaz (merge)
                    HashMap<String, Object> up = new HashMap<>();
                    up.put("completedCount", cnt);
                    up.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                    db.collection("teacherProfiles").document(row.id).set(up, SetOptions.merge());
                });
    }


    private void loadPriceForRow(String teacherId, String subjectId){
        if (teacherId == null || subjectId == null) {
            Log.w(TAG, "loadPriceForRow: teacherId/subjectId null");
            return;
        }
        final String tsId = teacherId + "_" + subjectId;
        Log.d(TAG, "price: try A teacherSubjects/" + tsId);

        db.collection("teacherSubjects").document(tsId).get()
                .addOnSuccessListener(d -> {
                    Integer price = toIntOrNull(d.get("price"));
                    if (price != null && price > 0) {
                        adapter.updatePriceById(teacherId, price);
                    } else {
                        db.collection("teacherSubjects").document(teacherId)
                                .collection("subjects").document(subjectId).get()
                                .addOnSuccessListener(sd -> {
                                    Integer p2 = toIntOrNull(sd.get("price"));
                                    if (p2 != null && p2 > 0) {
                                        adapter.updatePriceById(teacherId, p2);
                                    } else {
                                        db.collection("subjects").document(subjectId).get()
                                                .addOnSuccessListener(ss -> {
                                                    Integer p3 = toIntOrNull(ss.get("price"));
                                                    if (p3 != null && p3 > 0) {
                                                        adapter.updatePriceById(teacherId, p3);
                                                    }
                                                });
                                    }
                                });
                    }
                });
    }

    private void loadTeachers() {
        showLoading(true);

        String mapField = "subjectsMap." + subjectId;

        db.collection("teacherProfiles")
                .whereGreaterThan(mapField, 0)
                .get()
                .addOnSuccessListener(this::processTeachers)
                .addOnFailureListener(e -> showError("Öğretmenler yüklenemedi: " + e.getMessage()));
    }

    private void fetchRatingFallback(TeacherAdapter.TeacherRow row, int position) {
        db.collection("teacherReviews")
                .whereEqualTo("teacherId", row.id)
                .get()
                .addOnSuccessListener(snap -> {
                    long cnt = 0;
                    double sum = 0;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Number n = (Number) d.get("rating");
                        if (n != null) { sum += n.doubleValue(); cnt++; }
                    }
                    row.ratingCount = cnt;
                    row.ratingAvg   = cnt > 0 ? (sum / cnt) : 0.0;
                    adapter.notifyItemChanged(position);

                    HashMap<String, Object> up = new HashMap<>();
                    up.put("ratingAvg", row.ratingAvg);
                    up.put("ratingCount", row.ratingCount);
                    up.put("updatedAt", FieldValue.serverTimestamp());
                    db.collection("teacherProfiles").document(row.id).set(up, SetOptions.merge());
                });
    }

    // ------- Arama / Filtre --------
    private void applyFilter() {
        String q = (etSearch != null && etSearch.getText() != null) ? etSearch.getText().toString() : "";
        String needle = q == null ? "" : q.trim().toLowerCase(new Locale("tr", "TR"));

        if (needle.isEmpty()) {
            adapter.submit(new ArrayList<>(all));
            tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
            rv.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }

        List<TeacherAdapter.TeacherRow> filtered = new ArrayList<>();
        for (TeacherAdapter.TeacherRow r : all) {
            if (r.fullName != null && r.fullName.toLowerCase(new Locale("tr","TR")).contains(needle)) {
                filtered.add(r);
            }
        }
        adapter.submit(filtered);
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
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
