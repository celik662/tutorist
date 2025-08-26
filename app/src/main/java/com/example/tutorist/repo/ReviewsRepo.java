package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.HashMap;
import java.util.Map;

public class ReviewsRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public Task<Void> createTeacherReview(String bookingId, String teacherId,
                                          String studentId, int rating, String comment) {
        DocumentReference ref = db.collection("teacherReviews").document(bookingId); // 1:1
        Map<String, Object> m = new HashMap<>();
        m.put("bookingId", bookingId);
        m.put("teacherId", teacherId);
        m.put("studentId", studentId);
        m.put("rating", rating);
        m.put("comment", comment == null ? "" : comment.trim());
        m.put("createdAt", FieldValue.serverTimestamp());
        return ref.set(m);
    }

    public Query listForTeacher(String teacherId) {
        return db.collection("teacherReviews")
                .whereEqualTo("teacherId", teacherId)
                .orderBy("createdAt", Query.Direction.DESCENDING);
    }
}
