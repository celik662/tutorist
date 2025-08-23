// app/src/main/java/com/example/tutorist/repo/BookingRepo.java
package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class BookingRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private DocumentReference doc(String id) {
        return db.collection("bookings").document(id);
    }

    public static String slotId(String teacherId, String dateIso, int hour) {
        return teacherId + "_" + dateIso + "_" + hour; // ör: rAst..._2025-08-27_9
    }

    /** ÇAKIŞMASIZ create: ilk commit kazanır, ikincisi "slot dolu" ile düşer */
    public Task<Void> createBooking(String teacherId, String studentId, String subjectId,
                                    String dateIso, int hour) {
        String id = slotId(teacherId, dateIso, hour);
        DocumentReference ref = doc(id);

        Map<String, Object> data = new HashMap<>();
        data.put("teacherId", teacherId);
        data.put("studentId", studentId);
        data.put("subjectId", subjectId);
        data.put("date", dateIso);
        data.put("hour", hour);
        data.put("status", "pending");
        data.put("createdAt", FieldValue.serverTimestamp());
        data.put("updatedAt", FieldValue.serverTimestamp());

        return db.runTransaction(tr -> {
            DocumentSnapshot snap = tr.get(ref);
            if (snap.exists()) {
                // Aynı slot zaten alınmış -> çatışma
                throw new FirebaseFirestoreException(
                        "Slot already taken",
                        FirebaseFirestoreException.Code.ABORTED
                );
            }
            tr.set(ref, data); // create
            return null;
        });
    }

    // --- Status güncellemeleri ---
    public Task<Void> accept(String bookingId) {
        return doc(bookingId).update(
                "status", "accepted",
                "updatedAt", FieldValue.serverTimestamp()
        );
    }

    public Task<Void> decline(String bookingId) {
        return doc(bookingId).update(
                "status", "declined",
                "updatedAt", FieldValue.serverTimestamp()
        );
    }

    /** Yalnızca pending iken öğrencinin iptali; kurallar zaten zorlar. */
    public Task<Void> cancelByStudent(String bookingId) {
        return doc(bookingId).update(
                "status", "cancelled",
                "updatedAt", FieldValue.serverTimestamp()
        );
    }
}
