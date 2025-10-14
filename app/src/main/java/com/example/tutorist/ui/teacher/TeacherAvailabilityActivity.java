package com.example.tutorist.ui.teacher;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.model.AvailabilityBlock;
import com.example.tutorist.repo.AvailabilityRepo;
import com.example.tutorist.util.ValidationUtil;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TeacherAvailabilityActivity extends AppCompatActivity {

    // ---- UI ----
    private MaterialAutoCompleteTextView spDay, spStart, spEnd;
    private Button btnAdd;
    private TextView tvMsg;
    private RecyclerView rv;

    // ---- Data / Repo ----
    private final AvailabilityRepo repo = new AvailabilityRepo();
    private String uid;
    private final List<AvailabilityBlock> items = new ArrayList<>();
    private Adapter adapter;

    // Görünen etiketler
    private static final String[] DAY_LABELS =
            {"Pazartesi","Salı","Çarşamba","Perşembe","Cuma","Cumartesi","Pazar"};
    private static final String[] HOUR_LABELS = buildHourLabels(); // "00:00" .. "24:00"

    private static String[] buildHourLabels() {
        String[] arr = new String[25];
        for (int h = 0; h <= 24; h++) arr[h] = String.format(Locale.getDefault(), "%02d:00", h);
        return arr;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_availability);

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; }

        // ❌ BURADA YENİDEN TANIMLAMA YAPMA
        // MaterialAutoCompleteTextView spDay   = findViewById(R.id.spDay);

        // ✅ SINIF ALANLARINI ATA
        spDay   = findViewById(R.id.spDay);
        spStart = findViewById(R.id.spStart);
        spEnd   = findViewById(R.id.spEnd);
        btnAdd  = findViewById(R.id.btnAdd);
        tvMsg   = findViewById(R.id.tvMsg);
        rv      = findViewById(R.id.rvBlocks);

        makeSelectionOnly(spDay);
        makeSelectionOnly(spStart);
        makeSelectionOnly(spEnd);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(items, this::onDelete);
        rv.setAdapter(adapter);

        initDropdowns();
        btnAdd.setOnClickListener(v -> onAdd());

        // Varsayılanlar
        spDay.setText(DAY_LABELS[0], /*dismissDropDown=*/false);
        spStart.setText("09:00", false);
        spEnd.setText("18:00", false);

        loadDay();
    }


    private void makeSelectionOnly(com.google.android.material.textfield.MaterialAutoCompleteTextView v) {
        // Yazı girişi / yapıştırmayı kapat
        v.setKeyListener(null);
        v.setLongClickable(false);
        v.setTextIsSelectable(false);

        // Sadece tıklayınca açılır menüyü göster
        v.setOnClickListener(view -> v.showDropDown());
        v.setOnFocusChangeListener((view, hasFocus) -> { if (hasFocus) v.showDropDown(); });

        // Serbest yazı gelirse (ör. programatik) listede yoksa temizle (opsiyonel güvenlik)
        v.setOnDismissListener(() -> {
            android.widget.ListAdapter ad = v.getAdapter();
            CharSequence cur = v.getText();
            boolean match = false;
            if (ad != null && cur != null) {
                for (int i = 0; i < ad.getCount(); i++) {
                    Object it = ad.getItem(i);
                    if (it != null && cur.toString().contentEquals(String.valueOf(it))) {
                        match = true; break;
                    }
                }
            }
            if (!match) v.setText("");  // listede olmayan serbest değerleri reddet
        });
    }


    // ---- Dropdown’ları hazırla ----
    private void initDropdowns() {
        spDay.setSimpleItems(DAY_LABELS);
        spStart.setSimpleItems(HOUR_LABELS);
        spEnd.setSimpleItems(HOUR_LABELS);

        spDay.setOnItemClickListener((parent, view, position, id) -> {
            tvMsg.setText("");
            loadDay();
        });
    }

    // Pazartesi=1 ... Pazar=7 (uygulamadaki konvansiyon)
    private int selectedDayOfWeek() {
        String label = String.valueOf(spDay.getText());
        for (int i = 0; i < DAY_LABELS.length; i++) {
            if (DAY_LABELS[i].equals(label)) return i + 1;
        }
        return 1; // fallback
    }

    private static int parseHour(String label) {
        // "HH:00" -> HH
        if (label == null || label.length() < 2) return 0;
        try { return Integer.parseInt(label.substring(0, 2)); }
        catch (Exception ignore) { return 0; }
    }

    private int selectedStartHour() { return parseHour(String.valueOf(spStart.getText())); }
    private int selectedEndHour()   { return parseHour(String.valueOf(spEnd.getText())); }

    // ---- Data yükleme ----
    private void loadDay() {
        int day = selectedDayOfWeek();
        repo.loadDayBlocks(uid, day)
                .addOnSuccessListener(list -> {
                    items.clear();
                    items.addAll(list);
                    Collections.sort(items, Comparator.comparingInt(b -> b.startHour));
                    adapter.notifyDataSetChanged();
                    tvMsg.setText(items.isEmpty() ? "Bu gün için kayıtlı müsaitlik yok." : "");
                })
                .addOnFailureListener(e -> tvMsg.setText("Yükleme hatası: " + e.getMessage()));
    }

    // ---- Aralık ekle ----
    private void onAdd() {
        int day   = selectedDayOfWeek();
        int start = selectedStartHour(); // 0..24
        int end   = selectedEndHour();   // 0..24

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
                    spStart.setText("09:00", false);
                    spEnd.setText("18:00", false);
                    loadDay();
                })
                .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
    }

    // ---- Silme ----
    private void onDelete(String docId) {
        repo.deleteBlock(uid, docId)
                .addOnSuccessListener(v -> { tvMsg.setText("Silindi."); loadDay(); })
                .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
    }

    // ---- Adapter ----
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
            h.tv.setText(String.format(Locale.getDefault(),
                    "%02d:00–%02d:00", b.startHour, b.endHour));
            h.btn.setOnClickListener(v -> onDelete.onDelete(b.id));
        }

        @Override public int getItemCount(){ return items.size(); }
    }
}
