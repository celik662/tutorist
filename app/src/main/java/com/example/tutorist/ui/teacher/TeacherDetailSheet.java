package com.example.tutorist.ui.teacher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.ui.student.ChooseSlotActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TeacherDetailSheet extends com.google.android.material.bottomsheet.BottomSheetDialogFragment {

    public static TeacherDetailSheet newInstance(String teacherId, String subjectId, String subjectName) {
        Bundle b = new Bundle();
        b.putString("tid", teacherId);
        b.putString("sid", subjectId);
        b.putString("sname", subjectName);
        TeacherDetailSheet f = new TeacherDetailSheet();
        f.setArguments(b);
        return f;
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private ListenerRegistration profReg, revReg;

    private ImageView img;
    private TextView tvName, tvBio, tvCount, tvPrice; // tvPrice eklendi
    private RatingBar ratingBar;
    private RecyclerView rv;
    private ReviewAdapter reviewAdapter;

    private String teacherId;
    private String subjectId;
    private String subjectName;

    private Integer currentPrice = null; // seçili dersin fiyatı (TRY)

    // ChooseSlotActivity’ye geçirirken aynı key’leri kullanalım
    private static final String EX_TEACHER_NAME  = "ex_teacher_name";
    private static final String EX_SUBJECT_PRICE = "ex_subject_price";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.sheet_teacher_detail, c, false);
        img       = v.findViewById(R.id.img);
        tvName    = v.findViewById(R.id.tvName);
        tvBio     = v.findViewById(R.id.tvBio);
        tvCount   = v.findViewById(R.id.tvCount);
        tvPrice   = v.findViewById(R.id.tvPrice);  // layout’ta bu id olmalı
        ratingBar = v.findViewById(R.id.ratingBar);

        rv = v.findViewById(R.id.rvReviews);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        reviewAdapter = new ReviewAdapter(new ArrayList<>());
        rv.setAdapter(reviewAdapter);

        Bundle args = getArguments();
        teacherId   = args != null ? args.getString("tid")   : null;
        subjectId   = args != null ? args.getString("sid")   : null;
        subjectName = args != null ? args.getString("sname") : null;

        // Rezervasyon butonu
        v.findViewById(R.id.btnBook).setOnClickListener(btn -> {
            Context ctx = getContext();
            if (ctx != null) {
                Intent it = new Intent(ctx, ChooseSlotActivity.class);
                it.putExtra("teacherId", teacherId);
                if (subjectId != null)   it.putExtra("subjectId", subjectId);
                if (subjectName != null) it.putExtra("subjectName", subjectName);
                // ÖZETTE DOĞRU GÖRÜNMEK İÇİN extras:
                if (tvName != null)      it.putExtra(EX_TEACHER_NAME, tvName.getText().toString());
                if (currentPrice != null && currentPrice > 0) {
                    it.putExtra(EX_SUBJECT_PRICE, currentPrice);
                }
                startActivity(it);
            }
            dismissAllowingStateLoss();
        });

        startListening();       // profil + yorumları dinle
        loadPriceForDetail();   // fiyatı getir (profilden dene, olmazsa fallback)

        return v;
    }

    /* ---------- Yardımcılar ---------- */

    @Nullable
    private Integer toIntOrNull(Object o){
        if (o == null) return null;
        if (o instanceof Number) return ((Number)o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignore){ return null; }
    }

    /** teacherProfiles dokümanından subject fiyatını çıkarır. */
    @Nullable
    private Integer extractPriceFromProfile(DocumentSnapshot d, String subjectId){
        if (d == null || subjectId == null) return null;

        // 1) prices.{subjectId}
        Integer n = toIntOrNull(d.get("prices." + subjectId));
        if (n != null && n > 0) return n;

        // 2) subjectsMap.{subjectId}.price  (nested obje)
        Object sm = d.get("subjectsMap." + subjectId);
        if (sm instanceof Map) {
            Integer n2 = toIntOrNull(((Map<?,?>) sm).get("price"));
            if (n2 != null && n2 > 0) return n2;
        }

        // 3) subjectsMap.{subjectId} doğrudan sayı (senin ekran görüntündeki senaryo)
        Integer n3 = toIntOrNull(sm);
        if (n3 != null && n3 > 0) return n3;

        return null;
    }

    private void setPriceText(@Nullable Integer price){
        if (tvPrice == null) return;
        if (price != null && price > 0) {
            tvPrice.setText("₺" + price);
        } else {
            tvPrice.setText("—");
        }
    }

    /* ---------- Fiyat: profil → teacherSubjects → subjects fallback ---------- */

    private void loadPriceForDetail() {
        if (subjectId == null || teacherId == null) { setPriceText(null); return; }

        // Önce profilden tek sefer oku (dinleyicide de tekrar deniyoruz)
        db.collection("teacherProfiles").document(teacherId).get()
                .addOnSuccessListener(prof -> {
                    Integer fromProf = extractPriceFromProfile(prof, subjectId);
                    if (fromProf != null && fromProf > 0) {
                        currentPrice = fromProf;
                        setPriceText(currentPrice);
                    } else {
                        // A) teacherSubjects/{teacherId_subjectId}
                        final String tsId = teacherId + "_" + subjectId;
                        db.collection("teacherSubjects").document(tsId).get()
                                .addOnSuccessListener(d -> {
                                    Integer p = toIntOrNull(d.get("price"));
                                    if (p != null && p > 0) {
                                        currentPrice = p; setPriceText(currentPrice);
                                    } else {
                                        // B) teacherSubjects/{teacherId}/subjects/{subjectId}
                                        db.collection("teacherSubjects").document(teacherId)
                                                .collection("subjects").document(subjectId).get()
                                                .addOnSuccessListener(sd -> {
                                                    Integer p2 = toIntOrNull(sd.get("price"));
                                                    if (p2 != null && p2 > 0) {
                                                        currentPrice = p2; setPriceText(currentPrice);
                                                    } else {
                                                        // C) subjects/{subjectId}.price (genel)
                                                        db.collection("subjects").document(subjectId).get()
                                                                .addOnSuccessListener(s -> {
                                                                    Integer p3 = toIntOrNull(s.get("price"));
                                                                    currentPrice = p3;
                                                                    setPriceText(currentPrice);
                                                                })
                                                                .addOnFailureListener(e -> setPriceText(null));
                                                    }
                                                })
                                                .addOnFailureListener(e -> setPriceText(null));
                                    }
                                })
                                .addOnFailureListener(e -> setPriceText(null));
                    }
                })
                .addOnFailureListener(e -> setPriceText(null));
    }

    /* ---------- Profil + Yorum dinleme ---------- */

    private void startListening() {
        if (teacherId == null) return;

        // Profil
        profReg = db.collection("teacherProfiles").document(teacherId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null || !snap.exists()) {
                        fetchRatingFallbackFromReviews();
                        return;
                    }
                    String name  = snap.getString("displayName");
                    String bio   = snap.getString("bio");
                    Double avg   = snap.getDouble("ratingAvg");
                    Long   count = snap.getLong("ratingCount");

                    if (tvName != null) tvName.setText(name != null ? name : "Öğretmen");
                    if (tvBio  != null) tvBio.setText(bio != null ? bio : "—");

                    // Fiyatı profilden tekrar dene (profil güncellenmiş olabilir)
                    Integer p = extractPriceFromProfile(snap, subjectId);
                    if (p != null && p > 0) {
                        currentPrice = p;
                        setPriceText(currentPrice);
                    }

                    if (avg == null || count == null) {
                        fetchRatingFallbackFromReviews();
                    } else {
                        bindRating(avg, count);
                    }
                });

        // Yorumlar (son 20)
        revReg = db.collection("teacherReviews")
                .whereEqualTo("teacherId", teacherId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    List<Review> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Review r = new Review();
                        r.rating    = d.getLong("rating") != null ? d.getLong("rating").intValue() : 0;
                        r.comment   = String.valueOf(d.get("comment"));
                        r.createdAt = d.getTimestamp("createdAt");
                        list.add(r);
                    }
                    reviewAdapter.submit(list);
                });
    }

    private void bindRating(Double ratingAvg, Long ratingCount) {
        double avg  = ratingAvg  == null ? 0.0 : ratingAvg;
        long   cnt  = ratingCount == null ? 0L  : ratingCount;
        float  disp = (float)(cnt > 0 ? avg : 5.0);
        ratingBar.setRating(disp);
        tvCount.setText(String.format(Locale.getDefault(), "%.1f (%d)", disp, cnt));
    }

    private void fetchRatingFallbackFromReviews() {
        db.collection("teacherReviews")
                .whereEqualTo("teacherId", teacherId)
                .get()
                .addOnSuccessListener(snap -> {
                    long cnt = 0; double sum = 0;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Number n = (Number) d.get("rating");
                        if (n != null) { sum += n.doubleValue(); cnt++; }
                    }
                    double avg = cnt > 0 ? (sum / cnt) : 0.0;
                    bindRating(avg, cnt);

                    if (cnt > 0) {
                        HashMap<String,Object> up = new HashMap<>();
                        up.put("ratingAvg", avg);
                        up.put("ratingCount", cnt);
                        db.collection("teacherProfiles").document(teacherId)
                                .set(up, SetOptions.merge());
                    }
                });
    }

    @Override public void onDestroyView() {
        if (profReg != null) profReg.remove();
        if (revReg != null)  revReg.remove();
        super.onDestroyView();
    }

    /* --- dto + adapter (aynı) --- */
    static class Review { int rating; String comment; com.google.firebase.Timestamp createdAt; }
    static class ReviewVH extends RecyclerView.ViewHolder {
        RatingBar rb; TextView tvWhen, tvComment;
        ReviewVH(View v){ super(v);
            rb = v.findViewById(R.id.rb);
            tvWhen = v.findViewById(R.id.tvWhen);
            tvComment = v.findViewById(R.id.tvComment);
        }
    }
    static class ReviewAdapter extends RecyclerView.Adapter<ReviewVH> {
        private final List<Review> items;
        ReviewAdapter(List<Review> it){ items = it; }
        void submit(List<Review> it){ items.clear(); items.addAll(it); notifyDataSetChanged(); }
        @NonNull @Override public ReviewVH onCreateViewHolder(@NonNull ViewGroup p, int vt){
            return new ReviewVH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_review, p, false));
        }
        @Override public void onBindViewHolder(@NonNull ReviewVH h, int pos){
            Review r = items.get(pos);
            h.rb.setRating(r.rating);
            h.tvComment.setText(r.comment != null ? r.comment : "");
            String when = r.createdAt != null ? r.createdAt.toDate().toString() : "";
            h.tvWhen.setText(when);
        }
        @Override public int getItemCount(){ return items.size(); }
    }

    private static float displayRatingFrom(Double ratingAvg, Long ratingCount) {
        double avg = ratingAvg == null ? 0.0 : ratingAvg;
        long count = ratingCount == null ? 0L : ratingCount;
        return (float) (count > 0 ? avg : 5.0);
    }
}
