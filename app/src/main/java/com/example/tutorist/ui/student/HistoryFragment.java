package com.example.tutorist.ui.student;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.repo.BookingRepo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

import java.util.*;

public class HistoryFragment extends Fragment {

    private enum Filter { PENDING, HISTORY }

    private RecyclerView rv;
    private ProgressBar progress;
    private TextView empty;
    private RadioGroup rgFilter;
    private RadioButton rbPending, rbHistory;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final BookingRepo bookingRepo = new BookingRepo();
    private ListenerRegistration reg;
    private String uid;

    private Filter currentFilter = Filter.PENDING;

    static class Row {
        String id, teacherId, subjectId, subjectName, status, date;
        int hour;
        Double price;
        String teacherName;
        java.util.Date endAt;
        boolean hasReview;      // öğrenci -> teacherReviews/{bookingId}
        boolean hasTeacherNote; // öğretmen -> teacherNotes/{bookingId}
    }

    private final List<Row> items = new ArrayList<>();
    private final Map<String, String> teacherNameCache = new HashMap<>();
    private final Map<String, Map<String, Double>> teacherPricesCache = new HashMap<>();

    private Adapter adapter;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_history, container, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            Toast.makeText(requireContext(), "Lütfen giriş yapın.", Toast.LENGTH_LONG).show();
            return;
        }

        rv = v.findViewById(R.id.rv);
        progress = v.findViewById(R.id.progress);
        empty = v.findViewById(R.id.empty);
        rgFilter = v.findViewById(R.id.rgFilter);
        rbPending = v.findViewById(R.id.rbPending);
        rbHistory = v.findViewById(R.id.rbHistory);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter(
                items,
                this::onCancelClicked,
                this::teacherNameOf,
                this::priceOf,
                this::onReviewClicked,
                this::onNoteClicked
        );
        rv.setAdapter(adapter);

        // Adapter değiştikçe boş/dolu durumu otomatik güncelle
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { updateEmptyState(); }
            @Override public void onItemRangeInserted(int positionStart, int itemCount) { updateEmptyState(); }
            @Override public void onItemRangeRemoved(int positionStart, int itemCount) { updateEmptyState(); }
        });

        rgFilter.setOnCheckedChangeListener((g, id) -> {
            currentFilter = rbPending.isChecked() ? Filter.PENDING : Filter.HISTORY;
            listen(currentFilter);
        });

        setLoading(true);
    }

    @Override public void onStart() {
        super.onStart();
        if (uid != null) listen(currentFilter);
    }

    @Override public void onStop() {
        if (reg != null) { reg.remove(); reg = null; }
        super.onStop();
    }

    // ---------------- UI yardımcıları ----------------
    private void setLoading(boolean loading) {
        if (!isAdded()) return;
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            rv.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            empty.setText("");
        }
    }

    private void updateEmptyState() {
        if (!isAdded()) return;
        boolean isEmpty = adapter.getItemCount() == 0;
        empty.setText(isEmpty
                ? (currentFilter == Filter.PENDING ? "Bekleyen talebin yok." : "Geçmiş kaydın yok.")
                : "");
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rv.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
    // -------------------------------------------------

    private void listen(Filter filter) {
        if (reg != null) { reg.remove(); reg = null; }
        setLoading(true);

        Query q = db.collection("bookings").whereEqualTo("studentId", uid);
        if (filter == Filter.PENDING) {
            // pending’i olduğu gibi getir (normalize bind aşamasında)
            q = q.whereEqualTo("status", "pending");
        } else {
            // History: pending dışındaki tüm anlamlı durumlar
            q = q.whereIn("status", Adapter.HISTORY_STATUSES_WHEREIN);
        }

        reg = q.addSnapshotListener((snap, e) -> {
            setLoading(false);
            if (!isAdded()) return;
            if (e != null || snap == null) {
                Toast.makeText(requireContext(), e != null ? e.getMessage() : "Hata", Toast.LENGTH_LONG).show();
                return;
            }

            items.clear();
            Set<String> teacherIdsSeen = new HashSet<>();

            for (DocumentSnapshot d : snap.getDocuments()) {
                Row r = new Row();
                r.id         = d.getId();
                r.teacherId  = String.valueOf(d.get("teacherId"));
                r.subjectId  = String.valueOf(d.get("subjectId"));
                Object sn    = d.get("subjectName");
                r.subjectName = (sn != null) ? String.valueOf(sn) : r.subjectId;
                r.status = Adapter.normalizeStatus(String.valueOf(d.get("status")));
                r.date       = String.valueOf(d.get("date"));
                r.hour       = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;

                Object ts = d.get("endAt");
                if (ts instanceof com.google.firebase.Timestamp)
                    r.endAt = ((com.google.firebase.Timestamp) ts).toDate();
                else if (ts instanceof java.util.Date)
                    r.endAt = (java.util.Date) ts;

                if ("pending".equals(r.status) && r.endAt != null && new java.util.Date().after(r.endAt)) {
                    // DB’de saklamasan bile, UI tarafında expired gibi renklendir
                    r.status = "expired";
                }

                r.teacherName = teacherNameOf(r.teacherId);
                r.price       = priceOf(r.teacherId, r.subjectId);

                r.hasReview = false;
                r.hasTeacherNote = false;

                checkReviewExists(r);       // teacherReviews/{bookingId}
                checkTeacherNoteExists(r);  // teacherNotes/{bookingId}

                teacherIdsSeen.add(r.teacherId);
                items.add(r);
            }

            // Sıralama: önce tarihe göre; varsa endAt geçmiş için daha iyi bir sinyal
            items.sort((a,b) -> {
                // endAt varsa ona göre, yoksa date+hour
                if (a.endAt != null && b.endAt != null) return a.endAt.compareTo(b.endAt);
                if (a.endAt != null) return -1;
                if (b.endAt != null) return 1;
                int c = String.valueOf(a.date).compareTo(String.valueOf(b.date));
                if (c != 0) return c;
                return Integer.compare(a.hour, b.hour);
            });

            adapter.notifyDataSetChanged();
            updateEmptyState();

            for (String tid : teacherIdsSeen) ensureTeacherProfileLoaded(tid);
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void checkReviewExists(Row r) {
        db.collection("teacherReviews").document(r.id).get()
                .addOnSuccessListener(ds -> {
                    r.hasReview = ds.exists();
                    if (isAdded()) adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> { /* yok say */ });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void checkTeacherNoteExists(Row r) {
        db.collection("teacherNotes").document(r.id).get()
                .addOnSuccessListener(ds -> {
                    r.hasTeacherNote = ds.exists();
                    if (isAdded()) adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> { /* yok say */ });
    }

    private void ensureTeacherProfileLoaded(String teacherId) {
        if (teacherNameCache.containsKey(teacherId) && teacherPricesCache.containsKey(teacherId)) return;

        db.collection("teacherProfiles").document(teacherId).get()
                .addOnSuccessListener(doc -> {
                    if (!isAdded()) return;
                    if (doc != null && doc.exists()) {
                        String name = null;
                        Object dn = doc.get("displayName");
                        if (dn == null) dn = doc.get("fullName");
                        if (dn != null) name = String.valueOf(dn);
                        teacherNameCache.put(teacherId, (name != null && !name.isEmpty()) ? name : teacherId);

                        Map<String, Double> map = new HashMap<>();
                        Object raw = doc.get("subjectsMap");
                        if (raw instanceof Map) {
                            Map<?,?> m = (Map<?,?>) raw;
                            for (Map.Entry<?,?> en : m.entrySet()) {
                                Object k = en.getKey(), v = en.getValue();
                                if (k != null && v instanceof Number) {
                                    map.put(String.valueOf(k), ((Number)v).doubleValue());
                                }
                            }
                        }
                        teacherPricesCache.put(teacherId, map);
                    } else {
                        teacherNameCache.put(teacherId, teacherId);
                        teacherPricesCache.put(teacherId, Collections.emptyMap());
                    }
                    if (isAdded()) adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(err -> {
                    teacherNameCache.put(teacherId, teacherId);
                    teacherPricesCache.put(teacherId, Collections.emptyMap());
                    if (isAdded()) adapter.notifyDataSetChanged();
                });
    }

    private String teacherNameOf(String teacherId) {
        String n = teacherNameCache.get(teacherId);
        return n != null ? n : teacherId;
    }

    private Double priceOf(String teacherId, String subjectId) {
        Map<String, Double> m = teacherPricesCache.get(teacherId);
        return m != null ? m.get(subjectId) : null;
    }

    private void onCancelClicked(Row r) {
        if (!"pending".equalsIgnoreCase(r.status)) return;
        bookingRepo.updateStatusById(r.id, "cancelled")
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), "İptal hatası: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void onReviewClicked(Row r) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_teacher_review, null, false);
        RatingBar ratingBar = view.findViewById(R.id.ratingBar);
        EditText et = view.findViewById(R.id.etReview);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Öğretmeni Değerlendir")
                .setView(view)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Gönder", (d, w) -> {
                    int rating = Math.max(1, Math.min(5, Math.round(ratingBar.getRating())));
                    String review = et.getText().toString().trim();

                    Map<String, Object> data = new HashMap<>();
                    data.put("bookingId", r.id);
                    data.put("teacherId", r.teacherId);
                    data.put("studentId", uid);
                    data.put("rating", rating);
                    data.put("comment", et.getText().toString().trim()); // <-- review değil, comment
                    data.put("createdAt", FieldValue.serverTimestamp());

                    db.collection("teacherReviews").document(r.id)
                            .set(data, SetOptions.merge())
                            .addOnSuccessListener(v -> {
                                Toast.makeText(requireContext(), "Teşekkürler! Değerlendirmen kaydedildi.", Toast.LENGTH_SHORT).show();
                                r.hasReview = true;
                                if (isAdded()) adapter.notifyDataSetChanged();

                                // İSTEĞE BAĞLI: Öğretmen profil istatistiklerini güncelle
                                // updateTeacherRatingAgg(r.teacherId, rating);
                            })
                            .addOnFailureListener(e ->
                                    Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .show();
    }

    @SuppressWarnings("ConstantConditions")
    private void updateTeacherRatingAgg(String teacherId, int newRating){
        DocumentReference ref = db.collection("teacherProfiles").document(teacherId);
        db.runTransaction(tr -> {
            DocumentSnapshot snap = tr.get(ref);
            long count = 0L; double sum = 0.0;
            if (snap.exists()) {
                Number c = (Number) snap.get("ratingCount");
                Number s = (Number) snap.get("ratingSum");
                count = c != null ? c.longValue() : 0L;
                sum   = s != null ? s.doubleValue() : 0.0;
            }
            count += 1;
            sum   += newRating;
            Map<String,Object> up = new HashMap<>();
            up.put("ratingCount", count);
            up.put("ratingSum", sum);
            up.put("avgRating", sum / Math.max(1, count));
            tr.set(ref, up, SetOptions.merge());
            return null;
        });
    }

    private void onNoteClicked(Row r) {
        db.collection("teacherNotes").document(r.id).get()
                .addOnSuccessListener(doc -> {
                    if (doc == null || !doc.exists()) {
                        Toast.makeText(requireContext(), "Öğretmen notu bulunamadı.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String note = String.valueOf(doc.get("note"));

                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Öğretmen Notu")
                            .setMessage(note != null ? note : "")
                            .setPositiveButton("Kapat", null)
                            .show();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show());
    }


    // ---------------- Adapter ----------------
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface OnCancel { void run(Row r); }
        interface NameProvider { String apply(String teacherId); }
        interface PriceProvider { Double apply(String teacherId, String subjectId); }
        interface OnReview { void run(Row r); }
        interface OnNote { void run(Row r); }

        private final List<Row> items;
        private final OnCancel onCancel;
        private final NameProvider nameProvider;
        private final PriceProvider priceProvider;
        private final OnReview onReview;
        private final OnNote onNote;

        Adapter(List<Row> items, OnCancel onCancel, NameProvider nameProvider,
                PriceProvider priceProvider, OnReview onReview, OnNote onNote) {
            this.items = items;
            this.onCancel = onCancel;
            this.nameProvider = nameProvider;
            this.priceProvider = priceProvider;
            this.onReview = onReview;
            this.onNote = onNote;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv1, tv2, tvStatus;
            Button btnCancel, btnReview;
            ImageView ivNote; // opsiyonel (layout'a eklersen görünür olur)

            VH(View v){
                super(v);
                tv1 = v.findViewById(R.id.tvLine1);
                tv2 = v.findViewById(R.id.tvLine2);
                tvStatus = v.findViewById(R.id.tvStatus);
                btnCancel = v.findViewById(R.id.btnCancel);
                btnReview = v.findViewById(R.id.btnReview);
                ivNote = v.findViewById(R.id.ivNote); // item_student_booking'e eklersen bağlanır
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_student_booking, p, false);
            return new VH(v);
        }

        @SuppressLint("SetTextI18n")
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Row r = items.get(pos);

            String teacherName = nameProvider.apply(r.teacherId);
            Double price = priceProvider.apply(r.teacherId, r.subjectId);

            h.tv1.setText(teacherName + " • " + (r.subjectName != null ? r.subjectName : r.subjectId));

            String priceStr = (price != null) ? String.format(Locale.getDefault(),"₺%.0f", price) : "";
            String dt = String.format(Locale.getDefault(), "%s %02d:00", r.date, r.hour);

            // Status metin/renk
            StatusUi ui = statusUi(h.itemView, r.status);
            String line = String.format(Locale.getDefault(), "%s  %s", dt, priceStr);
            SpannableString ss = new SpannableString(line);
            int idx = line.lastIndexOf(ui.label);
            if (idx >= 0) {
                ss.setSpan(new ForegroundColorSpan(ui.inlineColor), idx, idx + ui.label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + ui.label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            h.tv2.setText(ss);

            if (h.tvStatus != null) {
                h.tvStatus.setText(ui.label);
                h.tvStatus.setBackgroundResource(ui.chipBgRes);
                h.tvStatus.setTextColor(ContextCompat.getColor(h.itemView.getContext(), ui.chipTextColor));
            }

            boolean canReview = r.endAt != null
                    && new java.util.Date().after(r.endAt)
                    && ( "accepted".equalsIgnoreCase(r.status)
                    || "completed".equalsIgnoreCase(r.status) )
                    && !r.hasReview;

            boolean cancellable = "pending".equalsIgnoreCase(r.status);
            h.btnCancel.setVisibility(cancellable ? View.VISIBLE : View.GONE);
            h.btnCancel.setOnClickListener(v -> onCancel.run(r));

            h.btnReview.setVisibility(canReview ? View.VISIBLE : View.GONE);
            h.btnReview.setOnClickListener(v -> {
                h.btnReview.setEnabled(false); // çift tık önle
                onReview.run(r);
                h.btnReview.setEnabled(true);
            });

            // Öğretmen notu: info ikonu ve satır tıklaması
            boolean showNote = r.hasTeacherNote
                    && r.endAt != null
                    && new java.util.Date().after(r.endAt); // sadece geçmiş

            if (h.ivNote != null) {
                h.ivNote.setVisibility(showNote ? View.VISIBLE : View.GONE);
                h.ivNote.setOnClickListener(v -> { if (showNote) onNote.run(r); });
            }
            // Tüm satır tıklaması da notu açsın (varsa)
            h.itemView.setOnClickListener(v -> { if (showNote) onNote.run(r); });
        }

        private static class StatusUi {
            final String label;
            final int inlineColor;     // tv2 içindeki renk
            final int chipBgRes;       // tvStatus arka plan drawable
            final int chipTextColor;   // tvStatus yazı rengi
            StatusUi(String l, int inline, int bgRes, int chipText){
                label = l; inlineColor = inline; chipBgRes = bgRes; chipTextColor = chipText;
            }
        }


        /** Firestore’dan gelebilecek muhtemel haller → kanonik isim */
        private static String normalizeStatus(String raw) {
            if (raw == null) return "pending";
            String s = raw.trim().toLowerCase(Locale.ROOT);

            // eşanlamlılar
            if (s.equals("canceled")) s = "cancelled";
            if (s.equals("rejected") || s.equals("denied")) s = "declined";
            if (s.equals("teacher_rejected") || s.equals("declined_by_teacher")) s = "teacher_declined";
            if (s.equals("teacher_canceled") || s.equals("teacher_cancelled")) s = "teacher_cancelled";
            if (s.equals("student_canceled") || s.equals("student_cancelled")) s = "student_cancelled";
            if (s.equals("done") || s.equals("finished")) s = "completed";

            // kanonik set
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

        /** History sekmesinde göstermek istediğimiz (pending harici) ham status değerleri.
         *  whereIn max 10 öğe → tam 10 olacak şekilde seçtik. */
        private static final List<String> HISTORY_STATUSES_WHEREIN = Arrays.asList(
                "accepted","completed","declined","teacher_declined",
                "cancelled","canceled","student_cancelled","teacher_cancelled",
                "expired","no_show"
        );

        private StatusUi statusUi(View v, String statusRaw) {
            String s = normalizeStatus(statusRaw);
            android.content.Context c = v.getContext();
            switch (s) {
                case "accepted":
                    return new StatusUi("Onaylandı",
                            ContextCompat.getColor(c, R.color.status_accepted),
                            R.drawable.bg_status_completed,
                            R.color.status_accepted);

                case "completed":
                    return new StatusUi("Tamamlandı",
                            ContextCompat.getColor(c, R.color.status_completed),
                            R.drawable.bg_status_completed,
                            R.color.status_completed);

                case "declined":
                    return new StatusUi("Reddedildi",
                            ContextCompat.getColor(c, R.color.status_declined),
                            R.drawable.bg_status_canceled,
                            R.color.status_declined);

                case "teacher_declined":
                    return new StatusUi("Öğretmen reddetti",
                            ContextCompat.getColor(c, R.color.status_declined),
                            R.drawable.bg_status_canceled,
                            R.color.status_declined);

                case "cancelled":
                case "student_cancelled":
                    return new StatusUi("Öğrenci iptal etti",
                            ContextCompat.getColor(c, R.color.status_cancelled),
                            R.drawable.bg_status_canceled,
                            R.color.status_cancelled);

                case "teacher_cancelled":
                    return new StatusUi("Öğretmen iptal etti",
                            ContextCompat.getColor(c, R.color.status_cancelled),
                            R.drawable.bg_status_canceled,
                            R.color.status_cancelled);

                case "expired":
                    return new StatusUi("Süresi doldu",
                            ContextCompat.getColor(c, R.color.status_expired),
                            R.drawable.bg_status_warning,
                            R.color.status_expired);

                case "no_show":
                    return new StatusUi("Katılım yok",
                            ContextCompat.getColor(c, R.color.status_no_show),
                            R.drawable.bg_status_warning,
                            R.color.status_no_show);

                case "pending":
                default:
                    return new StatusUi("Beklemede",
                            ContextCompat.getColor(c, R.color.status_pending),
                            R.drawable.bg_status_pending,
                            R.color.tutorist_onPrimaryContainer);
            }
        }



        @Override public int getItemCount(){ return items.size(); }
    }
}
