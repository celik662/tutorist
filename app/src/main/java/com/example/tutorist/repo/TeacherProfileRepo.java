// app/src/main/java/com/example/tutorist/repo/TeacherProfileRepo.java
package com.example.tutorist.repo;

import android.util.Log;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class TeacherProfileRepo {
    private static final String TAG = "TeacherProfileRepo";
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private DocumentReference doc(String uid) { return db.collection("teacherProfiles").document(uid); }

    /** Map<subjectId, price> */
    public Task<Map<String, Double>> loadSubjectsMap(String uid) {
        return doc(uid).get().continueWith(t -> {
            Map<String, Double> out = new HashMap<>();
            DocumentSnapshot s = t.getResult();
            if (s != null && s.exists()) {
                // 1) Nested map: subjectsMap: { english: 123, math: 78 }
                Object raw = s.get("subjectsMap");
                if (raw instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) raw;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        Object k = e.getKey();
                        Object v = e.getValue();
                        if (k != null && v instanceof Number) {
                            out.put(String.valueOf(k), ((Number) v).doubleValue());
                        }
                    }
                }
                // 2) Eski/dotted alanlar için geriye dönük okuma: subjectsMap.english = 123
                Map<String, Object> data = s.getData();
                if (data != null) {
                    for (Map.Entry<String, Object> e : data.entrySet()) {
                        String k = e.getKey();
                        Object v = e.getValue();
                        if (k != null && k.startsWith("subjectsMap.") && v instanceof Number) {
                            out.put(k.substring("subjectsMap.".length()), ((Number) v).doubleValue());
                        }
                    }
                }
            }
            return out;
        });
    }

    /** Ekle/Güncelle (nested map olarak yazar) */
    public Task<Void> upsertSubjectPrice(String uid, String subjectId, double price) {
        Map<String, Object> nested = new HashMap<>();
        nested.put(subjectId, price);

        Map<String, Object> data = new HashMap<>();
        data.put("subjectsMap", nested);
        data.put("updatedAt", FieldValue.serverTimestamp());
        return doc(uid).set(data, SetOptions.merge());
    }

    /** Sil (hem nested path hem de eski dotted path için güvenli) */
    public Task<Void> removeSubject(String uid, String subjectId) {
        DocumentReference r = doc(uid);
        WriteBatch b = db.batch();

        // 1) DOĞRU nested map anahtarı (yeni yazımlar için)
        b.update(r, FieldPath.of("subjectsMap", subjectId), FieldValue.delete());

        // 2) LİTERAL noktalı alan (eski veriler için)  ← KRİTİK
        b.update(r, FieldPath.of("subjectsMap." + subjectId), FieldValue.delete());

        // (Not: "subjectsMap."+subjectId şeklinde string path kullanmak nested sayılır; işe yaramaz.
        // Bu yüzden mutlaka FieldPath.of(...) ile TEK SEGMENT veriyoruz.)

        b.update(r, "updatedAt", FieldValue.serverTimestamp());
        return b.commit();
    }

    public Task<Void> updateDisplayName(String uid, String displayName) {
        Map<String, Object> data = new HashMap<>();
        data.put("displayName", displayName);
        data.put("updatedAt", FieldValue.serverTimestamp());
        return doc(uid).set(data, SetOptions.merge());
    }
}
