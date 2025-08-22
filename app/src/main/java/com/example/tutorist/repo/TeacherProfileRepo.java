// app/src/main/java/com/example/tutorist/repo/TeacherProfileRepo.java
package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class TeacherProfileRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();


    public Task<Void> upsertSubjectPrice(String uid, String subjectId, double price) {
        DocumentReference ref = db.collection("teacherProfiles").document(uid);
        Map<String, Object> nested = new HashMap<>();
        nested.put(subjectId, price);
        Map<String, Object> root = new HashMap<>();
        root.put("subjectsMap", nested);
        return ref.set(root, SetOptions.merge());
    }


    public Task<Void> removeSubject(String uid, String subjectId) {
        DocumentReference ref = db.collection("teacherProfiles").document(uid);
        Map<String, Object> upd = new HashMap<>();
        upd.put("subjectsMap."+subjectId, FieldValue.delete());
        return ref.update(upd);
    }

    public Task<Void> updateDisplayName(String uid, String displayName) {
        // DİKKAT: merge zorunlu. Aksi halde subjectsMap silinir → "yarım saniye görünüp kaybolma".
        DocumentReference ref = db.collection("teacherProfiles").document(uid);
        Map<String, Object> m = new HashMap<>();
        m.put("displayName", displayName);
        return ref.set(m, SetOptions.merge());
    }
}
