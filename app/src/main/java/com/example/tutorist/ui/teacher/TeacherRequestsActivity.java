// app/src/main/java/com/example/tutorist/ui/teacher/TeacherRequestsActivity.java
package com.example.tutorist.ui.teacher;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.repo.BookingRepo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

import java.text.SimpleDateFormat;
import java.util.*;

public class TeacherRequestsActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView empty, tvTitle, tvSubtitle;
    private com.google.android.material.progressindicator.CircularProgressIndicator progress;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final BookingRepo bookingRepo = new BookingRepo();
    private ListenerRegistration reg;
    private String uid;

    static class Row {
        String id;
        String studentId, studentName;
        String subjectId, subjectName;
        String date; int hour; // "YYYY-MM-DD" + 0..23
    }
    private final List<Row> items = new ArrayList<>();
    private Adapter adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_requests);
        setTitle("Talepler");

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; }

        tvTitle    = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        empty      = findViewById(R.id.empty);
        progress   = findViewById(R.id.progress);
        rv         = findViewById(R.id.rv);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(items, this::onAccept, this::onDecline);
        rv.setAdapter(adapter);

        // Liste değiştikçe boş görünümü güncelle
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { updateEmptyState(); }
            @Override public void onItemRangeInserted(int s, int c) { updateEmptyState(); }
            @Override public void onItemRangeRemoved(int s, int c) { updateEmptyState(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        com.example.tutorist.push.AppMessagingService.syncCurrentFcmToken();
    }

    @Override protected void onStart() {
        super.onStart();
        listenPending();
    }

    @Override protected void onStop() {
        if (reg != null) { reg.remove(); reg = null; }
        super.onStop();
    }

    private void setLoading(boolean loading){
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) { rv.setVisibility(View.GONE); empty.setVisibility(View.GONE); }
    }
    private void updateEmptyState(){
        boolean isEmpty = adapter.getItemCount() == 0;
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rv.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void listenPending() {
        if (reg != null) { reg.remove(); reg = null; }
        setLoading(true);

        // Basit ve indeks dostu: teacherId + status = pending
        Query q = db.collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "pending");

        reg = q.addSnapshotListener((snap, e) -> {
            setLoading(false);
            if (e != null || snap == null) {
                empty.setText(e != null ? e.getMessage() : "Hata");
                empty.setVisibility(View.VISIBLE);
                rv.setVisibility(View.GONE);
                return;
            }

            items.clear();
            for (DocumentSnapshot d : snap.getDocuments()) {
                Row r = new Row();
                r.id         = d.getId();
                r.studentId  = str(d.get("studentId"));
                Object sn    = d.get("studentName");
                r.studentName= sn != null ? String.valueOf(sn) : r.studentId;

                r.subjectId  = str(d.get("subjectId"));
                Object subn  = d.get("subjectName");
                r.subjectName= subn != null ? String.valueOf(subn) : r.subjectId;

                r.date       = str(d.get("date"));
                r.hour       = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;

                items.add(r);
            }

            // Tarih + saat’e göre sıralama (en yakın önce)
            items.sort((a,b) -> {
                int c = nullSafe(a.date).compareTo(nullSafe(b.date));
                if (c != 0) return c;
                return Integer.compare(a.hour, b.hour);
            });

            adapter.notifyDataSetChanged();
            updateEmptyState();
        });
    }

    private static String str(Object o){ return o==null? "" : String.valueOf(o); }
    private static String nullSafe(String s){ return s==null ? "" : s; }

    private void onAccept(Row r) {
        // Çift tıklama vb. için basit koruma: butonu adapter içinde disable ediyoruz
        bookingRepo.updateStatusById(r.id, "accepted")
                .addOnFailureListener(e -> toast("Kabul hatası: " + e.getMessage()));
    }

    private void onDecline(Row r) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Talebi reddet?")
                .setMessage("Bu talebi reddetmek istediğinize emin misiniz?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Reddet", (d,w) ->
                        bookingRepo.updateStatusById(r.id, "declined")
                                .addOnFailureListener(e -> toast("Reddetme hatası: " + e.getMessage())))
                .show();
    }

    private void toast(String m){ Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    /* ================= Adapter ================= */

    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface Act { void run(Row r); }
        private final List<Row> items; private final Act onAccept; private final Act onDecline;
        Adapter(List<Row> it, Act accept, Act decline){ items=it; onAccept=accept; onDecline=decline; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvTop, tvBottom;
            com.google.android.material.button.MaterialButton btnAccept, btnDecline;
            VH(View v){
                super(v);
                tvTop     = v.findViewById(R.id.tvLineTop);
                tvBottom  = v.findViewById(R.id.tvLineBottom);
                btnAccept = v.findViewById(R.id.btnAccept);
                btnDecline= v.findViewById(R.id.btnDecline);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt){
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_teacher_request, p, false);
            return new VH(v);
        }

        @SuppressLint("SetTextI18n")
        @Override public void onBindViewHolder(@NonNull VH h, int pos){
            Row r = items.get(pos);

            String top = (r.studentName!=null && !r.studentName.isEmpty() ? r.studentName : r.studentId)
                    + " • " + (r.subjectName!=null && !r.subjectName.isEmpty() ? r.subjectName : r.subjectId);
            h.tvTop.setText(top);

            String bottom = fmtDate(r.date) + "  •  " + String.format(Locale.getDefault(), "%02d:00", r.hour);
            h.tvBottom.setText(bottom);

            h.btnAccept.setEnabled(true);
            h.btnDecline.setEnabled(true);

            h.btnAccept.setOnClickListener(v -> {
                h.btnAccept.setEnabled(false);
                h.btnDecline.setEnabled(false);
                onAccept.run(r);
            });
            h.btnDecline.setOnClickListener(v -> {
                h.btnAccept.setEnabled(false);
                h.btnDecline.setEnabled(false);
                onDecline.run(r);
            });
        }

        @Override public int getItemCount(){ return items.size(); }



        private String fmtDate(String ymd) {
            // "2025-08-25" → "25 Ağu 2025"
            try {
                java.text.DateFormat in  = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                java.text.DateFormat out = new java.text.SimpleDateFormat("d MMM yyyy", new Locale("tr"));
                Date d = ((SimpleDateFormat)in).parse(ymd);
                return out.format(d);
            } catch (Exception e){
                return ymd != null ? ymd : "";
            }
        }
    }
}
