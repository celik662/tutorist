package com.example.tutorist.ui.teacher;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.model.Subject;
import com.example.tutorist.repo.SubjectsRepo;
import com.example.tutorist.repo.TeacherProfileRepo;
import com.example.tutorist.repo.AvailabilityRepo;
import com.example.tutorist.util.ValidationUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.*;

public class TeacherSubjectsActivity extends AppCompatActivity {

    private MaterialAutoCompleteTextView spSubjects;
    private EditText etPrice;
    private RecyclerView rv;
    private TextView tvMsg;
    private ListenerRegistration mapReg;

    private final SubjectsRepo subjectsRepo = new SubjectsRepo();
    private final TeacherProfileRepo profileRepo = new TeacherProfileRepo();
    private final AvailabilityRepo availabilityRepo = new AvailabilityRepo();
    private String uid;

    private List<Subject> allSubjects = new ArrayList<>();
    private final Map<String, Double> priceMap = new HashMap<>();
    private final Map<String, Subject> subjectByName = new HashMap<>(); // nameTR → Subject

    private final List<Row> current = new ArrayList<>();
    private Adapter adapter;

    static class Row {
        String subjectId;
        String subjectName;
        double price;
        Row(String id, String name, double price) { this.subjectId = id; this.subjectName = name; this.price = price; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_subjects);

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; }

        spSubjects = findViewById(R.id.spSubjects);
        etPrice    = findViewById(R.id.etPrice);
        tvMsg      = findViewById(R.id.tvMsg);
        rv         = findViewById(R.id.rvList);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(current, this::confirmDelete);
        rv.setAdapter(adapter);

        findViewById(R.id.btnSave).setOnClickListener(v -> onSave());

