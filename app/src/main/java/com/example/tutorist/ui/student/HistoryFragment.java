package com.example.tutorist.ui.student;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    static class Row {
        String id, teacherId, subjectId, subjectName, status, date;
        int hour;
        Double price;
        String teacherName;
        java.util.Date endAt;
        boolean hasReview;
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
                this::onReviewClicked   // <-- yeni callback
        );
        rv.setAdapter(adapter);

        rgFilter.setOnCheckedChangeListener((g, id) -> {
            Filter f = rbPending.isChecked() ? Filter.PENDING : Filter.HISTORY;
            listen(f);
        });
    }

    @Override public void onStart() {
        super.onStart();
        if (uid != null) listen(Filter.PENDING);
    }

    @Override public void onStop() {
        if (reg != null) { reg.remove(); reg = null; }
        super.onStop();
    }

    private void listen(Filter filter) {
        if (reg != null) { reg.remove(); reg = null; }
        progress.setVisibility(View.VISIBLE);
        empty.setText("");

        Query q = db.collection("bookings").whereEqualTo("studentId", uid);
        if (filter == Filter.PENDING) {
            q = q.whereEqualTo("status", "pending");
        } else {
            q = q.whereIn("status", Arrays.asList("accepted","declined","cancelled"));
        }

        reg = q.addSnapshotListener((snap, e) -> {
            progress.setVisibility(View.GONE);
            if (!isAdded()) return; // fragment ayrıldıysa
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
                r.status     = String.valueOf(d.get("status"));
                r.date       = String.valueOf(d.get("date"));
                r.hour       = d.getLong("hour") != null ? d.getLong("hour").intValue() : 0;

                // endAt (Timestamp -> Date)
                Object ts = d.get("endAt");
                if (ts instanceof com.google.firebase.Timestamp)
                    r.endAt = ((com.google.firebase.Timestamp) ts).toDate();
                else if (ts instanceof java.util.Date)
                    r.endAt = (java.util.Date) ts;

                r.hasReview = false;  // varsayılan
                // Yorum var mı? (isteğe bağlı, varsa gösterimi engelle)
                checkReviewExists(r); // aşağıda

                r.teacherName = teacherNameOf(r.teacherId);
                r.price       = priceOf(r.teacherId, r.subjectId);
                teacherIdsSeen.add(r.teacherId);

                items.add(r);
            }

            items.sort(Comparator.comparing((Row r) -> r.date).thenComparingInt(r -> r.hour));
            adapter.notifyDataSetChanged();

            empty.setText(items.isEmpty()
                    ? (filter == Filter.PENDING ? "Bekleyen talebin yok." : "Geçmiş kaydın yok.")
                    : "");

            for (String tid : teacherIdsSeen) ensureTeacherProfileLoaded(tid);
        });
    }

    private void checkReviewExists(Row r) {
        db.collection("teacherReviews").document(r.id).get()
                .addOnSuccessListener(ds -> {
                    r.hasReview = ds.exists();
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
        if (!"pending".equals(r.status)) return; // rules da böyle
        bookingRepo.updateStatusById(r.id, "cancelled")
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), "İptal hatası: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    // --- Adapter ---
    static class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        interface OnCancel { void run(Row r); }
        interface NameProvider { String apply(String teacherId); }
        interface PriceProvider { Double apply(String teacherId, String subjectId); }
        interface OnReview { void run(Row r); }

        private final List<Row> items;
        private final OnCancel onCancel;
        private final NameProvider nameProvider;
        private final PriceProvider priceProvider;
        private final OnReview onReview;

        Adapter(List<Row> items, OnCancel onCancel, NameProvider nameProvider, PriceProvider priceProvider, OnReview onReview) {
            this.items = items;
            this.onCancel = onCancel;
            this.nameProvider = nameProvider;
            this.priceProvider = priceProvider;
            this.onReview = onReview;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv1, tv2; Button btnCancel,btnReview;
            VH(View v){
                super(v);
                tv1 = v.findViewById(R.id.tvLine1);
                tv2 = v.findViewById(R.id.tvLine2);
                btnCancel = v.findViewById(R.id.btnCancel);
                btnReview = v.findViewById(R.id.btnReview);
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

            // --- Status'u TR + renk ---
            StatusUi ui = statusUi(h.itemView, r.status);
            String line = String.format(Locale.getDefault(), "%s  %s  • Durum= %s", dt, priceStr, ui.label);

            android.text.SpannableString ss = new android.text.SpannableString(line);
            int idx = line.lastIndexOf(ui.label);
            if (idx >= 0) {
                ss.setSpan(new android.text.style.ForegroundColorSpan(ui.color),
                        idx, idx + ui.label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        idx, idx + ui.label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            h.tv2.setText(ss);

            boolean canReview = r.endAt != null
                    && new java.util.Date().after(r.endAt)
                    && ("accepted".equals(r.status) || "completed".equals(r.status))
                    && !r.hasReview; // Firestore'dan teacherReviews/{bookingId} yoksa false


            boolean cancellable = "pending".equalsIgnoreCase(r.status);
            h.btnCancel.setVisibility(cancellable ? View.VISIBLE : View.GONE);
            h.btnCancel.setOnClickListener(v -> onCancel.run(r));

            // >>> EKLE: Yorum Yaz butonu bağlama
            h.btnReview.setVisibility(canReview ? View.VISIBLE : View.GONE);
            h.btnReview.setOnClickListener(v -> {
                h.btnReview.setEnabled(false); // çift tık önle
                onReview.run(r);               // dışarıdaki callback'in
            });
        }

        private static class StatusUi {
            final String label; final int color;
            StatusUi(String l, int c){ label=l; color=c; }
        }

        private StatusUi statusUi(View v, String statusRaw) {
            String s = statusRaw == null ? "" : statusRaw.toLowerCase(Locale.ROOT);
            android.content.Context c = v.getContext();
            if (s.equals("accepted")) {
                return new StatusUi("Onaylandı",
                        androidx.core.content.ContextCompat.getColor(c, R.color.status_accepted));
            } else if (s.equals("declined")) {
                return new StatusUi("Reddedildi",
                        androidx.core.content.ContextCompat.getColor(c, R.color.status_declined));
            } else if (s.equals("cancelled") || s.equals("canceled")) { // iki yazıma da dayanıklı
                return new StatusUi("İptal edildi",
                        androidx.core.content.ContextCompat.getColor(c, R.color.status_cancelled));
            } else { // pending (default)
                return new StatusUi("Beklemede",
                        androidx.core.content.ContextCompat.getColor(c, R.color.status_pending));
            }
        }


        @Override public int getItemCount(){ return items.size(); }
    }
    private void onReviewClicked(Row r) {
        Toast.makeText(requireContext(),
                "Yorum yaz: " + teacherNameOf(r.teacherId), Toast.LENGTH_SHORT).show();
        // TODO: Review dialog/ekranını aç ve teacherReviews/{bookingId} yaz.
    }
}
