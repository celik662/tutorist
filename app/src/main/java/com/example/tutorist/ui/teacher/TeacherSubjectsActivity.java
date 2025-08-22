// app/src/main/java/com/example/tutorist/ui/teacher/TeacherSubjectsActivity.java
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
import com.example.tutorist.util.ValidationUtil;
import com.google.firebase.auth.FirebaseAuth;
import java.util.*;

public class TeacherSubjectsActivity extends AppCompatActivity {

    private Spinner spSubjects;
    private EditText etPrice;
    private RecyclerView rv;
    private TextView tvMsg;

    private final SubjectsRepo subjectsRepo = new SubjectsRepo();
    private final TeacherProfileRepo profileRepo = new TeacherProfileRepo();
    private String uid;

    private List<Subject> allSubjects = new ArrayList<>();
    private final Map<String,Double> priceMap = new HashMap<>();

    private final List<Row> current = new ArrayList<>();
    private Adapter adapter;

    static class Row {
        String subjectId; String subjectName; double price;
        Row(String id, String name, double price){ this.subjectId=id; this.subjectName=name; this.price=price; }
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

        // Paralel başlat: biri önce biterse de listeyi kurar
        loadSubjects();
        loadMyMap();
    }

    private void loadSubjects() {
        subjectsRepo.loadActiveSubjects()
                .addOnSuccessListener(list -> {
                    allSubjects = list;
                    ArrayAdapter<String> aa = new ArrayAdapter<>(
                            this, android.R.layout.simple_spinner_item, mapNames(allSubjects));
                    aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spSubjects.setAdapter(aa);
                    rebuildList();
                })
                .addOnFailureListener(e -> {
                    tvMsg.setText("Ders listesi yüklenemedi: " + e.getMessage());
                    allSubjects = Collections.emptyList();
                    spSubjects.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, new ArrayList<>()));
                    rebuildList(); // isimler id ile gösterilir
                });
    }

    private void loadMyMap() {
        profileRepo.loadSubjectsMap(uid)
                .addOnSuccessListener(map -> {
                    priceMap.clear();
                    priceMap.putAll(map);
                    rebuildList();
                })
                .addOnFailureListener(e -> tvMsg.setText("Profil okunamadı: " + e.getMessage()));
    }

    private void rebuildList() {
        current.clear();
        for (Map.Entry<String,Double> e : priceMap.entrySet()) {
            String sid = e.getKey();
            Subject s = findSubject(sid);
            String name = (s!=null && s.nameTR!=null && !s.nameTR.isEmpty()) ? s.nameTR : sid;
            current.add(new Row(sid, name, e.getValue()));
        }
        current.sort(Comparator.comparing(r -> r.subjectName.toLowerCase(Locale.getDefault())));
        adapter.notifyDataSetChanged();
        if (current.isEmpty()) tvMsg.setText("Ders eklemediniz.");
        else tvMsg.setText("");
    }

    private List<String> mapNames(List<Subject> list) {
        List<String> out = new ArrayList<>();
        for (Subject s : list) out.add((s!=null && s.nameTR!=null && !s.nameTR.isEmpty()) ? s.nameTR : (s!=null? s.id:""));
        return out;
    }

    private Subject findSubject(String id) {
        for (Subject s : allSubjects) if (s != null && id != null && id.equals(s.id)) return s;
        return null;
    }

    private void onSave() {
        int idx = spSubjects.getSelectedItemPosition();
        if (idx < 0 || idx >= allSubjects.size()) { tvMsg.setText("Ders seçin."); return; }

        String txt = etPrice.getText().toString().trim().replace(",", ".");
        double price;
        try { price = Double.parseDouble(txt); } catch (Exception ex) { tvMsg.setText("Geçerli bir fiyat girin."); return; }
        if (!ValidationUtil.isValidPrice(price)) { tvMsg.setText("Fiyat 0'dan büyük olmalı."); return; }

        Subject s = allSubjects.get(idx);
        if (s == null || s.id == null) { tvMsg.setText("Ders verisi hatalı."); return; }

        profileRepo.upsertSubjectPrice(uid, s.id, price)
                .addOnSuccessListener(v -> {
                    priceMap.put(s.id, price);
                    etPrice.setText("");
                    tvMsg.setText("Kaydedildi.");
                    rebuildList();
                })
                .addOnFailureListener(e -> tvMsg.setText("Kaydetme hatası: " + e.getMessage()));
    }

    private void confirmDelete(String subjectId) {
        new AlertDialog.Builder(this)
                .setTitle("Dersi sil?")
                .setMessage("Bu dersi listenden kaldırmak istiyor musun?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d,w) -> doDelete(subjectId))
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
        Adapter(List<Row> items, OnDelete onDelete){ this.items=items; this.onDelete=onDelete; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPrice; Button btnDel;
            VH(android.view.View v){ super(v);
                tvName=v.findViewById(R.id.tvName); tvPrice=v.findViewById(R.id.tvPrice); btnDel=v.findViewById(R.id.btnDelete); }
        }
        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int vtype) {
            android.view.View v = android.view.LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_teacher_subject, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            Row r = items.get(pos);
            h.tvName.setText(r.subjectName);
            h.tvPrice.setText(String.format(Locale.getDefault(),"₺%.0f", r.price));
            h.btnDel.setOnClickListener(v -> onDelete.onDelete(r.subjectId));
        }
        @Override public int getItemCount(){ return items.size(); }
    }
}
