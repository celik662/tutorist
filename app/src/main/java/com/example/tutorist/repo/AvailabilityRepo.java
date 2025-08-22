package com.example.tutorist.repo;

import com.example.tutorist.model.AvailabilityBlock;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AvailabilityRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public Task<List<AvailabilityBlock>> loadDayBlocks(String uid, int dayOfWeek) {
        return db.collection("availabilities").document(uid).collection("weekly")
                .whereEqualTo("dayOfWeek", dayOfWeek).get()
                .continueWith(t -> {
                    List<AvailabilityBlock> out = new ArrayList<>();
                    if (t.isSuccessful() && t.getResult()!=null) {
                        for (DocumentSnapshot d : t.getResult()) {
                            Integer dow = d.getLong("dayOfWeek")!=null ? d.getLong("dayOfWeek").intValue() : 0;
                            Integer sh  = d.getLong("startHour")!=null ? d.getLong("startHour").intValue() : 0;
                            Integer eh  = d.getLong("endHour")!=null ? d.getLong("endHour").intValue() : 0;
                            out.add(new AvailabilityBlock(d.getId(), dow, sh, eh));
                        }
                    }
                    return out;
                });
    }

    public Task<String> addBlock(String uid, int dayOfWeek, int startHour, int endHour) {
        DocumentReference ref = db.collection("availabilities").document(uid)
                .collection("weekly").document();
        Map<String, Object> data = new HashMap<>();
        data.put("dayOfWeek", dayOfWeek);
        data.put("startHour", startHour);
        data.put("endHour", endHour);
        data.put("createdAt", FieldValue.serverTimestamp());
        return ref.set(data).continueWith(t -> ref.getId());
    }

    public Task<Void> deleteBlock(String uid, String docId) {
        return db.collection("availabilities").document(uid)
                .collection("weekly").document(docId).delete();
    }

    public Task<Void> updateBlock(String uid, String docId, int startHour, int endHour) {
        Map<String, Object> m = new HashMap<>();
        m.put("startHour", startHour);
        m.put("endHour", endHour);
        return db.collection("availabilities").document(uid)
                .collection("weekly").document(docId).update(m);
    }
}
