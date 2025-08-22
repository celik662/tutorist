package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.tutorist.R;
import com.example.tutorist.model.Subject;
import com.google.firebase.firestore.*;

import java.util.*;

public class LessonsFragment extends Fragment {

    private RecyclerView rv;
    private SubjectAdapter adapter;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inf.inflate(R.layout.fragment_lessons, container, false);
        rv = v.findViewById(R.id.rvSubjects);
        rv.setLayoutManager(new GridLayoutManager(getContext(), 3));
        adapter = new SubjectAdapter(s -> {
            // Öğretmen listesine git (senin mevcut activity yolunu koruyorum)
            Intent i = new Intent(getContext(), com.example.tutorist.ui.teacher.TeacherListActivity.class);
            i.putExtra("subjectId", s.id);
            i.putExtra("subjectName", s.nameTR);
            startActivity(i);
        });
        rv.setAdapter(adapter);

        loadSubjects();
        return v;
    }

    private void loadSubjects() {
        db.collection("subjects")
                .whereEqualTo("isActive", true)
                .orderBy("order", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(snap -> applySubjects(snap))
                .addOnFailureListener(e -> {
                    // Çoğunlukla: FAILED_PRECONDITION (index gerekiyor)
                    android.util.Log.e("LessonsFragment", "Subjects query failed", e);
                    // Fallback: orderBy olmadan yeniden dene ki ekran boş kalmasın
                    db.collection("subjects")
                            .whereEqualTo("isActive", true)
                            .get()
                            .addOnSuccessListener(this::applySubjects)
                            .addOnFailureListener(e2 -> {
                                android.util.Log.e("LessonsFragment", "Fallback query failed", e2);
                                android.widget.Toast.makeText(getContext(),
                                        "Dersler yüklenemedi: " + e2.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                            });
                });
    }

    private void applySubjects(QuerySnapshot snap) {
        List<Subject> list = new ArrayList<>();
        for (DocumentSnapshot d : snap) {
            Subject s = d.toObject(Subject.class);
            if (s != null) {
                s.id = d.getId();
                Object hex = d.get("colorHex");
                if (hex != null) s.colorHex = String.valueOf(hex);
                list.add(s);
            }
        }
        adapter.setItems(list);
    }


}
