package com.example.tutorist.ui.teacher;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.tutorist.R;
import com.example.tutorist.repo.BookingRepo;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;

public class TeacherRequestsActivity extends AppCompatActivity {

    private RecyclerView rv;
    private View progress, empty;
    private Adapter adapter;
    private String uid;
    private ListenerRegistration reg;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final BookingRepo bookingRepo = new BookingRepo();

    private static final String TAG = "TeacherReq";


    static class Row {
        String id, studentId, studentName, subjectId, subjectName, date;
        int hour;
        Row(String id, String studentId, String studentName,
            String subjectId, String subjectName, String date, int hour){
            this.id=id; this.studentId=studentId; this.studentName=studentName;
            this.subjectId=subjectId; this.subjectName=subjectName;
            this.date=date; this.hour=hour;
        }
    }


    private final List<Row> items = new ArrayList<>();

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_requests);
        setTitle("Gelen Talepler");

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; }

        rv       = findViewById(R.id.rv);
        progress = findViewById(R.id.progress);
        empty    = findViewById(R.id.empty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(items, this::onAccept, this::onDecline);
        rv.setAdapter(adapter);

        Log.d(TAG, "onCreate, uid=" + uid);
        FirebaseOptions opts = FirebaseApp.getInstance().getOptions();
        Log.d(TAG, "Firebase projectId=" + opts.getProjectId() + ", appId=" + opts.getApplicationId());


        listenPending();
    }

    private void listenPending() {
        if (reg != null) reg.remove();
        progress.setVisibility(View.VISIBLE);

        Log.d(TAG, "listenPending(): start, teacherId=" + uid);

        reg = db.collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "pending")
                .addSnapshotListener((snap, e) -> {
                    progress.setVisibility(View.GONE);

                    if (e != null) {
                        if (e instanceof FirebaseFirestoreException) {
                            FirebaseFirestoreException fe = (FirebaseFirestoreException) e;
                            Log.e(TAG, "Firestore listen failed. code=" + fe.getCode()
                                    + " msg=" + fe.getMessage(), fe);
                        } else {
                            Log.e(TAG, "Firestore listen failed: " + e.getMessage(), e);
                        }
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }

                    Log.d(TAG, "snapshot ok. size=" + snap.size()
                            + " fromCache=" + snap.getMetadata().isFromCache());

                    items.clear();
                    for (DocumentSnapshot d : snap) {
                        String id = d.getId();
                        String studentId = String.valueOf(d.get("studentId"));
                        String subjectId = String.valueOf(d.get("subjectId"));
                        String date = String.valueOf(d.get("date"));
                        int hour = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;

                        String studentName = d.getString("studentName");
                        if (studentName == null || studentName.trim().isEmpty()) studentName = studentId;

                        String subjectName = d.getString("subjectName");
                        if (subjectName == null || subjectName.trim().isEmpty()) subjectName = subjectId;

                        items.add(new Row(id, studentId, studentName, subjectId, subjectName, date, hour));
                    }

                    items.sort(Comparator.comparing((Row r) -> r.date).thenComparingInt(r -> r.hour));
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }


    private void onAccept(Row r) {
        Log.d(TAG, "onAccept -> " + r.id);
        bookingRepo.updateStatusById(r.id, "accepted")
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
        // Başarılı olursa snapshot dinleyicisi zaten listeyi yenileyecek.
    }

    private void onDecline(Row r) {
        Log.d(TAG, "onDecline -> " + r.id);
        bookingRepo.updateStatusById(r.id, "declined")
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }


    @Override protected void onDestroy() {
        if (reg != null) reg.remove();
        super.onDestroy();
    }

    // --- Adapter ---
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface Act { void run(Row r); }
        private final List<Row> items; private final Act onAccept, onDecline;
        Adapter(List<Row> items, Act onAccept, Act onDecline){ this.items=items; this.onAccept=onAccept; this.onDecline=onDecline; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            Button btnAcc, btnDec;
            VH(View v){ super(v);
                tv=v.findViewById(R.id.tvLine);
                btnAcc=v.findViewById(R.id.btnAccept);
                btnDec=v.findViewById(R.id.btnDecline);
            }
        }

        @Override public VH onCreateViewHolder(ViewGroup p, int vt){
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_teacher_request, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos){
            Row r = items.get(pos);
            h.tv.setText(String.format(
                    Locale.getDefault(),
                    "%s  %02d:00\nÖğrenci adı: %s\nDers adı: %s",
                    r.date, r.hour, r.studentName, r.subjectName
            ));

            h.btnAcc.setOnClickListener(v -> {
                h.btnAcc.setEnabled(false);
                h.btnDec.setEnabled(false);
                onAccept.run(r);
            });
            h.btnDec.setOnClickListener(v -> {
                h.btnAcc.setEnabled(false);
                h.btnDec.setEnabled(false);
                onDecline.run(r);
            });
        }

        @Override public int getItemCount(){ return items.size(); }
    }
}