        // Paralel başlat
        loadSubjects();
        subscribeMyMap();
    }

    @Override protected void onDestroy() {
        if (mapReg != null) { mapReg.remove(); mapReg = null; }
        super.onDestroy();
    }

    /** Öğretmenin mevcut ders/fiyat haritasını canlı dinle */
    private void subscribeMyMap() {
        if (mapReg != null) { mapReg.remove(); }

        mapReg = FirebaseFirestore.getInstance()
                .collection("teacherProfiles").document(uid)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        tvMsg.setText("Profil okunamadı: " + e.getMessage());
                        return;
                    }

                    Map<String, Double> map = new HashMap<>();
                    if (snap != null && snap.exists()) {
                        // 1) subjectsMap: {english: 120, math: 100}
                        Object raw = snap.get("subjectsMap");
                        if (raw instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) raw;
                            for (Map.Entry<?, ?> en : m.entrySet()) {
                                Object k = en.getKey(), v = en.getValue();
                                if (k != null && v instanceof Number) {
                                    map.put(String.valueOf(k), ((Number) v).doubleValue());
                                }
                            }
                        }
                        // 2) Düz alan biçimi: subjectsMap.english = 120
                        Map<String, Object> data = snap.getData();
                        if (data != null) {
                            for (Map.Entry<String, Object> en : data.entrySet()) {
                                String k = en.getKey();
                                Object v = en.getValue();
                                if (k.startsWith("subjectsMap.") && v instanceof Number) {
                                    String sid = k.substring("subjectsMap.".length());
                                    map.put(sid, ((Number) v).doubleValue());
                                }
                            }
                        }
                    }

                    priceMap.clear();
                    priceMap.putAll(map);
                    rebuildList();
                });
    }

    /** Aktif dersleri çek & açılır listeyi doldur */
    private void loadSubjects() {
        subjectsRepo.loadActiveSubjects()
                .addOnSuccessListener(list -> {
                    allSubjects = list != null ? list : Collections.emptyList();

                    subjectByName.clear();
                    List<String> names = new ArrayList<>();
                    for (Subject s : allSubjects) {
                        String display = (s != null && s.nameTR != null && !s.nameTR.isEmpty()) ? s.nameTR : (s != null ? s.id : "");
                        if (display == null) display = "";
                        names.add(display);
                        if (s != null) subjectByName.put(display, s);
                    }

                    // MaterialAutoCompleteTextView için en basit kurulum
                    spSubjects.setSimpleItems(names.toArray(new String[0]));
                    rebuildList();
                })
                .addOnFailureListener(e -> {
                    tvMsg.setText("Ders listesi yüklenemedi: " + e.getMessage());
                    allSubjects = Collections.emptyList();
                    spSubjects.setSimpleItems(new String[0]);
                    rebuildList();
                });
    }

    /** Listeyi (ders adı-alfabetik) yeniden kur */
    private void rebuildList() {
        current.clear();
        for (Map.Entry<String, Double> e : priceMap.entrySet()) {
            String sid = e.getKey();
            Subject s = findSubjectById(sid);
            String name = (s != null && s.nameTR != null && !s.nameTR.isEmpty()) ? s.nameTR : sid;
            current.add(new Row(sid, name, e.getValue()));
        }
        current.sort(Comparator.comparing(r -> r.subjectName.toLowerCase(Locale.getDefault())));
        adapter.notifyDataSetChanged();
        tvMsg.setText(current.isEmpty() ? "Ders eklemediniz." : "");
    }

    private Subject findSubjectById(String id) {
        for (Subject s : allSubjects) {
            if (s != null && id != null && id.equals(s.id)) return s;
        }
        return null;
    }

    /** Kaydet tıklandı */
    private void onSave() {
        String selName = spSubjects.getText() != null ? spSubjects.getText().toString().trim() : "";
        Subject s = subjectByName.get(selName);
        if (s == null || s.id == null || s.id.isEmpty()) {
            tvMsg.setText("Lütfen listeden bir ders seçin.");
            return;
        }

        String txt = etPrice.getText().toString().trim().replace(",", ".");
        double price;
        try {
            price = Double.parseDouble(txt);
        } catch (Exception ex) {
            tvMsg.setText("Geçerli bir fiyat girin.");
            return;
        }
        if (!ValidationUtil.isValidPrice(price)) {
            tvMsg.setText("Fiyat 0'dan büyük olmalı.");
            return;
        }

        profileRepo.upsertSubjectPrice(uid, s.id, price)
                .addOnSuccessListener(v -> {
                    priceMap.put(s.id, price);
                    etPrice.setText("");
                    tvMsg.setText("Kaydedildi.");
                    rebuildList();

                    // Ders eklendi → Müsaitlik var mı kontrol et, yoksa yönlendir
                    availabilityRepo.countAllBlocksFor(uid)
                            .addOnSuccessListener(count -> {
                                if (count == 0) showAvailabilityPrompt();
                            });
                })
                .addOnFailureListener(e -> tvMsg.setText("Kaydetme hatası: " + e.getMessage()));
    }

    private void showAvailabilityPrompt() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Müsaitlik Saatlerin")
                .setMessage("Öğrenciler seni görebilsin diye müsaitlik saatlerini eklemelisin.")
                .setNegativeButton("Sonra", null)
                .setPositiveButton("Saat Ekle", (d, w) -> {
                    startActivity(new android.content.Intent(
                            this, com.example.tutorist.ui.teacher.TeacherAvailabilityActivity.class));
                })
                .show();
    }

    private void confirmDelete(String subjectId) {
        new AlertDialog.Builder(this)
                .setTitle("Dersi sil?")
                .setMessage("Bu dersi listenden kaldırmak istiyor musun?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d, w) -> doDelete(subjectId))
                .show();
    }

    private void doDelete(String subjectId) {
        tvMsg.setText("Siliniyor...");
        profileRepo.removeSubject(uid, subjectId)
                .addOnSuccessListener(v -> {
                    priceMap.remove(subjectId);
                    tvMsg.setText("Silindi.");
                    rebuildList();
                })
                .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
    }

    // --- Adapter ---
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface OnDelete { void onDelete(String subjectId); }
        private final List<Row> items; private final OnDelete onDelete;
        Adapter(List<Row> items, OnDelete onDelete){ this.items = items; this.onDelete = onDelete; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPrice; Button btnDel;
            VH(android.view.View v){
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvPrice = v.findViewById(R.id.tvPrice);
                btnDel  = v.findViewById(R.id.btnDelete);
            }
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int vtype) {
            android.view.View v = android.view.LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_teacher_subject, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            Row r = items.get(pos);
            h.tvName.setText(r.subjectName);
            h.tvPrice.setText(String.format(Locale.getDefault(), "₺%.0f", r.price));
            h.btnDel.setOnClickListener(v -> onDelete.onDelete(r.subjectId));
        }

        @Override public int getItemCount(){ return items.size(); }
    }
}
