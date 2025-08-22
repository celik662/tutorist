// app/src/main/java/com/example/tutorist/ui/teacher/TeacherDetailActivity.java
package com.example.tutorist.ui.teacher;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.text.SimpleDateFormat;
import java.util.*;

public class TeacherDetailActivity extends AppCompatActivity {

    private String teacherId, subjectId, subjectName;
    private TextView tvName, tvSubject, tvPrice, tvDate;
    private Button btnPickDate;
    private RecyclerView rvSlots;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final Calendar selected = Calendar.getInstance(); // bugün
    private final SlotsAdapter adapter = new SlotsAdapter(new ArrayList<>(), hour ->
            Toast.makeText(this, "Seçildi: " + String.format(Locale.getDefault(), "%02d:00", hour), Toast.LENGTH_SHORT).show()
    );


    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_detail);

        teacherId   = getIntent().getStringExtra("teacherId");
        subjectId   = getIntent().getStringExtra("subjectId");
        subjectName = getIntent().getStringExtra("subjectName");

        tvName = findViewById(R.id.tvName);
        tvSubject = findViewById(R.id.tvSubject);
        tvPrice = findViewById(R.id.tvPrice);
        tvDate = findViewById(R.id.tvDate);
        btnPickDate = findViewById(R.id.btnPickDate);
        rvSlots = findViewById(R.id.rvSlots);
        rvSlots.setLayoutManager(new GridLayoutManager(this, 4));
        rvSlots.setAdapter(adapter);

        tvSubject.setText(subjectName);

        btnPickDate.setOnClickListener(v -> openDatePicker());

        loadHeader();
        updateDateLabel();
        loadSlotsForSelectedDate();


    }

    private void loadHeader() {
        // Ad Soyad
        db.collection("users").document(teacherId).get().addOnSuccessListener(doc -> {
            Object name = doc.get("fullName");
            if (name == null) name = doc.get("email");
            tvName.setText(name != null ? String.valueOf(name) : teacherId);
        });

        // Fiyat (teacherProfiles.subjectsMap[subjectId])
        db.collection("teacherProfiles").document(teacherId).get().addOnSuccessListener(doc -> {
            Map<String, Object> sm = (Map<String, Object>) doc.get("subjectsMap");
            if (sm != null) {
                Object p = sm.get(subjectId);
                if (p instanceof Number) tvPrice.setText(String.format(Locale.getDefault(), "₺%.0f", ((Number)p).doubleValue()));
            }
        });
    }
    private String dayIdFromCal(Calendar c) {
        switch (c.get(Calendar.DAY_OF_WEEK)) {
            case Calendar.MONDAY:    return "mon";
            case Calendar.TUESDAY:   return "tue";
            case Calendar.WEDNESDAY: return "wed";
            case Calendar.THURSDAY:  return "thu";
            case Calendar.FRIDAY:    return "fri";
            case Calendar.SATURDAY:  return "sat";
            case Calendar.SUNDAY:    return "sun";
        }
        return "mon";
    }


    private void collectHoursFromDoc(DocumentSnapshot d, List<Integer> hours) {
        // enabled=false ise atla (weekly/{mon} şemasında olabilir)
        Boolean en = d.getBoolean("enabled");
        if (en != null && !en) return;

        Integer sh = getInt(d, "startHour", "start");
        Integer eh = getInt(d, "endHour",  "end");
        if (sh == null || eh == null || eh <= sh) return;

        for (int h = sh; h < eh; h++) hours.add(h);
    }


    private void openDatePicker() {
        Calendar now = Calendar.getInstance();
        DatePickerDialog dlg = new DatePickerDialog(
                this,
                (view, y, m, d) -> {
                    selected.set(Calendar.YEAR, y);
                    selected.set(Calendar.MONTH, m);
                    selected.set(Calendar.DAY_OF_MONTH, d);
                    updateDateLabel();
                    loadSlotsForSelectedDate();
                },
                selected.get(Calendar.YEAR),
                selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)
        );
        // geçmiş tarihleri kapat (bugün dahil)
        dlg.getDatePicker().setMinDate(now.getTimeInMillis());
        dlg.show();
    }

    private void updateDateLabel() {
        SimpleDateFormat sdf = new SimpleDateFormat("d MMM yyyy, EEEE", new Locale("tr", "TR"));
        tvDate.setText(sdf.format(selected.getTime()));
    }

    private int toMon1_7(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK); // 1=Sun..7=Sat
        return (dow == Calendar.SUNDAY) ? 7 : (dow - 1); // 1=Mon..7=Sun
    }

    private String selectedDateIso() {
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return iso.format(selected.getTime());
    }

    private void loadSlotsForSelectedDate() {
        int dayMon1_7 = toMon1_7(selected);               // 1=Mon..7=Sun
        int daySun1_7 = selected.get(Calendar.DAY_OF_WEEK); // 1=Sun..7=Sat
        String dayId  = dayIdFromCal(selected);

        android.util.Log.d("TeacherDetail", "loadSlots: teacherId=" + teacherId +
                " date=" + selectedDateIso() +
                " dayMon1_7=" + dayMon1_7 +
                " daySun1_7=" + daySun1_7 +
                " dayId=" + dayId);

        // 1) weekly altında dayOfWeek (Mon=1..Sun=7) ile arama
        db.collection("availabilities").document(teacherId)
                .collection("weekly")
                .whereEqualTo("dayOfWeek", dayMon1_7)
                .get()
                .addOnSuccessListener(snap1 -> {
                    android.util.Log.d("TeacherDetail", "q1(dayMon1_7) size=" + snap1.size());
                    if (!snap1.isEmpty()) {
                        buildApplySlotsFromDocs(snap1.getDocuments());
                        return;
                    }
                    // 2) Fallback: dayOfWeek (Sun=1..Sat=7) ile arama
                    db.collection("availabilities").document(teacherId)
                            .collection("weekly")
                            .whereEqualTo("dayOfWeek", daySun1_7)
                            .get()
                            .addOnSuccessListener(snap2 -> {
                                android.util.Log.d("TeacherDetail", "q2(daySun1_7) size=" + snap2.size());
                                if (!snap2.isEmpty()) {
                                    buildApplySlotsFromDocs(snap2.getDocuments());
                                    return;
                                }
                                // 3) Fallback: weekly/{mon..sun} tek doküman şeması
                                db.collection("availabilities").document(teacherId)
                                        .collection("weekly").document(dayId).get()
                                        .addOnSuccessListener(doc -> {
                                            android.util.Log.d("TeacherDetail", "q3(doc " + dayId + ") exists=" + doc.exists());
                                            java.util.List<com.google.firebase.firestore.DocumentSnapshot> list =
                                                    doc.exists() ? java.util.Collections.singletonList(doc)
                                                            : java.util.Collections.emptyList();
                                            buildApplySlotsFromDocs(list);
                                        })
                                        .addOnFailureListener(e -> {
                                            android.util.Log.e("TeacherDetail", "q3 failed", e);
                                            buildApplySlotsFromDocs(java.util.Collections.emptyList());
                                        });
                            })
                            .addOnFailureListener(e -> {
                                android.util.Log.e("TeacherDetail", "q2 failed", e);
                                buildApplySlotsFromDocs(java.util.Collections.emptyList());
                            });
                })
                .addOnFailureListener(e -> {
                    android.util.Log.e("TeacherDetail", "q1 failed", e);
                    buildApplySlotsFromDocs(java.util.Collections.emptyList());
                });
    }

    private Integer getInt(com.google.firebase.firestore.DocumentSnapshot d, String a, String b) {
        Long v = d.getLong(a);
        if (v == null && b != null) v = d.getLong(b);
        return v != null ? v.intValue() : null;
    }



    private void buildApplySlotsFromDocs(java.util.List<com.google.firebase.firestore.DocumentSnapshot> docs) {
        java.util.List<Integer> hours = new java.util.ArrayList<>();

        for (com.google.firebase.firestore.DocumentSnapshot d : docs) {
            // weekly/{mon} şemasında enabled=false olabilir
            Boolean en = d.getBoolean("enabled");
            if (en != null && !en) continue;

            Integer sh = getInt(d, "startHour", "start");
            Integer eh = getInt(d, "endHour",  "end");
            android.util.Log.d("TeacherDetail", "doc " + d.getId() + " -> start=" + sh + " end=" + eh + " enabled=" + en);

            if (sh == null || eh == null || eh <= sh) continue;
            for (int h = sh; h < eh; h++) hours.add(h);
        }

        // bugünün geçmiş saatlerini disable
        java.util.Set<Integer> disabled = new java.util.HashSet<>();
        java.util.Calendar now = java.util.Calendar.getInstance();
        if (isSameDay(now, selected)) {
            int curHour = now.get(java.util.Calendar.HOUR_OF_DAY);
            for (int h : hours) if (h <= curHour) disabled.add(h);
        }

        // rezervasyonlu saatleri düş (index yoksa yine uygula)
        db.collection("bookings")
                .whereEqualTo("teacherId", teacherId)
                .whereEqualTo("date", selectedDateIso())
                .whereIn("status", java.util.Arrays.asList("pending","accepted"))
                .get()
                .addOnSuccessListener(bSnap -> {
                    for (com.google.firebase.firestore.DocumentSnapshot b : bSnap) {
                        Integer hour = b.getLong("hour") != null ? b.getLong("hour").intValue() : null;
                        if (hour != null) disabled.add(hour);
                    }
                    applySlots(hours, disabled);
                })
                .addOnFailureListener(e -> {
                    android.util.Log.w("TeacherDetail", "bookings query failed (probably no index) -> showing raw slots", e);
                    applySlots(hours, disabled);
                });
    }



    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR)==b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR);
    }

    private void applySlots(java.util.List<Integer> hours, java.util.Set<Integer> disabled) {
        java.util.List<SlotsAdapter.Row> rows = new java.util.ArrayList<>();
        for (int h : hours) rows.add(new SlotsAdapter.Row(h, disabled.contains(h)));
        adapter.replace(rows);

        // EKRANDA BOŞ DURUM MESAJI GÖSTER
        TextView tvEmpty = findViewById(R.id.tvEmpty); // layout’ta ekleyin
        if (tvEmpty != null) {
            tvEmpty.setVisibility(rows.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            if (rows.isEmpty()) tvEmpty.setText("Bu gün için öğretmenin müsait saati yok.");
        }
    }

    // --- Slots Adapter ---
    static class SlotsAdapter extends RecyclerView.Adapter<SlotsAdapter.VH> {
        interface OnSlotClick { void onClick(int hour); }
        static class Row { int hour; boolean disabled; Row(int h, boolean d){ hour=h; disabled=d; } }

        private final List<Row> items;
        private final OnSlotClick onSlotClick;

        SlotsAdapter(List<Row> items, OnSlotClick onSlotClick){ this.items=items; this.onSlotClick=onSlotClick; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(android.view.View v){ super(v); tv=v.findViewById(R.id.tvHour); }
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int vt) {
            android.view.View v = android.view.LayoutInflater.from(p.getContext()).inflate(R.layout.item_hour_slot, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            Row r = items.get(pos);
            h.tv.setText(String.format(Locale.getDefault(), "%02d:00", r.hour));
            h.itemView.setEnabled(!r.disabled);
            h.tv.setAlpha(r.disabled ? 0.4f : 1f);
            h.itemView.setOnClickListener(v -> { if (!r.disabled) onSlotClick.onClick(r.hour); });
        }

        @Override public int getItemCount(){ return items.size(); }
        void replace(List<Row> rows){ items.clear(); items.addAll(rows); notifyDataSetChanged(); }
    }

}
