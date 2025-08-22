// app/src/main/java/com/example/tutorist/repo/SubjectsRepo.java
package com.example.tutorist.repo;

import com.example.tutorist.model.Subject;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.*;

import java.util.*;

public class SubjectsRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public Task<List<Subject>> loadActiveSubjects() {
        return db.collection("subjects")
                .whereEqualTo("isActive", true)
                .get()
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) {
                        return Tasks.forException(task.getException()!=null
                                ? task.getException() : new RuntimeException("subjects query failed"));
                    }
                    List<Subject> out = new ArrayList<>();
                    for (DocumentSnapshot d : task.getResult()) {
                        Subject s = new Subject();
                        s.id = d.getId();
                        Object n = d.get("nameTR"); if (n!=null) s.nameTR = String.valueOf(n);
                        Object c = d.get("colorHex"); if (c!=null) s.colorHex = String.valueOf(c);
                        Object o = d.get("order"); if (o instanceof Number) s.order = ((Number)o).intValue();
                        out.add(s);
                    }
                    out.sort((a,b)->{
                        int ao = a.order==null? Integer.MAX_VALUE: a.order;
                        int bo = b.order==null? Integer.MAX_VALUE: b.order;
                        if (ao!=bo) return Integer.compare(ao,bo);
                        String an = a.nameTR==null? "":a.nameTR;
                        String bn = b.nameTR==null? "":b.nameTR;
                        return an.compareToIgnoreCase(bn);
                    });
                    return Tasks.forResult(out);
                });
    }
}
