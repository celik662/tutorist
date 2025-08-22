// app/src/main/java/com/example/tutorist/repo/TeacherProfileRepo.java
package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class TeacherProfileRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private DocumentReference doc(String uid) {
        return db.collection("teacherProfiles").document(uid);
    }

    /** teacherProfiles/{uid}.subjectsMap'i okur: {subjectId -> price} */
    public Task<Map<String, Double>> loadSubjectsMap(String uid) {
        return doc(uid).get().continueWith(t -> {
            Map<String, Double> out = new HashMap<>();
            if (t.isSuccessful() && t.getResult()!=null) {
                Object raw = t.getResult().get("subjectsMap");
                if (raw instanceof Map) {
                    Map<?,?> m = (Map<?,?>) raw;
                    for (Map.Entry<?,?> e : m.entrySet()) {
                        Object k = e.getKey();
                        Object v = e.getValue();
                        if (k != null && v instanceof Number) {
                            out.put(String.valueOf(k), ((Number) v).doubleValue());
                        }
                    }
                }
            }
            return out;
        });
    }

    /** subjectsMap.<subjectId> alanını verilen fiyata ayarlar (merge). */
    public Task<Void> upsertSubjectPrice(String uid, String subjectId, double price) {
        Map<String, Object> data = new HashMap<>();
        data.put("subjectsMap." + subjectId, price);
        data.put("updatedAt", FieldValue.serverTimestamp());
        return doc(uid).set(data, SetOptions.merge());
    }

    /** subjectsMap.<subjectId> alanını siler (merge + FieldValue.delete). */
    public Task<Void> removeSubject(String uid, String subjectId) {
        Map<String, Object> data = new HashMap<>();
        data.put("subjectsMap." + subjectId, FieldValue.delete());
        data.put("updatedAt", FieldValue.serverTimestamp());
        return doc(uid).set(data, SetOptions.merge());
    }

    /** Öğretmenlerin listede görünen adı. Belge yoksa oluşturur. */
    public Task<Void> updateDisplayName(String uid, String displayName) {
        Map<String, Object> data = new HashMap<>();
        data.put("displayName", displayName);
        data.put("updatedAt", FieldValue.serverTimestamp());
        return doc(uid).set(data, SetOptions.merge());
    }
}
