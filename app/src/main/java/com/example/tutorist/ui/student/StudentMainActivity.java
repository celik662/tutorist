package com.example.tutorist.ui.student;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.tutorist.R;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class StudentMainActivity extends AppCompatActivity {
    private static final String TAG = "StudentMain";
    private ListenerRegistration histReg;
    private BadgeDrawable histBadge;
    private BottomNavigationView nav;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_student_main);

        nav = findViewById(R.id.bottomNav);

        nav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
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
            return true;
        });

        // Rozeti MENÜ ÖĞESİNE bağla
        histBadge = nav.getOrCreateBadge(R.id.nav_history);
        histBadge.setVisible(false);
        histBadge.setMaxCharacterCount(3);

        // Varsayılan sekme
        nav.setSelectedItemId(R.id.nav_lessons);
    }

    @Override protected void onStart() {
        super.onStart();
        subscribeHistoryBadge();
    }

    @Override protected void onStop() {
        super.onStop();
        if (histReg != null) { histReg.remove(); histReg = null; }
    }

    /** Bottom nav rozetini (badge) güncel tut: pending + accepted (gelecek/süren dersler) */
    private void subscribeHistoryBadge() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            Log.w(TAG, "subscribeHistoryBadge: uid null");
            return;
        }
        if (histReg != null) { histReg.remove(); histReg = null; }

        // Yalnız bekleyen + onaylı ve YAKLAŞAN/SÜREN dersler sayılsın:
        // status in ['pending','accepted'] AND startAt >= now
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
                // Hata olsa da rozeti gizle
                if (histBadge != null) {
                    histBadge.setVisible(false);
                }
                return;
            }
            int count = (snap != null) ? snap.size() : 0;
            Log.d(TAG, "badge snapshot ok, count=" + count);

            if (histBadge != null) {
                histBadge.setVisible(count > 0);
                histBadge.setNumber(count);
            }
        });
    }
}
