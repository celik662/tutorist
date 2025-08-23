// app/src/main/java/com/example/tutorist/repo/BookingRepo.java
package com.example.tutorist.repo;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.HashMap;
import java.util.Map;

public class BookingRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private DocumentReference doc(String id) {
        return db.collection("bookings").document(id);
    }

    public static String slotId(String teacherId, String dateIso, int hour) {
        return teacherId + "_" + dateIso + "_" + hour;
    }

    // BookingRepo.java (create kısmı)
    // BookingRepo.java
    public Task<Void> createBooking(String teacherId, String studentId, String subjectId,
                                    String dateIso, int hour) {
        String id = slotId(teacherId, dateIso, hour);
        DocumentReference dr = db.collection("bookings").document(id);

        Map<String, Object> data = new HashMap<>();
        data.put("teacherId", teacherId);
        data.put("studentId", studentId);
        data.put("subjectId", subjectId);
        data.put("date", dateIso);
        data.put("hour", hour);
        data.put("status", "pending");
        data.put("createdAt", FieldValue.serverTimestamp());
        data.put("updatedAt", FieldValue.serverTimestamp());

        Log.d("BOOK", "create try id="+id+" uid="+FirebaseAuth.getInstance().getUid()+" payload="+data);

        // set() -> belge yoksa "create" sayılır ve kuraldaki !exists koşuluna takılır.
        return dr.set(data).addOnFailureListener(e -> {
            if (e instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException fe = (FirebaseFirestoreException) e;
                Log.e("BOOK", "create FAIL code="+fe.getCode()+" msg="+fe.getMessage(), fe);
            } else {
                Log.e("BOOK", "create FAIL", e);
            }
        });
    }



}
