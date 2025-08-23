// app/src/main/java/com/example/tutorist/repo/BookingRepo.java
package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class BookingRepo {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public static String slotId(String teacherId, String dateIso, int hour) {
        return teacherId + "_" + dateIso + "_" + hour;
    }

    private DocumentReference bookingDoc(String id){
        return db.collection("bookings").document(id);
    }
    private DocumentReference lockDoc(String id){
        return db.collection("slotLocks").document(id);
    }

    // BookingRepo.java
// BookingRepo.java
    public Task<Void> createBooking(String teacherId, String studentId, String subjectId,
                                    String dateIso, int hour) {
        final String id  = slotId(teacherId, dateIso, hour);
        final DocumentReference bRef = bookingDoc(id);
        final DocumentReference lRef = lockDoc(id);

        Map<String, Object> booking = new HashMap<>();
        booking.put("teacherId", teacherId);
        booking.put("studentId", studentId);
        booking.put("subjectId", subjectId);
        booking.put("date", dateIso);
        booking.put("hour", hour);
        booking.put("status", "pending");
        booking.put("createdAt", FieldValue.serverTimestamp());
        booking.put("updatedAt", FieldValue.serverTimestamp());

        Map<String, Object> lock = new HashMap<>();
        lock.put("teacherId", teacherId);
        lock.put("studentId", studentId);
        lock.put("date", dateIso);
        lock.put("hour", hour);
        lock.put("status", "pending");
        lock.put("updatedAt", FieldValue.serverTimestamp());

        // Sadece slotLocks'a bak (varsa dolu say)
        return lRef.get().continueWithTask(t -> {
            DocumentSnapshot lockSnap = t.getResult();
            if (lockSnap != null && lockSnap.exists()) {
                throw new FirebaseFirestoreException(
                        "Slot already taken", FirebaseFirestoreException.Code.ALREADY_EXISTS);
            }
            WriteBatch batch = db.batch();
            batch.set(bRef, booking);  // kurallar create + !exists ile koruyor
            batch.set(lRef, lock);     // kurallar create + !exists ile koruyor
            return batch.commit();
        });
    }




}
