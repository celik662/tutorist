// app/src/main/java/com/example/tutorist/ui/teacher/TeacherRequestsActivity.java
package com.example.tutorist.ui.teacher;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.repo.BookingRepo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

import java.util.*;

public class TeacherRequestsActivity extends AppCompatActivity {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final BookingRepo bookingRepo = new BookingRepo();
    private RecyclerView rv;
    private Adapter adapter;
    private String uid;

    static class Row {
        String id, studentId, subjectId, date; int hour; String status;
        Row(String id, String studentId, String subjectId, String date, int hour, String status){
            this.id=id; this.studentId=studentId; this.subjectId=subjectId;
            this.date=date; this.hour=hour; this.status=status;
        }
    }
    private final List<Row> items = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_requests);
        setTitle("Gelen Talepler");
        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { finish(); return; }

        rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(items, this::onAccept, this::onDecline);
        rv.setAdapter(adapter);

        listenPending();
    }

    private ListenerRegistration reg;
    private void listenPending() {
        if (reg != null) reg.remove();
        reg = db.collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "pending")
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    items.clear();
                    for (DocumentSnapshot d : snap) {
                        String id = d.getId();
                        String studentId = String.valueOf(d.get("studentId"));
                        String subjectId = String.valueOf(d.get("subjectId"));
                        String date = String.valueOf(d.get("date"));
                        Integer hour = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;
                        String status = String.valueOf(d.get("status"));
                        items.add(new Row(id, studentId, subjectId, date, hour, status));
                    }
                    // İstersen saat/tarihe göre sırala:
                    Collections.sort(items, Comparator
                            .comparing((Row r) -> r.date)
                            .thenComparingInt(r -> r.hour));
                    adapter.notifyDataSetChanged();
                });
    }

    private void onAccept(Row r) {
        bookingRepo.updateStatusById(r.id, "accepted")
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void onDecline(Row r) {
        bookingRepo.updateStatusById(r.id, "declined")
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override protected void onDestroy() {
        if (reg != null) reg.remove();
        super.onDestroy();
    }

    // basit adapter
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface OnAct { void act(Row r); }
        private final List<Row> items; private final OnAct onAccept, onDecline;
        Adapter(List<Row> items, OnAct onAccept, OnAct onDecline){
            this.items=items; this.onAccept=onAccept; this.onDecline=onDecline;
        }
        static class VH extends RecyclerView.ViewHolder {
            TextView tv; Button btnAcc, btnDec;
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
        @SuppressLint("SetTextI18n")
        @Override public void onBindViewHolder(VH h, int pos){
            Row r = items.get(pos);
            h.tv.setText(r.date + " " + String.format(Locale.getDefault(), "%02d:00", r.hour)
                    + " • student=" + r.studentId + " • subj=" + r.subjectId);
            h.btnAcc.setOnClickListener(v -> onAccept.act(r));
            h.btnDec.setOnClickListener(v -> onDecline.act(r));
        }
        @Override public int getItemCount(){ return items.size(); }
    }
}
