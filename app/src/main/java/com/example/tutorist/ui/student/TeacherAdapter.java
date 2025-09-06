package com.example.tutorist.ui.student;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RatingBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.tutorist.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TeacherAdapter extends RecyclerView.Adapter<TeacherAdapter.VH> {

    public interface OnTeacherClick { void onClick(String teacherId); }

    public static class TeacherRow {
        public String id;
        public String fullName;
        public String bio;
        public Double ratingAvg;
        public Long ratingCount;
        public Integer price; // TRY (ekledik)
    }

    private final List<TeacherRow> items = new ArrayList<>();
    private final OnTeacherClick listener;

    public TeacherAdapter(OnTeacherClick l){ this.listener = l; }

    public void submit(List<TeacherRow> data){
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    static float displayRatingFrom(Double ratingAvg, Long ratingCount) {
        double avg = ratingAvg == null ? 0.0 : ratingAvg;
        long count = ratingCount == null ? 0L : ratingCount;
        return (float)(count > 0 ? avg : 5.0f);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvBio, tvRatingText, tvPrice;
        RatingBar rb;
        VH(View v){
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvBio  = v.findViewById(R.id.tvBio);
            tvRatingText = v.findViewById(R.id.tvRatingText);
            tvPrice = v.findViewById(R.id.tvPrice);    // layout’ta var olmalı
            rb = v.findViewById(R.id.rb);
        }
    }
    public void updatePriceById(String teacherId, Integer price){
        if (teacherId == null || price == null) return;
        int idx = -1;
        for (int i = 0; i < items.size(); i++){
            if (teacherId.equals(items.get(i).id)) { idx = i; break; }
        }
        if (idx >= 0) {
            items.get(idx).price = price;
            notifyItemChanged(idx);
        }
    }


    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt){
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_teacher, p, false);
        return new VH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override public void onBindViewHolder(@NonNull VH h, int pos){
        TeacherRow t = items.get(pos);

        if (h.tvName != null) h.tvName.setText(t.fullName != null ? t.fullName : "Öğretmen");
        if (h.tvBio  != null) h.tvBio.setText(t.bio != null ? t.bio : "—");

        float display = displayRatingFrom(t.ratingAvg, t.ratingCount);
        long cnt = t.ratingCount != null ? t.ratingCount : 0L;

        if (h.rb != null) h.rb.setRating(display);
        if (h.tvRatingText != null)
            h.tvRatingText.setText(String.format(Locale.getDefault(), "%.1f (%d)", display, cnt));

        // Fiyat
        if (h.tvPrice != null) {
            if (t.price != null && t.price > 0) {
                h.tvPrice.setText("₺" + t.price);
                h.tvPrice.setVisibility(View.VISIBLE);
            } else {
                h.tvPrice.setText("—");
                h.tvPrice.setVisibility(View.VISIBLE); // istersen GONE yap
            }
        }

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(t.id); });
    }

    @Override public int getItemCount(){ return items.size(); }
}
