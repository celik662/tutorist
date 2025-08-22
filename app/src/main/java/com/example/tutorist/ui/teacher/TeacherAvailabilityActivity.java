package com.example.tutorist.ui.teacher;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.model.AvailabilityBlock;
import com.example.tutorist.repo.AvailabilityRepo;
import com.example.tutorist.util.ValidationUtil;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TeacherAvailabilityActivity extends AppCompatActivity {

    private Spinner spDay, spStart, spEnd;
    private Button btnAdd;
    private TextView tvMsg;
    private RecyclerView rv;

    private final AvailabilityRepo repo = new AvailabilityRepo();
    private String uid; // onCreate'te set edilecek
    private final List<AvailabilityBlock> items = new ArrayList<>();
    private Adapter adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_availability);

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; } // güvenlik

        spDay   = findViewById(R.id.spDay);
        spStart = findViewById(R.id.spStart);
        spEnd   = findViewById(R.id.spEnd);
        btnAdd  = findViewById(R.id.btnAdd);
        tvMsg   = findViewById(R.id.tvMsg);
        rv      = findViewById(R.id.rvBlocks);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(items, this::onDelete);
        rv.setAdapter(adapter);

        initSpinners();
        btnAdd.setOnClickListener(v -> onAdd());

        loadDay(); // varsayılan Pzt için yükle
        //debugSeedToday();
    }

    private void debugSeedToday() {
        String seedUid = FirebaseAuth.getInstance().getUid();
        if (seedUid == null) return;

        java.util.Calendar c = java.util.Calendar.getInstance();
        int dayMon1_7 = (c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY)
                ? 7 : (c.get(java.util.Calendar.DAY_OF_WEEK) - 1);

        new com.example.tutorist.repo.AvailabilityRepo()
                .addBlock(seedUid, dayMon1_7, 9, 18)
                .addOnSuccessListener(id -> android.util.Log.d("SEED", "Seed OK id=" + id))
                .addOnFailureListener(e -> android.util.Log.e("SEED", "Seed FAIL", e));
    }
    private void initSpinners() {
        // Günler (Mon=1..Sun=7 ile uyumlu)
        String[] days = {"Pzt","Sal","Çar","Per","Cum","Cts","Paz"};
        ArrayAdapter<String> adDays =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, days);
        adDays.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDay.setAdapter(adDays);
        spDay.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadDay();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Saatler 0..24
        List<String> hours = new ArrayList<>();
        for (int h = 0; h <= 24; h++) hours.add(String.format(Locale.getDefault(), "%02d:00", h));
        ArrayAdapter<String> adH =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, hours);
        adH.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spStart.setAdapter(adH);
        spEnd.setAdapter(adH);

        // Varsayılan 09:00–18:00
        spStart.setSelection(9);
        spEnd.setSelection(18);
    }

    private int selectedDayOfWeek() {
        return spDay.getSelectedItemPosition() + 1; // 1..7 (Pzt=1, ... Paz=7)
    }

    private void loadDay() {
        int day = selectedDayOfWeek();
        repo.loadDayBlocks(uid, day)
                .addOnSuccessListener(list -> {
                    items.clear();
                    items.addAll(list);
                    // ekrana hoş görünmesi için saat başına göre sırala
                    Collections.sort(items, Comparator.comparingInt(b -> b.startHour));
                    adapter.notifyDataSetChanged();
                    tvMsg.setText(items.isEmpty() ? "Bu gün için kayıtlı müsaitlik yok." : "");
                })
                .addOnFailureListener(e -> tvMsg.setText("Yükleme hatası: " + e.getMessage()));
    }

    private void onAdd() {
        int day   = selectedDayOfWeek();
        int start = spStart.getSelectedItemPosition(); // 0..24
        int end   = spEnd.getSelectedItemPosition();   // 0..24

        if (!ValidationUtil.isValidBlock(start, end)) {
            tvMsg.setText("Geçersiz aralık. (Başlangıç < Bitiş, 0–24)");
            return;
        }
        if (ValidationUtil.hasOverlap(items, start, end)) {
            tvMsg.setText("Mevcut aralıklarla çakışıyor.");
            return;
        }

        repo.addBlock(uid, day, start, end)
                .addOnSuccessListener(id -> {
                    tvMsg.setText("Eklendi.");
                    spStart.setSelection(9);
                    spEnd.setSelection(18);
                    loadDay();
                })
                .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
    }

    private void onDelete(String docId) {
        repo.deleteBlock(uid, docId)
                .addOnSuccessListener(v -> { tvMsg.setText("Silindi."); loadDay(); })
                .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
    }

    // --- Adapter ---
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface OnDelete { void onDelete(String docId); }
        private final List<AvailabilityBlock> items;
        private final OnDelete onDelete;

        Adapter(List<AvailabilityBlock> items, OnDelete onDelete){
            this.items = items; this.onDelete = onDelete;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv; Button btn;
            VH(View v){
                super(v);
                tv  = v.findViewById(R.id.tvBlock);
                btn = v.findViewById(R.id.btnDelete);
            }
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int vt) {
            View v = android.view.LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_availability_block, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            AvailabilityBlock b = items.get(pos);
            h.tv.setText(String.format(Locale.getDefault(), "%02d:00–%02d:00", b.startHour, b.endHour));
            h.btn.setOnClickListener(v -> onDelete.onDelete(b.id));
        }

        @Override public int getItemCount(){ return items.size(); }
    }
}
