// app/src/main/java/com/example/tutorist/repo/BookingRepo.java
package com.example.tutorist.repo;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.*;

import java.util.*;

public class BookingRepo {

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /** Aynı slotu tekil kimlikle temsil etmek için */
    public static String slotId(String teacherId, String dateIso, int hour) {
        return teacherId + "_" + dateIso + "_" + hour;
    }

    private DocumentReference bookingRef(String bookingId) {
        return db.collection("bookings").document(bookingId);
    }

    private DocumentReference lockRef(String teacherId, String dateIso, int hour) {
        return db.collection("slotLocks").document(slotId(teacherId, dateIso, hour));
    }

    /**
     * Slotu atomik olarak kilitle + booking oluştur.
     * DOLULUK kontrolü SADECE slotLocks üstünden yapılır.
     * - lock.status in [pending, accepted] ise: “dolu” hatası at.
     * - aksi halde lock=pending + bookings/{id}=pending yaz.
     */
    public Task<Void> createBooking(
            String teacherId,
            String studentId,
            String studentName,
            String subjectId,
            String subjectName,
            String dateIso,   // yyyy-MM-dd
            int hour          // 0..23
    ) {
        final String bookingId = slotId(teacherId, dateIso, hour);
        final DocumentReference bRef = bookingRef(bookingId);
        final DocumentReference lRef = lockRef(teacherId, dateIso, hour);

        return db.runTransaction(tr -> {
            // 1) Mevcut LOCK var mı?
            DocumentSnapshot l = tr.get(lRef);
            if (l.exists()) {
                String st = String.valueOf(l.get("status"));
                if ("pending".equals(st) || "accepted".equals(st)) {
                    throw new IllegalStateException("Bu saat zaten dolu.");
                }
            }

            // 2) LOCK oluştur/aktif et
            Map<String, Object> lock = new HashMap<>();
            lock.put("teacherId", teacherId);
            lock.put("date", dateIso);
            lock.put("hour", hour);
            lock.put("status", "pending"); // ödeme & onay süreci
            lock.put("updatedAt", FieldValue.serverTimestamp());
            tr.set(lRef, lock, SetOptions.merge());

            // 3) BOOKING yaz (pending)
            // startAt & endAt
            String[] p = dateIso.split("-");
            int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]) - 1, d = Integer.parseInt(p[2]);
            Calendar c = Calendar.getInstance();
            c.set(Calendar.MILLISECOND, 0);
            c.set(y, m, d, hour, 0, 0);
            Date startAt = c.getTime();
            c.add(Calendar.HOUR_OF_DAY, 1);
            Date endAt = c.getTime();

            Map<String, Object> b = new HashMap<>();
            b.put("teacherId", teacherId);
            b.put("studentId", studentId);
            b.put("studentName", studentName != null ? studentName : "");
            b.put("subjectId", subjectId != null ? subjectId : "");
            b.put("subjectName", subjectName != null ? subjectName : "");
            b.put("date", dateIso);
            b.put("hour", hour);
            b.put("status", "pending");
            b.put("startAt", startAt);
            b.put("endAt", endAt);
            b.put("createdAt", FieldValue.serverTimestamp());
            b.put("updatedAt", FieldValue.serverTimestamp());

            tr.set(bRef, b, SetOptions.merge());

            return null;
        });
    }

    /**
     * Booking durumunu güncelle.
     * - Dokümanı ASLA silme (geçmiş görünür kalsın)
     * - İptal/Red gibi durumlarda LOCK'ı kaldır (slot yeniden açılır)
     */
    public Task<Void> updateStatusById(String bookingId, String newStatus) {
        final DocumentReference bRef = bookingRef(bookingId);

        return db.runTransaction(tr -> {
            DocumentSnapshot b = tr.get(bRef);
            if (!b.exists()) return null;

            String teacherId = b.getString("teacherId");
            String dateIso   = b.getString("date");
            Number hourN     = (Number) b.get("hour");
            int hour         = hourN != null ? hourN.intValue() : -1;

            Map<String, Object> up = new HashMap<>();
            up.put("status", normalizeStatus(newStatus));
            up.put("updatedAt", FieldValue.serverTimestamp());
            tr.set(bRef, up, SetOptions.merge());

            // Kilidi serbest bırakılacak durumlar
            String s = String.valueOf(up.get("status"));
            if (Arrays.asList("cancelled","student_cancelled","declined","teacher_declined").contains(s)) {
                if (teacherId != null && dateIso != null && hour >= 0) {
                    tr.delete(lockRef(teacherId, dateIso, hour));
                }
            }

            return null;
        });
    }

    // Aynı normalize mantığını basitçe burada da tutalım
    private static String normalizeStatus(String raw) {
        if (raw == null) return "pending";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("canceled")) s = "cancelled";
        if (s.equals("rejected") || s.equals("denied")) s = "declined";
        if (s.equals("teacher_rejected") || s.equals("declined_by_teacher")) s = "teacher_declined";
        if (s.equals("teacher_canceled") || s.equals("teacher_cancelled")) s = "teacher_cancelled";
        if (s.equals("student_canceled") || s.equals("student_cancelled")) s = "student_cancelled";
        if (s.equals("done") || s.equals("finished")) s = "completed";

        switch (s) {
            case "pending":
            case "accepted":
            case "declined":
            case "teacher_declined":
            case "cancelled":
            case "student_cancelled":
            case "teacher_cancelled":
            case "completed":
            case "expired":
            case "no_show":
                return s;
            default:
                return "pending";
        }
    }
}
