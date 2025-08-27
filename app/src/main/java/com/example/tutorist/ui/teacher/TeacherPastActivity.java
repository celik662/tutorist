package com.example.tutorist.ui.teacher;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.repo.BookingRepo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

import java.util.*;

public class TeacherPastActivity extends AppCompatActivity {

    private RecyclerView rv;
    private ProgressBar progress;
    private TextView empty;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final BookingRepo bookingRepo = new BookingRepo();
    private ListenerRegistration reg;
    private String uid;

    static class Row {
        String id, studentId, studentName, subjectName, status, date; int hour;
        boolean hasNote;
        java.util.Date endAt;
    }
    private final List<Row> items = new ArrayList<>();
    private Adapter adapter;

    // --- EKLENDİ: UI yardımcıları ---
    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            rv.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            empty.setText("");
        }
    }
    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        empty.setText(isEmpty ? "Henüz tamamlanmış dersiniz yok." : "");
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rv.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
    private void showErrorState(String message) {
        progress.setVisibility(View.GONE);
        rv.setVisibility(View.GONE);
        empty.setText(message != null ? message : "Bir şeyler ters gitti.");
        empty.setVisibility(View.VISIBLE);
    }
    // --- /EKLENDİ ---

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_past);
        setTitle("Geçmiş Dersler");

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; }

        rv = findViewById(R.id.rv);
        progress = findViewById(R.id.progress);
        empty = findViewById(R.id.empty);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(items, this::onAddSummary, this::onViewSummary);
        rv.setAdapter(adapter);

        // EKLENDİ: adapter değiştiğinde boş/dolu görünümünü güncelle
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { updateEmptyState(); }
            @Override public void onItemRangeInserted(int positionStart, int itemCount) { updateEmptyState(); }
            @Override public void onItemRangeRemoved(int positionStart, int itemCount) { updateEmptyState(); }
        });

        // İlk açılışta yükleme göster
        setLoading(true);
    }

    @Override protected void onStart() {
        super.onStart();
        listenPast();
    }
    @Override protected void onStop() {
        if (reg != null) reg.remove();
        super.onStop();
    }

    private void listenPast() {
        if (reg != null) { reg.remove(); reg = null; }
        setLoading(true);

        // Asıl (indexed) sorgu
        Query q = db.collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereIn("status", Arrays.asList("accepted","completed"))
                .whereLessThanOrEqualTo("endAt", new Date())
                .orderBy("endAt", Query.Direction.DESCENDING);

        reg = q.addSnapshotListener((snap, e) -> {
            setLoading(false);

            if (e != null) {
                // Index henüz "READY" değilse buraya düşer
                if (e instanceof FirebaseFirestoreException &&
                        ((FirebaseFirestoreException) e).getCode()
                                == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                    listenPastFallback(); // <-- geçici çözüm
                    return;
                }
                showErrorState(e.getMessage());
                return;
            }

            bindItemsFromSnapshot(snap);
        });
    }

    // Index hazır olana kadar kullanılacak basit sorgu:
    // Sadece teacherId ile çek -> client-side filtrele & sırala
    private void listenPastFallback() {
        if (reg != null) { reg.remove(); reg = null; }
        setLoading(true);

        reg = db.collection("bookings")
                .whereEqualTo("teacherId", uid)
                .addSnapshotListener((snap, e) -> {
                    setLoading(false);
                    if (e != null || snap == null) {
                        showErrorState(e != null ? e.getMessage() : "Hata");
                        return;
                    }
                    items.clear();
                    java.util.Date now = new java.util.Date();

                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String status = String.valueOf(d.get("status"));
                        java.util.Date endAt = d.getDate("endAt");
                        if (!Arrays.asList("accepted","completed").contains(status)) continue;
                        if (endAt == null || endAt.after(now)) continue; // sadece geçmiş

                        Row r = new Row();
                        r.id = d.getId();
                        r.studentId = String.valueOf(d.get("studentId"));
                        Object sn = d.get("studentName");
                        r.studentName = sn != null ? String.valueOf(sn) : r.studentId;
                        Object subn = d.get("subjectName");
                        r.subjectName = subn != null ? String.valueOf(subn) : String.valueOf(d.get("subjectId"));
                        r.status = status;
                        r.date   = String.valueOf(d.get("date"));
                        r.hour   = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;
                        r.hasNote = false;
                        r.endAt = endAt;
                        items.add(r);

                        db.collection("teacherNotes").document(r.id).get()
                                .addOnSuccessListener(note -> { r.hasNote = note != null && note.exists(); adapter.notifyDataSetChanged(); });
                    }

                    // endAt’e göre yeni->eski
                    items.sort((a,b) -> {
                        if (a.endAt == null && b.endAt == null) return 0;
                        if (a.endAt == null) return 1;
                        if (b.endAt == null) return -1;
                        return b.endAt.compareTo(a.endAt);
                    });

                    adapter.notifyDataSetChanged();
                    updateEmptyState(); // EKLENDİ
                });
    }

    // Ortak bağlama – asıl (indexed) sorgu başarılıysa burası çalışır
    private void bindItemsFromSnapshot(QuerySnapshot snap) {
        items.clear();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Row r = new Row();
            r.id = d.getId();
            r.studentId = String.valueOf(d.get("studentId"));
            Object sn = d.get("studentName");
            r.studentName = sn != null ? String.valueOf(sn) : r.studentId;
            Object subn = d.get("subjectName");
            r.subjectName = subn != null ? String.valueOf(subn) : String.valueOf(d.get("subjectId"));
            r.status = String.valueOf(d.get("status"));
            r.date   = String.valueOf(d.get("date"));
            r.hour   = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;
            r.hasNote = false;
            r.endAt = d.getDate("endAt");
            items.add(r);

            db.collection("teacherNotes").document(r.id).get()
                    .addOnSuccessListener(note -> { r.hasNote = note != null && note.exists(); adapter.notifyDataSetChanged(); });
        }
        // Indexed sorgu zaten endAt DESC döndürüyor ama yine de güvene alalım:
        items.sort((a,b) -> {
            if (a.endAt == null && b.endAt == null) return 0;
            if (a.endAt == null) return 1;
            if (b.endAt == null) return -1;
            return b.endAt.compareTo(a.endAt);
        });

        adapter.notifyDataSetChanged();
        updateEmptyState(); // EKLENDİ
    }


    private void onViewSummary(Row r) {
        db.collection("teacherNotes").document(r.id).get()
                .addOnSuccessListener(doc -> {
                    if (doc == null || !doc.exists()) {
                        Toast.makeText(this, "Özet bulunamadı.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String note = String.valueOf(doc.get("note"));
                    new AlertDialog.Builder(this)
                            .setTitle("Ders Notu")
                            .setMessage(note != null ? note : "")
                            .setPositiveButton("Kapat", null)
                            .show();
                })
                .addOnFailureListener(e -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
    }


    private void onAddSummary(Row r) {
        // Dialog: Rating + Note
        final View view = LayoutInflater.from(this).inflate(R.layout.dialog_teacher_note, null, false);
        RatingBar ratingBar = view.findViewById(R.id.ratingBar);
        EditText et = view.findViewById(R.id.etNote);

        new AlertDialog.Builder(this)
                .setTitle("Özet / Puan Ver")
                .setView(view)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kaydet", (d,w) -> {
                    int rating = Math.max(1, Math.min(5, Math.round(ratingBar.getRating())));
                    String note = et.getText().toString().trim();

                    Map<String,Object> m = new HashMap<>();
                    m.put("bookingId", r.id);
                    m.put("teacherId", uid);
                    m.put("studentId", r.studentId);
                    m.put("rating", rating);
                    m.put("note", note);
                    m.put("createdAt", FieldValue.serverTimestamp());

                    WriteBatch batch = db.batch();
                    batch.set(db.collection("teacherNotes").document(r.id), m);
                    // Ders "accepted" ise tamamlanmışa çek
                    batch.update(db.collection("bookings").document(r.id),
                            "status", "completed",
                            "updatedAt", FieldValue.serverTimestamp());
                    batch.update(db.collection("slotLocks").document(r.id),
                            "status", "completed",
                            "updatedAt", FieldValue.serverTimestamp());

                    batch.commit()
                            .addOnSuccessListener(vv -> Toast.makeText(this, "Kaydedildi.", Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .show();
    }

    // ---- Adapter ----
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface Act { void run(Row r); }
        private final List<Row> items; private final Act onAdd; private final Act onView;
        Adapter(List<Row> it, Act onAdd, Act onView){ items=it; this.onAdd=onAdd; this.onView=onView; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv1, tv2; Button btnSummary, btnView;
            VH(View v){ super(v);
                tv1=v.findViewById(R.id.tvLine1);
                tv2=v.findViewById(R.id.tvLine2);
                btnSummary=v.findViewById(R.id.btnSummary);
                btnView=v.findViewById(R.id.btnView);
            }
        }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt){
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_teacher_past, p, false));
        }
        @SuppressLint("SetTextI18n")
        @Override public void onBindViewHolder(@NonNull VH h, int pos){
            Row r = items.get(pos);
            h.tv1.setText(r.studentName + " • " + r.subjectName);
            h.tv2.setText(String.format(Locale.getDefault(), "%s %02d:00  • Durum: %s", r.date, r.hour,
                    "Tamamlandı"));

            h.btnSummary.setVisibility(r.hasNote ? View.GONE : View.VISIBLE);
            h.btnView.setVisibility(r.hasNote ? View.VISIBLE : View.GONE);

            h.btnSummary.setOnClickListener(v -> onAdd.run(r));
            h.btnView.setOnClickListener(v -> onView.run(r));
        }
        @Override public int getItemCount(){ return items.size(); }
    }
}
