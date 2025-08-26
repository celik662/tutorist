package com.example.tutorist.repo;

import static android.content.ContentValues.TAG;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.Arrays;
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
    // yardımcı:
    private static java.util.Date buildDate(String dateIso, int hour) {
        String[] p = dateIso.split("-");
        int y = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]); // 1..12
        int d = Integer.parseInt(p[2]);
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getDefault());
        c.set(java.util.Calendar.YEAR, y);
        c.set(java.util.Calendar.MONTH, m - 1); // 0-based
        c.set(java.util.Calendar.DAY_OF_MONTH, d);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTime();
    }


    // BookingRepo.java
    public Task<Void> createBooking(String teacherId,
                                    String studentId,
                                    String studentName,
                                    String subjectId,
                                    String subjectName,
                                    String dateIso,
                                    int hour) {
        final String id  = slotId(teacherId, dateIso, hour);
        final DocumentReference bRef = bookingDoc(id);
        final DocumentReference lRef = lockDoc(id);

        java.util.Date startAt = buildDate(dateIso, hour);
        java.util.Date endAt   = new java.util.Date(startAt.getTime() + 60L*60L*1000L); // 1 saat

        // ÖNCE map’i oluştur
        Map<String, Object> booking = new HashMap<>();
        booking.put("teacherId", teacherId);
        booking.put("studentId", studentId);
        booking.put("studentName", studentName);   // yeni
        booking.put("subjectId", subjectId);
        booking.put("subjectName", subjectName);   // yeni
        booking.put("date", dateIso);
        booking.put("hour", hour);
        booking.put("status", "pending");
        booking.put("createdAt", FieldValue.serverTimestamp());
        booking.put("updatedAt", FieldValue.serverTimestamp());
        booking.put("startAt", startAt);
        booking.put("endAt",   endAt);

        Map<String, Object> lock = new HashMap<>();
        lock.put("teacherId", teacherId);
        lock.put("studentId", studentId);
        lock.put("date", dateIso);
        lock.put("hour", hour);
        lock.put("status", "pending");
        lock.put("updatedAt", FieldValue.serverTimestamp());

        // Slot çakışması kontrolü (varsa ALREADY_EXISTS fırlatır)
        return lRef.get().continueWithTask(t -> {
            DocumentSnapshot lockSnap = t.getResult();
            if (lockSnap != null && lockSnap.exists()) {
                throw new FirebaseFirestoreException(
                        "Slot already taken", FirebaseFirestoreException.Code.ALREADY_EXISTS);
            }
            WriteBatch batch = db.batch();
            batch.set(bRef, booking);
            batch.set(lRef, lock);
            return batch.commit();
        });
    }




    /** Kolaylık: elinde bookingId varsa direkt */
    public Task<Void> updateStatusById(@NonNull String bookingId, @NonNull String newStatus) {
        Log.d("BookingRepo", "updateStatusById path=bookings/" + bookingId + " newStatus=" + newStatus);

        return db.runTransaction(tr -> {
            // ---------- TÜM OKUMALAR (READS) ----------
            DocumentReference bRef = db.collection("bookings").document(bookingId);
            DocumentSnapshot bSnap = tr.get(bRef); // 1. okuma
            if (!bSnap.exists()) {
                throw new FirebaseFirestoreException(
                        "Booking not found: " + bookingId,
                        FirebaseFirestoreException.Code.NOT_FOUND
                );
            }

            String teacherId = bSnap.getString("teacherId");
            String date      = bSnap.getString("date");
            Long   hourLong  = bSnap.getLong("hour");
            int    hour      = hourLong == null ? -1 : hourLong.intValue();

            DocumentReference lRef = null;
            DocumentSnapshot lSnap = null;
            if (teacherId != null && date != null && hour >= 0) {
                String slotId = teacherId + "_" + date + "_" + hour;
                lRef = db.collection("slotLocks").document(slotId);
                lSnap = tr.get(lRef); // 2. okuma (VARSA)
            }

            // ---------- TÜM YAZMALAR (WRITES) ----------
            Map<String, Object> up = new HashMap<>();
            up.put("status", newStatus);
            up.put("updatedAt", FieldValue.serverTimestamp());

            tr.update(bRef, up);                 // booking yaz
            if (lRef != null && lSnap.exists())  // slotLock varsa yaz
                tr.update(lRef, up);

            return null;
        });
    }

}
