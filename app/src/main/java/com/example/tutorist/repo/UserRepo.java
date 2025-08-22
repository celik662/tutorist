package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class UserRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public Task<Map<String, Object>> loadUser(String uid) {
        return db.collection("users").document(uid).get()
                .continueWith(t -> t.getResult()!=null ? t.getResult().getData() : null);
    }

    public Task<Void> updateUserBasic(String uid, String fullName, String phone) {
        Map<String, Object> m = new HashMap<>();
        m.put("fullName", fullName);
        m.put("phone", phone);
        return db.collection("users").document(uid).set(m, SetOptions.merge());
    }
}
