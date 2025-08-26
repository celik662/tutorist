package com.example.tutorist.ui.student;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.example.tutorist.R;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.List;

public class StudentMainActivity extends AppCompatActivity {
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
            if (id == R.id.nav_lessons)      f = new LessonsFragment();
            else if (id == R.id.nav_history) f = new HistoryFragment();
            else                              f = new StudentProfileFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f).commit();
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

    private void subscribeHistoryBadge() {
        String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        if (histReg != null) { histReg.remove(); histReg = null; }

        histReg = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("bookings")
                .whereEqualTo("studentId", uid)
                .whereIn("status", List.of("pending"))
                .addSnapshotListener((snap, e) -> {
                    int count = (snap != null) ? snap.size() : 0;
                    histBadge.setVisible(count > 0);
                    histBadge.setNumber(count);
                });
    }
}
