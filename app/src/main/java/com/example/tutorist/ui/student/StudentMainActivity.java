package com.example.tutorist.ui.student;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StudentMainActivity extends AppCompatActivity {
    private static final String TAG = "StudentMain";
    private ListenerRegistration histReg;
    private BadgeDrawable histBadge;
    private BottomNavigationView nav;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabUpcoming;

    // countdown tick
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateFabCountdownText();           // sadece metni yenile
            fabUpcoming.postDelayed(this, 1000);
        }
    };

    // snapshot’tan hesaplanan en yakın accepted dersin başlangıç-bitiş anları
    private Date nextStartAt = null;
    private Date nextEndAt = null;
    private String nextLabelSubject = "";
    private String nextLabelTeacher = "";

    // StudentMainActivity alanlarına ekle
    private com.google.android.material.bottomsheet.BottomSheetDialog upcomingDialog;
    private RecyclerView upcomingRv;
    private UpcomingAdapter upcomingAdapter;

    // accepted & future derslerin cache’i (tek sorgudan doldurulacak)
    private final List<UpcomingItem> upcomingItems = new ArrayList<>();

    // Opsiyonel: öğretmen adını sheet’te göstermek için hafif cache
    private final Map<String, String> teacherNameCache = new HashMap<>();

    static class UpcomingItem {
        String id;
        String teacherId;
        String subjectName;
        Date startAt;
        Date endAt;
    }

    // Hangi tab açık?
    private int currentTabId = R.id.nav_lessons;
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_student_main);

        nav = findViewById(R.id.bottomNav);
        fabUpcoming = findViewById(R.id.fabUpcoming);

        nav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
            currentTabId = id; // 👈 aktif sekmeyi takip et

            if (id == R.id.nav_lessons) {
                f = new LessonsFragment();
            } else if (id == R.id.nav_history) {
                f = new HistoryFragment();
            } else {
                f = new StudentProfileFragment();
            }

            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .commit();

            // Dersler sekmesi dışındayken FAB görünmesin
            syncFabVisibilityWithTab();
            return true;
        });

        // Rozeti MENÜ ÖĞESİNE bağla (mevcut)
        histBadge = nav.getOrCreateBadge(R.id.nav_history);
        histBadge.setVisible(false);
        histBadge.setMaxCharacterCount(3);

        // FAB tıklaması: şimdilik sadece “Katıl” penceresi kurallarına saygılı bir sheet açacağız
        fabUpcoming.setOnClickListener(v -> openUpcomingSheet()); // Sheet’i sonraki adımda detaylandıracağız

        // Varsayılan sekme
        nav.setSelectedItemId(R.id.nav_lessons);
    }


    @Override protected void onStart() {
        super.onStart();
        subscribeHistoryBadge();
        fabUpcoming.post(countdownTick);
    }

    @Override protected void onStop() {
        super.onStop();
        if (histReg != null) { histReg.remove(); histReg = null; }
        fabUpcoming.removeCallbacks(countdownTick);
    }
    private void openUpcomingSheet() {
        if (upcomingDialog == null) {
            View content = getLayoutInflater().inflate(R.layout.sheet_upcoming, null, false);
            upcomingRv = content.findViewById(R.id.rvUpcoming);
            upcomingRv.setLayoutManager(new LinearLayoutManager(this));

            upcomingAdapter = new UpcomingAdapter(
                    upcomingItems,
                    this::getTeacherName,    // lazily cache’leyeceğiz
                    this::joinWithWindow     // Katıl kuralı aynı
            );
            upcomingRv.setAdapter(upcomingAdapter);

            upcomingDialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
            upcomingDialog.setContentView(content);
        }

        // Veri güncel → göster
        upcomingAdapter.notifyDataSetChanged();
        upcomingDialog.show();
    }

    // Öğretmen adını hafifçe cache’le (sheet görünürken bind olunca 1-1 doküman çekebilir)
    private void getTeacherName(String teacherId, java.util.function.Consumer<String> cb) {
        if (teacherId == null || teacherId.isEmpty()) { cb.accept(""); return; }
        String cached = teacherNameCache.get(teacherId);
        if (cached != null) { cb.accept(cached); return; }

        FirebaseFirestore.getInstance().collection("teacherProfiles")
                .document(teacherId)
                .get()
                .addOnSuccessListener(ds -> {
                    String name = "";
                    if (ds != null && ds.exists()) {
                        Object dn = ds.get("displayName");
                        if (dn == null) dn = ds.get("fullName");
                        if (dn != null) name = String.valueOf(dn);
                    }
                    teacherNameCache.put(teacherId, name);
                    cb.accept(name);
                })
                .addOnFailureListener(e -> cb.accept(""));
    }

    private void joinWithWindow(UpcomingItem u, View source) {
        if (u == null || u.startAt == null || u.endAt == null) return;
        long now = System.currentTimeMillis();
        long openAt  = u.startAt.getTime() - 5 * 60 * 1000;
        long closeAt = u.endAt.getTime()   + 10 * 60 * 1000;

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



    /** Bottom nav rozetini (badge) güncel tut: pending + accepted (gelecek/süren dersler) */
    /** Bottom nav rozetini ve FAB verisini güncel tut:
     *  status in ['pending','accepted'] AND startAt >= now
     */
    private void subscribeHistoryBadge() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            Log.w(TAG, "subscribeHistoryBadge: uid null");
            return;
        }
        if (histReg != null) { histReg.remove(); histReg = null; }

        List<String> statuses = Arrays.asList("pending", "accepted");
        Date now = new Date();

        Query q = FirebaseFirestore.getInstance()
                .collection("bookings")
                .whereEqualTo("studentId", uid)
                .whereIn("status", statuses)
                .whereGreaterThanOrEqualTo("startAt", now);

        Log.d(TAG, "subscribeHistoryBadge: listen start (uid=" + uid + ")");
        histReg = q.addSnapshotListener((snap, e) -> {
            if (e != null) {
                Log.e(TAG, "badge snapshot error: " + e.getMessage(), e);
                if (histBadge != null) histBadge.setVisible(false);
                // Hata durumunda FAB’ı da gizle
                nextStartAt = nextEndAt = null;
                updateFabState();
                return;
            }

            int count = (snap != null) ? snap.size() : 0;
            if (histBadge != null) {
                histBadge.setVisible(count > 0);
                histBadge.setNumber(count);
            }

            // --- FAB için en yakın "accepted & future" dersi çıkar ---
            nextStartAt = null; nextEndAt = null;
            nextLabelSubject = ""; nextLabelTeacher = "";

            if (snap != null && !snap.isEmpty()) {
                Date bestStart = null; Date bestEnd = null;
                String bestSubject = ""; String bestTeacher = "";

                for (var d : snap.getDocuments()) {
                    String status = String.valueOf(d.get("status"));
                    if (!"accepted".equalsIgnoreCase(status)) continue;

                    Object tsStart = d.get("startAt");
                    Object tsEnd = d.get("endAt");
                    Date s = tsStart instanceof com.google.firebase.Timestamp
                            ? ((com.google.firebase.Timestamp) tsStart).toDate()
                            : (tsStart instanceof Date ? (Date) tsStart : null);
                    Date eEnd = tsEnd instanceof com.google.firebase.Timestamp
                            ? ((com.google.firebase.Timestamp) tsEnd).toDate()
                            : (tsEnd instanceof Date ? (Date) tsEnd : null);

                    if (s == null) continue;
                    if (s.before(now)) continue; // sadece gelecekte olanlar

                    // en yakını seç
                    if (bestStart == null || s.before(bestStart)) {
                        bestStart = s;
                        bestEnd = eEnd;

                        // Etiketler (opsiyonel – sheet’te gösteririz)
                        Object subj = d.get("subjectName");
                        bestSubject = subj != null ? String.valueOf(subj) : "";
                        // Öğretmen adını burada çekmiyoruz; FAB’da metin gerekmiyor. (Sheet’te repo/cache’den doldururuz)
                        bestTeacher = "";
                    }
                }

                nextStartAt = bestStart;
                nextEndAt = bestEnd;
                nextLabelSubject = bestSubject;
                nextLabelTeacher = bestTeacher;
            }

            // ... snap geldikten sonra:
            nextStartAt = null; nextEndAt = null;
            nextLabelSubject = ""; nextLabelTeacher = "";

// YENİ: upcomingItems’ı doldur
            upcomingItems.clear();

            if (snap != null && !snap.isEmpty()) {
                Date nowLocal = new Date();
                Date bestStart = null; Date bestEnd = null;
                String bestSubject = ""; String bestTeacher = "";

                for (var d : snap.getDocuments()) {
                    String status = String.valueOf(d.get("status"));
                    if (!"accepted".equalsIgnoreCase(status)) continue;

                    Object tsStart = d.get("startAt");
                    Object tsEnd   = d.get("endAt");
                    Date s = tsStart instanceof com.google.firebase.Timestamp
                            ? ((com.google.firebase.Timestamp) tsStart).toDate()
                            : (tsStart instanceof Date ? (Date) tsStart : null);
                    Date eEnd = tsEnd instanceof com.google.firebase.Timestamp
                            ? ((com.google.firebase.Timestamp) tsEnd).toDate()
                            : (tsEnd instanceof Date ? (Date) tsEnd : null);
                    if (s == null) continue;
                    if (!s.after(nowLocal)) continue; // sadece gelecektekiler

                    UpcomingItem ui = new UpcomingItem();
                    ui.id = d.getId();
                    Object tid = d.get("teacherId");
                    ui.teacherId = tid != null ? String.valueOf(tid) : "";
                    Object subj = d.get("subjectName");
                    ui.subjectName = subj != null ? String.valueOf(subj) : "";
                    ui.startAt = s;
                    ui.endAt = eEnd;
                    upcomingItems.add(ui);

                    if (bestStart == null || s.before(bestStart)) {
                        bestStart = s; bestEnd = eEnd;
                        bestSubject = ui.subjectName;
                    }
                }

                // sırala
                upcomingItems.sort(Comparator.comparing(u -> u.startAt));

                nextStartAt = bestStart;
                nextEndAt   = bestEnd;
                nextLabelSubject = bestSubject;
            }

            updateFabState();
            if (upcomingAdapter != null) {
                upcomingAdapter.notifyDataSetChanged();
            }


        });
    }


    private void syncFabVisibilityWithTab() {
        // Sadece Dersler sekmesinde görünsün
        if (currentTabId != R.id.nav_lessons) {
            fabUpcoming.setVisibility(View.GONE);
            return;
        }
        // Dersler sekmesindeyken güncel duruma bak
        if (nextStartAt == null) {
            fabUpcoming.setVisibility(View.GONE);
        } else {
            fabUpcoming.setVisibility(View.VISIBLE);
        }
    }

    private void updateFabState() {
        syncFabVisibilityWithTab();
        updateFabCountdownText();
    }

    private void updateFabCountdownText() {
        if (fabUpcoming.getVisibility() != View.VISIBLE || nextStartAt == null) return;

        long now = System.currentTimeMillis();
        long diff = nextStartAt.getTime() - now;

        if (diff <= 0) {
            fabUpcoming.setText("00:00");
            return;
        }

        long totalMin = diff / 60000L;
        long days = totalMin / (24 * 60);
        long hours = (totalMin % (24 * 60)) / 60;
        long minutes = totalMin % 60;

        String text;
        if (days > 0) {
            // örn: 2g 05:07
            text = String.format(Locale.getDefault(), "%dg %02d:%02d", days, hours, minutes);
        } else {
            // örn: 05:07
            text = String.format(Locale.getDefault(), "%02d:%02d", hours, minutes);
        }

        fabUpcoming.setText(text);
    }

    private static class UpcomingAdapter extends RecyclerView.Adapter<UpcomingAdapter.VH> {
        interface TeacherNameProvider { void get(String teacherId, java.util.function.Consumer<String> cb); }
        interface OnJoin { void run(UpcomingItem u, View source); }

        private final List<UpcomingItem> data;
        private final TeacherNameProvider nameProvider;
        private final OnJoin onJoin;

        UpcomingAdapter(List<UpcomingItem> data, TeacherNameProvider nameProvider, OnJoin onJoin) {
            this.data = data; this.nameProvider = nameProvider; this.onJoin = onJoin;
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
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_upcoming, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            UpcomingItem u = data.get(pos);

            String subj = (u.subjectName != null && !u.subjectName.isEmpty()) ? u.subjectName : "Ders";
            h.tvTitle.setText(subj);

            nameProvider.get(u.teacherId, name -> {
                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                if (name != null && !name.isEmpty()) {
                    h.tvTitle.setText(subj + " • " + name);
                } else {
                    h.tvTitle.setText(subj);
                }
            });

            if (u.startAt != null) {
                DateFormat df = android.text.format.DateFormat.getMediumDateFormat(h.itemView.getContext());
                DateFormat tf = android.text.format.DateFormat.getTimeFormat(h.itemView.getContext());
                h.tvWhen.setText(df.format(u.startAt) + " • " + tf.format(u.startAt));
            } else h.tvWhen.setText("");

            // Countdown → chipCountdown
            long now = System.currentTimeMillis();
            String countdownText = "00:00";
            if (u.startAt != null) {
                long diff = u.startAt.getTime() - now;
                if (diff > 0) {
                    long totalMin = diff / 60000L;
                    long days = totalMin / (24 * 60);
                    long hours = (totalMin % (24 * 60)) / 60;
                    long minutes = totalMin % 60;
                    countdownText = (days > 0)
                            ? String.format(Locale.getDefault(), "%dg %02d:%02d", days, hours, minutes)
                            : String.format(Locale.getDefault(), "%02d:%02d", hours, minutes);
                }
            }
            h.chipCountdown.setText(countdownText);

            boolean canJoin = false;
            if (u.startAt != null && u.endAt != null) {
                long openAt  = u.startAt.getTime() - 5 * 60 * 1000;
                long closeAt = u.endAt.getTime()   + 10 * 60 * 1000;
                canJoin = now >= openAt && now <= closeAt;
            }
            h.btnJoin.setEnabled(canJoin);
            if (canJoin) {
                h.btnJoin.setBackgroundResource(R.drawable.bg_btn_outline_primary);
                h.btnJoin.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.tutorist_primary));
                h.btnJoin.setOnClickListener(v -> onJoin.run(u, v));
            } else {
                h.btnJoin.setBackgroundResource(R.drawable.bg_btn_outline_primary_disabled);
                h.btnJoin.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.tutorist_onSurfaceVariant));
                h.btnJoin.setOnClickListener(null); // tıklama tamamen kaldırılır
            }
            h.btnJoin.setOnClickListener(v -> onJoin.run(u, v));
        }


        @Override public int getItemCount() { return data.size(); }
    }


}
