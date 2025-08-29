package com.example.tutorist.ui.student;

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

    private final List<TeacherRow> items = new ArrayList<>();
    private final OnTeacherClick listener;

    public TeacherAdapter(OnTeacherClick l){ this.listener = l; }

    public void submit(List<TeacherRow> list){
        items.clear(); items.addAll(list); notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvBio, tvRatingText;
        RatingBar rb;
        VH(View v){
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvBio  = v.findViewById(R.id.tvBio);
            rb     = v.findViewById(R.id.rb);
            tvRatingText = v.findViewById(R.id.tvRatingText);
        }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt){
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_teacher, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos){
        TeacherRow t = items.get(pos);
        h.tvName.setText(t.fullName != null ? t.fullName : "Öğretmen");
        h.tvBio.setText(t.bio != null ? t.bio : "—");

        float display = displayRatingFrom(t.ratingAvg, t.ratingCount);
        long cnt = t.ratingCount != null ? t.ratingCount : 0L;

        h.rb.setRating(display);
        h.tvRatingText.setText(String.format(Locale.getDefault(), "%.1f (%d)", display, cnt));

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(t.id); });
    }

    @Override public int getItemCount(){ return items.size(); }

    private static float displayRatingFrom(Double ratingAvg, Long ratingCount) {
        double avg = ratingAvg == null ? 0.0 : ratingAvg;
        long count = ratingCount == null ? 0L : ratingCount;
        return (float) (count > 0 ? avg : 5.0); // hiç yorum yoksa 5 göster
    }

    public static class TeacherRow {
        public String id;
        public String fullName;
        public String bio;
        public Double ratingAvg;
        public Long ratingCount;
    }
}
