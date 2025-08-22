// app/src/main/java/com/example/tutorist/ui/teacher/TeacherListActivity.java
package com.example.tutorist.ui.teacher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TeacherListActivity extends AppCompatActivity {

    private String subjectId, subjectName;
    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvTitle, tvEmpty, tvError;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private Adapter adapter;

    static class Row {
        String teacherId;
        String teacherName;
        double price;
        Row(String id, String name, double price){ this.teacherId=id; this.teacherName=name; this.price=price; }
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_list);

        subjectId   = getIntent().getStringExtra("subjectId");
        subjectName = getIntent().getStringExtra("subjectName");

        tvTitle = findViewById(R.id.tvTitle);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvError = findViewById(R.id.tvError);
        rv      = findViewById(R.id.rvTeachers);
        progress= findViewById(R.id.progress);

        tvTitle.setText(subjectName != null ? subjectName : "Öğretmenler");
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(new ArrayList<>(), row -> {
            Intent i = new Intent(this, TeacherDetailActivity.class);
            i.putExtra("teacherId", row.teacherId);
            i.putExtra("subjectId", subjectId);
            i.putExtra("subjectName", subjectName);
            startActivity(i);
        });
        rv.setAdapter(adapter);

        loadTeachers();
    }

    private void loadTeachers() {
        showLoading(true);
        String nestedField = "subjectsMap." + subjectId;

        // 1) Doğru şema: subjectsMap altında english alt alanı
        db.collection("teacherProfiles")
                .whereGreaterThan(nestedField, 0)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        // 2) Fallback: alan adı "subjectsMap.english" (tek segment) olarak düz yazılmış
                        db.collection("teacherProfiles")
                                .whereGreaterThan(FieldPath.of(nestedField), 0) // DÜZ alan için
                                .get()
                                .addOnSuccessListener(this::processTeachers)
                                .addOnFailureListener(e -> showError("Öğretmenler yüklenemedi: " + e.getMessage()));
                        return;
                    }
                    processTeachers(snap);
                })
                .addOnFailureListener(e -> showError("Öğretmenler yüklenemedi: " + e.getMessage()));
    }

    private void processTeachers(QuerySnapshot snap) {
        List<Row> rows = new ArrayList<>();
        for (DocumentSnapshot d : snap) {
            String nestedField = "subjectsMap." + subjectId;
            Object v = d.get(nestedField);                          // doğru şema
            if (!(v instanceof Number)) v = d.get(FieldPath.of(nestedField)); // düz alan fallback
            if (!(v instanceof Number)) continue;

            double price = ((Number) v).doubleValue();
            String name = d.getString("displayName");
            if (name == null || name.trim().isEmpty()) name = "Öğretmen";
            rows.add(new Row(d.getId(), name, price));
        }
        Collections.sort(rows, Comparator.comparingDouble(r -> r.price));
        adapter.replace(rows);
        showLoading(false);
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
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

    // --- Adapter ---
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface OnRowClick { void onClick(Row r); }
        private final List<Row> items;
        private final OnRowClick onRowClick;
        Adapter(List<Row> items, OnRowClick onRowClick){ this.items=items; this.onRowClick=onRowClick; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPrice, tvRating;
            VH(View v){ super(v);
                tvName=v.findViewById(R.id.tvName);
                tvPrice=v.findViewById(R.id.tvPrice);
                tvRating=v.findViewById(R.id.tvRating);
            }
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int vt) {
            View v = android.view.LayoutInflater.from(p.getContext()).inflate(R.layout.item_teacher_row, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            Row r = items.get(pos);
            h.tvName.setText(r.teacherName);
            h.tvPrice.setText(String.format("₺%.0f", r.price));
            h.tvRating.setText("★ 5.0"); // placeholder
            h.itemView.setOnClickListener(v -> onRowClick.onClick(r));
        }
        @Override public int getItemCount(){ return items.size(); }
        public void replace(List<Row> rows){ items.clear(); items.addAll(rows); notifyDataSetChanged(); }
    }
}
