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
import com.google.firebase.firestore.ListenerRegistration;
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
    private final SlotsAdapter adapter = new SlotsAdapter(new ArrayList<>(), hour -> confirmBooking(hour));

    private final com.example.tutorist.repo.BookingRepo bookingRepo = new com.example.tutorist.repo.BookingRepo();
    enum SlotState { AVAILABLE, BOOKED, PAST }
    private com.google.firebase.firestore.ListenerRegistration slotReg;





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

    private void confirmBooking(int hour) {
        // Önce oturum var mı kontrol et
        String studentId = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (studentId == null) {
            Toast.makeText(this, "Lütfen önce giriş yapın.", Toast.LENGTH_LONG).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Dersi onayla")
                .setMessage(String.format(java.util.Locale.getDefault(),
                        "%s - %02d:00 için rezervasyon oluşturulsun mu?", selectedDateIso(), hour))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Onayla", (d, w) -> {
                    android.util.Log.d(
                            "BOOK",
                            "create " + com.example.tutorist.repo.BookingRepo.slotId(
                                    teacherId, selectedDateIso(), hour
                            ) + " uid=" + studentId
                    );

                    bookingRepo.createBooking(teacherId, studentId, subjectId, selectedDateIso(), hour)
                            .addOnSuccessListener(v -> {
                                Toast.makeText(this, "Talep gönderildi.", Toast.LENGTH_SHORT).show();
                                loadSlotsForSelectedDate();
                            })
                            .addOnFailureListener(e -> {
                                if (e instanceof com.google.firebase.firestore.FirebaseFirestoreException) {
                                    com.google.firebase.firestore.FirebaseFirestoreException fe =
                                            (com.google.firebase.firestore.FirebaseFirestoreException) e;
                                    if (fe.getCode() ==
                                            com.google.firebase.firestore.FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                                        Toast.makeText(this, "Bu saat dolu, başka bir saat seç.", Toast.LENGTH_LONG).show();
                                        loadSlotsForSelectedDate();
                                        return;
                                    }
                                }
                                Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                })
                .show();
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


    @Override protected void onDestroy() {
        if (slotReg != null) { slotReg.remove(); slotReg = null; }
        super.onDestroy();
    }

    private void loadSlotsForSelectedDate() {
        int day = toMon1_7(selected);

        // önce varsa eski listener'ı kaldır
        if (slotReg != null) { slotReg.remove(); slotReg = null; }

        db.collection("availabilities").document(teacherId)
                .collection("weekly")
                .whereEqualTo("dayOfWeek", day)
                .get()
                .addOnSuccessListener(snap -> {
                    // 1) saatleri üret
                    final List<Integer> hours = new ArrayList<>();
                    for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                        Integer sh = d.getLong("startHour") != null ? d.getLong("startHour").intValue() : 0;
                        Integer eh = d.getLong("endHour") != null ? d.getLong("endHour").intValue() : 0;
                        for (int h = sh; h < eh; h++) hours.add(h);
                    }

                    // 2) geçmiş saatler -> past
                    final Set<Integer> past = new HashSet<>();
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    boolean isToday = isSameDay(now, selected);
                    if (isToday) {
                        int curHour = now.get(java.util.Calendar.HOUR_OF_DAY);
                        for (int h : hours) if (h <= curHour) past.add(h);
                    }

                    // Listener gelene kadar ekrana "booked yok" halini çiz (opsiyonel ama güzel)
                    applySlots(hours, past, java.util.Collections.emptySet());

                    // 3) pending/accepted -> booked (slotLocks'tan canlı dinle)
                    slotReg = db.collection("slotLocks")
                            .whereEqualTo("teacherId", teacherId)
                            .whereEqualTo("date", selectedDateIso())
                            .whereIn("status", java.util.Arrays.asList("pending", "accepted"))
                            .addSnapshotListener((bSnap, e) -> {
                                Set<Integer> bookedNow = new HashSet<>();
                                if (e == null && bSnap != null) {
                                    for (com.google.firebase.firestore.DocumentSnapshot b : bSnap.getDocuments()) {
                                        Long lh = b.getLong("hour");
                                        if (lh != null) bookedNow.add(lh.intValue());
                                    }
                                }
                                applySlots(hours, past, bookedNow);
                            });

                })
                .addOnFailureListener(e -> {
                    // availability okunamadıysa boş göster
                    applySlots(java.util.Collections.emptyList(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());
                });
    }




    private void applySlots(List<Integer> hours, Set<Integer> past, Set<Integer> booked) {
        List<SlotsAdapter.Row> rows = new ArrayList<>();
        for (int h : hours) {
            SlotState st = SlotState.AVAILABLE;
            if (booked.contains(h))      st = SlotState.BOOKED;
            else if (past.contains(h))   st = SlotState.PAST;
            rows.add(new SlotsAdapter.Row(h, st));
        }
        adapter.replace(rows);
    }

    // --- Slots Adapter ---
    static class SlotsAdapter extends RecyclerView.Adapter<SlotsAdapter.VH> {
        interface OnSlotClick { void onClick(int hour); }

        static class Row {
            int hour; SlotState state;
            Row(int h, SlotState s){ hour=h; state=s; }
            boolean disabled(){ return state != SlotState.AVAILABLE; }
        }

        private final List<Row> items;
        private final OnSlotClick onSlotClick;

        SlotsAdapter(List<Row> items, OnSlotClick onSlotClick){ this.items=items; this.onSlotClick=onSlotClick; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(android.view.View v){ super(v); tv=v.findViewById(R.id.tvHour); }
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int vt) {
            android.view.View v = android.view.LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_hour_slot, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            Row r = items.get(pos);
            h.tv.setText(String.format(java.util.Locale.getDefault(), "%02d:00", r.hour));

            // görünüm ve tıklanabilirlik
            boolean disabled = r.disabled();
            h.itemView.setEnabled(!disabled);
            h.tv.setAlpha(disabled ? 0.5f : 1f);

            // arka planı duruma göre ver
            if (r.state == SlotState.AVAILABLE) {
                h.itemView.setBackgroundResource(R.drawable.bg_slot_available);
            } else if (r.state == SlotState.BOOKED) {
                h.itemView.setBackgroundResource(R.drawable.bg_slot_booked);
            } else { // PAST
                h.itemView.setBackgroundResource(R.drawable.bg_slot_past);
            }

            h.itemView.setOnClickListener(v -> {
                if (!disabled) onSlotClick.onClick(r.hour);
            });
        }

        @Override public int getItemCount(){ return items.size(); }
        void replace(List<Row> rows){ items.clear(); items.addAll(rows); notifyDataSetChanged(); }
    }

    private Integer getInt(com.google.firebase.firestore.DocumentSnapshot d, String a, String b) {
        Long v = d.getLong(a);
        if (v == null && b != null) v = d.getLong(b);
        return v != null ? v.intValue() : null;
    }


    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR)==b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR);
    }


}
