package com.example.tutorist.ui.student;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RatingBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class TeacherAdapter extends RecyclerView.Adapter<TeacherAdapter.VH> {

    /* ==== PUBLIC API ==== */

    public interface OnTeacherClick { void onClick(String teacherId); }

    /** Senin önceki modelini koruyorum */
    public static class TeacherRow {
        public String id;
        public String fullName;
        public String bio;
        public Double ratingAvg;
        public Long ratingCount;
        public Integer price; // TRY

        /** içerik karşılaştırması (DiffUtil için) */
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TeacherRow)) return false;
            TeacherRow that = (TeacherRow) o;
            return Objects.equals(id, that.id)
                    && Objects.equals(fullName, that.fullName)
                    && Objects.equals(bio, that.bio)
                    && Objects.equals(ratingAvg, that.ratingAvg)
                    && Objects.equals(ratingCount, that.ratingCount)
                    && Objects.equals(price, that.price);
        }
        @Override public int hashCode() {
            return Objects.hash(id, fullName, bio, ratingAvg, ratingCount, price);
        }
    }

    public TeacherAdapter(OnTeacherClick l){
        this.listener = l;
        setHasStableIds(true);
    }

    /** Tam listeyi ver; diff ile animasyonlu ve verimli günceller */
    public void submit(List<TeacherRow> data){
        List<TeacherRow> newList = (data == null) ? new ArrayList<>() : new ArrayList<>(data);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCb(this.items, newList));
        this.items = newList;
        diff.dispatchUpdatesTo(this);
    }

    /** Tek bir öğretmenin fiyatını güncelle (id ile) */
    public void updatePriceById(String teacherId, Integer price){
        if (teacherId == null) return;
        for (int i = 0; i < items.size(); i++){
            TeacherRow row = items.get(i);
            if (teacherId.equals(row.id)) {
                row.price = price;
                notifyItemChanged(i);
                return;
            }
        }
    }

    /* ==== INTERNAL ==== */

    private final OnTeacherClick listener;
    private List<TeacherRow> items = new ArrayList<>();

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvBio, tvRatingText, tvPrice;
        RatingBar rb;
        VH(View v){
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvBio  = v.findViewById(R.id.tvBio);
            tvRatingText = v.findViewById(R.id.tvRatingText);
            tvPrice = v.findViewById(R.id.tvPrice);
            rb = v.findViewById(R.id.rb);
        }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt){
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_teacher, p, false);
        return new VH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override public void onBindViewHolder(@NonNull VH h, int pos){
        TeacherRow t = items.get(pos);

        // İsim & bio
        if (h.tvName != null) h.tvName.setText(t.fullName != null ? t.fullName : "Öğretmen");
        if (h.tvBio  != null) h.tvBio.setText(t.bio != null ? t.bio : "—");

        // Rating
        float display = displayRatingFrom(t.ratingAvg, t.ratingCount);
        long cnt = t.ratingCount != null ? t.ratingCount : 0L;
        if (h.rb != null) h.rb.setRating(display);
        if (h.tvRatingText != null) {
            h.tvRatingText.setText(String.format(Locale.getDefault(), "%.1f (%d)", display, cnt));
        }

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

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(t.id);
        });
    }

    @Override public int getItemCount(){ return items.size(); }

    @Override public long getItemId(int position) {
        String id = items.get(position).id;
        return id == null ? RecyclerView.NO_ID : id.hashCode();
    }

    /* ==== HELPERS ==== */

    static float displayRatingFrom(Double ratingAvg, Long ratingCount) {
        double avg = ratingAvg == null ? 0.0 : ratingAvg;
        long count = ratingCount == null ? 0L : ratingCount;
        return (float)(count > 0 ? avg : 5.0f);
    }

    /** DiffUtil callback */
    static class DiffCb extends DiffUtil.Callback {
        private final List<TeacherRow> oldL, newL;
        DiffCb(List<TeacherRow> o, List<TeacherRow> n){ this.oldL=o; this.newL=n; }

        @Override public int getOldListSize() { return oldL.size(); }
        @Override public int getNewListSize() { return newL.size(); }

        @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            String a = oldL.get(oldItemPosition).id;
            String b = newL.get(newItemPosition).id;
            return Objects.equals(a, b);
        }

        @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return oldL.get(oldItemPosition).equals(newL.get(newItemPosition));
        }
    }
}
