package com.example.tutorist.ui.teacher;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TeacherMainActivity extends AppCompatActivity {

    private ListenerRegistration pendingReg;
    private ListenerRegistration upcomingReg;

    // --- FAB & upcoming ---
    private ExtendedFloatingActionButton fabUpcoming;
    private BottomSheetDialog upcomingDialog;
    private RecyclerView upcomingRv;
    private UpcomingAdapter upcomingAdapter;
    private final List<UpcomingItem> upcomingItems = new ArrayList<>();
    private final Map<String, String> studentNameCache = new HashMap<>();
    private static final String TAG = "TeacherMain";

    // FAB için durum
    private Date nextStartAt = null;
    private Date nextEndAt = null;
    private boolean hasLiveWindow = false; // 5 dk önce – 10 dk sonrası aralığında canlı mı?

    @SuppressLint("MissingInflatedId")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_teacher_main);
        ensureTeacherProfileDoc();

        Button btnSubjects = findViewById(R.id.btnSubjects);
        Button btnAvailability = findViewById(R.id.btnAvailability);
        Button btnProfile = findViewById(R.id.btnProfile);

        findViewById(R.id.btnRequests).setOnClickListener(v ->
                startActivity(new Intent(this, TeacherRequestsActivity.class)));
        findViewById(R.id.btnTeacherPast)
                .setOnClickListener(v ->
                        startActivity(new Intent(this, TeacherPastActivity.class)));

        // Savunmacı kontrol
        if (btnSubjects == null || btnAvailability == null || btnProfile == null) {
            Toast.makeText(this, "activity_teacher_main.xml içinde buton id'leri bulunamadı.", Toast.LENGTH_LONG).show();
            throw new IllegalStateException("Missing required views in activity_teacher_main.xml");
        }

        btnSubjects.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherSubjectsActivity.class)));
        btnAvailability.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherAvailabilityActivity.class)));
        btnProfile.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherProfileActivity.class)));

        fabUpcoming = findViewById(R.id.fabUpcoming_teacher);
        if (fabUpcoming != null) {
            fabUpcoming.setOnClickListener(v -> openUpcomingSheet());
        }
    }

    private void requireAuthOrFinish() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            // Dinleyicileri ve runnable'ı bırak
            if (pendingReg != null) { pendingReg.remove(); pendingReg = null; }
            if (upcomingReg != null) { upcomingReg.remove(); upcomingReg = null; }
            if (fabUpcoming != null) fabUpcoming.removeCallbacks(countdownTick);

            // Login'e tertemiz dön
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finishAffinity();
        }
    }


    @Override protected void onStart() {
        super.onStart();

        requireAuthOrFinish();                                // 🔒 önce kontrol
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        subscribePendingBadge();
        subscribeUpcomingForFab();
        if (fabUpcoming != null) fabUpcoming.post(countdownTick);
    }

    @Override protected void onStop() {
        if (pendingReg != null) { pendingReg.remove(); pendingReg = null; }
        if (upcomingReg != null) { upcomingReg.remove(); upcomingReg = null; }
        if (fabUpcoming != null) fabUpcoming.removeCallbacks(countdownTick);
        super.onStop();
    }



    // ---------- FAB görünürlük ve metin ----------
    private void updateFabState() {
        if (fabUpcoming == null) return;
        // canlı varsa ya da gelecekte ders varsa görünür olsun
        fabUpcoming.setVisibility((hasLiveWindow || nextStartAt != null) ? View.VISIBLE : View.GONE);
        updateFabCountdownText();
    }

    private void updateFabCountdownText() {
        if (fabUpcoming == null || fabUpcoming.getVisibility() != View.VISIBLE) return;

        if (hasLiveWindow) {
            fabUpcoming.setText("Canlı");
            return;
        }
        if (nextStartAt == null) return;

        long diff = nextStartAt.getTime() - System.currentTimeMillis();
        if (diff <= 0) { fabUpcoming.setText("00:00"); return; }

        long totalMin = diff / 60000L;
        long days = totalMin / (24 * 60);
        long hours = (totalMin % (24 * 60)) / 60;
        long minutes = totalMin % 60;
        String text = (days > 0)
                ? String.format(Locale.getDefault(), "%dg %02d:%02d", days, hours, minutes)
                : String.format(Locale.getDefault(), "%02d:%02d", hours, minutes);
        fabUpcoming.setText(text);
    }

    // ---------- Upcoming dinleyicisi ----------
    private void subscribeUpcomingForFab() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || fabUpcoming == null) {
            if (fabUpcoming != null) fabUpcoming.setVisibility(View.GONE);
            return;
        }

        if (upcomingReg != null) { upcomingReg.remove(); upcomingReg = null; }

        long nowMs = System.currentTimeMillis();
        Date tenMinAgo = new Date(nowMs - 10 * 60 * 1000);

        // ÖNEMLİ: startAt >= now yerine endAt >= (now - 10dk)
        upcomingReg = FirebaseFirestore.getInstance()
                .collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "accepted")
                .whereGreaterThanOrEqualTo("endAt", tenMinAgo) // <<— gecikenler de kalsın
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        android.util.Log.e(TAG, "Upcoming listen error", e);
                        nextStartAt = nextEndAt = null;
                        hasLiveWindow = false;
                        if (fabUpcoming != null) fabUpcoming.setVisibility(View.GONE);

                        if (String.valueOf(e).contains("FAILED_PRECONDITION")) {
                            Toast.makeText(this, "Firestore index gerekli. Logcat’teki linke tıkla.", Toast.LENGTH_LONG).show();
                        }
                        return;
                    }

                    nextStartAt = null;
                    nextEndAt = null;
                    hasLiveWindow = false;
                    upcomingItems.clear();

                    int count = (snap != null) ? snap.size() : 0;
                    android.util.Log.d(TAG, "Upcoming snapshot ok, count=" + count);

                    if (snap != null && !snap.isEmpty()) {
                        Date nextFutureStart = null, nextFutureEnd = null;
                        long now = System.currentTimeMillis();

                        for (var d : snap.getDocuments()) {
                            Date s = tsToDate(d.get("startAt"));
                            Date eEnd = tsToDate(d.get("endAt"));
                            if (s == null || eEnd == null) continue;

                            UpcomingItem u = new UpcomingItem();
                            u.id = d.getId();
                            u.studentId = str(d.get("studentId"));
                            u.subjectName = str(d.get("subjectName"));
                            u.startAt = s;
                            u.endAt = eEnd;
                            upcomingItems.add(u);

                            // canlı pencere: 5 dk önce – 10 dk sonrası
                            long openAt = s.getTime() - 5 * 60 * 1000;
                            long closeAt = eEnd.getTime() + 10 * 60 * 1000;
                            if (now >= openAt && now <= closeAt) hasLiveWindow = true;

                            // gelecekteki ilk dersi bul (geri sayım için)
                            if (s.getTime() > now && (nextFutureStart == null || s.before(nextFutureStart))) {
                                nextFutureStart = s;
                                nextFutureEnd = eEnd;
                            }
                        }

                        // En yakın tarihe göre sırala
                        upcomingItems.sort((a, b) -> a.startAt.compareTo(b.startAt));

                        // FAB hedefleri
                        nextStartAt = nextFutureStart;
                        nextEndAt = nextFutureEnd;
                    }

                    updateFabState();
                    if (upcomingAdapter != null) {
                        upcomingAdapter.notifyDataSetChanged();
                    }
                });
    }

    @Override protected void onResume() {
        super.onResume();
        requireAuthOrFinish();                                // opsiyonel
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            com.example.tutorist.push.AppMessagingService.syncCurrentFcmToken();
        }
    }

    private void openUpcomingSheet() {
        if (upcomingDialog == null) {
            View content = getLayoutInflater().inflate(R.layout.sheet_teacher_upcoming, null, false);
            upcomingRv = content.findViewById(R.id.rvUpcoming);
            upcomingRv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
            upcomingAdapter = new UpcomingAdapter(
                    upcomingItems,
                    this::getStudentName,
                    this::joinWithWindow
            );

            upcomingRv.setAdapter(upcomingAdapter);
            upcomingDialog = new BottomSheetDialog(this);
            upcomingDialog.setContentView(content);
        }
        upcomingAdapter.notifyDataSetChanged();
        upcomingDialog.show();
    }

    private void getStudentName(String studentId, java.util.function.Consumer<String> cb) {
        if (studentId == null || studentId.isEmpty()) {
            cb.accept("");
            return;
        }
        String cached = studentNameCache.get(studentId);
        if (cached != null) {
            cb.accept(cached);
            return;
        }

        FirebaseFirestore.getInstance().collection("users").document(studentId).get()
                .addOnSuccessListener(d -> {
                    String name = "";
                    if (d != null && d.exists()) {
                        Object n = d.get("fullName");
                        Object e = d.get("email");
                        name = n != null ? String.valueOf(n) : (e != null ? String.valueOf(e) : "");
                    }
                    studentNameCache.put(studentId, name);
                    cb.accept(name);
                })
                .addOnFailureListener(x -> cb.accept(""));
    }

    private void joinWithWindow(UpcomingItem u, View source) {
        if (u == null || u.startAt == null || u.endAt == null) return;
        long now = System.currentTimeMillis();
        long openAt = u.startAt.getTime() - 5 * 60 * 1000;
        long closeAt = u.endAt.getTime() + 10 * 60 * 1000;

        if (now < openAt) {
            Toast.makeText(this, "Ders zamanı gelmedi.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (now > closeAt) {
            Toast.makeText(this, "Ders süresi geçti.", Toast.LENGTH_SHORT).show();
            return;
        }

        source.setEnabled(false);
        com.example.tutorist.util.MeetingUtil.joinDailyMeeting(this, u.id);
        source.postDelayed(() -> source.setEnabled(true), 1200);
    }

    private static Date tsToDate(Object o) {
        if (o instanceof com.google.firebase.Timestamp) return ((com.google.firebase.Timestamp) o).toDate();
        if (o instanceof Date) return (Date) o;
        return null;
    }

    private static String str(Object o) { return o != null ? String.valueOf(o) : ""; }

    private void ensureTeacherProfileDoc() {
        String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        var db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        var ref = db.collection("teacherProfiles").document(uid);

        ref.get().addOnSuccessListener(doc -> {
            if (doc.exists()) return;

            db.collection("users").document(uid).get().addOnSuccessListener(u -> {
                String name = "Öğretmen";
                if (u != null && u.exists()) {
                    Object n = u.get("fullName");
                    Object e = u.get("email");
                    name = n != null ? String.valueOf(n) : (e != null ? String.valueOf(e) : name);
                }
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("displayName", name);
                m.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                ref.set(m, com.google.firebase.firestore.SetOptions.merge());
            });
        });
    }

    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateFabCountdownText();
            if (fabUpcoming != null) fabUpcoming.postDelayed(this, 1000);
        }
    };

    // ---------------- RecyclerView ----------------
    static class UpcomingItem {
        String id;           // bookingId
        String studentId;
        String subjectName;
        Date startAt, endAt;
    }

    private void subscribePendingBadge() {
        TextView badge = findViewById(R.id.badgeRequests);
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || badge == null) return;

        if (pendingReg != null) { pendingReg.remove(); pendingReg = null; }

        pendingReg = FirebaseFirestore.getInstance()
                .collection("bookings")
                .whereEqualTo("teacherId", uid)
                .whereEqualTo("status", "pending")
                .addSnapshotListener((snap, e) -> {
                    int count = (snap != null) ? snap.size() : 0;
                    if (count > 0) {
                        badge.setText(String.valueOf(Math.min(count, 99)));
                        badge.setVisibility(View.VISIBLE);
                    } else {
                        badge.setVisibility(View.GONE);
                    }
                });
    }

    @Override protected void onDestroy() {
        if (pendingReg != null) pendingReg.remove();
        if (upcomingReg != null) upcomingReg.remove();
        super.onDestroy();
    }

    private static class UpcomingAdapter extends RecyclerView.Adapter<UpcomingAdapter.VH> {
        interface NameProvider { void get(String id, java.util.function.Consumer<String> cb); }
        interface OnJoin { void run(UpcomingItem u, View source); }

        private final List<UpcomingItem> data;
        private final NameProvider nameProvider;
        private final OnJoin onJoin;

        UpcomingAdapter(List<UpcomingItem> d, NameProvider np, OnJoin oj) {
            data = d; nameProvider = np; onJoin = oj;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvWhen, chipCountdown;
            Button btnJoin;
            VH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvTitle);
                tvWhen = v.findViewById(R.id.tvWhen);
                chipCountdown = v.findViewById(R.id.chipCountdown);
                btnJoin = v.findViewById(R.id.btnJoin);
            }
        }



        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_teacher_upcoming, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            UpcomingItem u = data.get(pos);

            String subj = (u.subjectName != null && !u.subjectName.isEmpty()) ? u.subjectName : "Ders";
            h.tvTitle.setText(subj);

            nameProvider.get(u.studentId, name -> {
                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                if (name != null && !name.isEmpty()) h.tvTitle.setText(subj + " • " + name);
                else h.tvTitle.setText(subj);
            });

            if (u.startAt != null) {
                DateFormat df = android.text.format.DateFormat.getMediumDateFormat(h.itemView.getContext());
                DateFormat tf = android.text.format.DateFormat.getTimeFormat(h.itemView.getContext());
                h.tvWhen.setText(df.format(u.startAt) + " • " + tf.format(u.startAt));
            } else h.tvWhen.setText("");

            long now = System.currentTimeMillis();

            // Canlı pencere mi?
            String chipText = "00:00";
            if (u.startAt != null && u.endAt != null) {
                long openAt  = u.startAt.getTime() - 5 * 60 * 1000;
                long closeAt = u.endAt.getTime() + 10 * 60 * 1000;

                if (now >= openAt && now <= closeAt) {
                    chipText = "Canlı";
                } else if (u.startAt.getTime() > now) {
                    long diff = u.startAt.getTime() - now;
                    long totalMin = Math.max(0L, diff / 60000L);
                    long days = totalMin / (24 * 60);
                    long hours = (totalMin % (24 * 60)) / 60;
                    long mins = totalMin % 60;
                    chipText = (days > 0)
                            ? String.format(Locale.getDefault(), "%dg %02d:%02d", days, hours, mins)
                            : String.format(Locale.getDefault(), "%02d:%02d", hours, mins);
                }
            }
            h.chipCountdown.setText(chipText);

            boolean canJoin = false;
            if (u.startAt != null && u.endAt != null) {
                long openAt  = u.startAt.getTime() - 5 * 60 * 1000;
                long closeAt = u.endAt.getTime() + 10 * 60 * 1000;
                canJoin = now >= openAt && now <= closeAt;
            }

            h.btnJoin.setEnabled(canJoin);
            if (canJoin) {
                h.btnJoin.setBackgroundResource(R.drawable.bg_btn_outline_primary);
                h.btnJoin.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.tutorist_primary));
                h.btnJoin.setOnClickListener(v -> onJoin.run(u, v));
            } else {
                h.btnJoin.setBackgroundResource(R.drawable.bg_btn_outline_primary_disabled);
                h.btnJoin.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.tutorist_onSurfaceVariant));
                h.btnJoin.setOnClickListener(null);
            }
        }

        @Override public int getItemCount() { return data.size(); }
    }
}
