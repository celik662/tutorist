package com.example.tutorist.ui.student;

import android.graphics.Color;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.tutorist.R;
import com.example.tutorist.model.Subject;
import com.google.android.material.card.MaterialCardView;
import java.util.*;

public class SubjectAdapter extends RecyclerView.Adapter<SubjectAdapter.VH> {
    public interface OnItemClick { void onClick(Subject s); }

    private final List<Subject> items = new ArrayList<>();
    private final OnItemClick listener;

    // Pastel fallback renkler (ARGB)
    private static final int[] PASTELS = new int[]{
            0xFFFFE0B2, // orange100
            0xFFC8E6C9, // green100
            0xFFBBDEFB, // blue100
            0xFFFFCDD2, // red100
            0xFFD1C4E9, // purple100
            0xFFFFF9C4, // yellow100
            0xFFB2EBF2, // cyan100
            0xFFE1BEE7, // deepPurple100
            0xFFFFF3E0  // orange50
    };

    public SubjectAdapter(OnItemClick l) { this.listener = l; }

    public void setItems(List<Subject> list) {
        items.clear(); items.addAll(list); notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        MaterialCardView card;
        TextView tv;
        VH(View v) { super(v); card = v.findViewById(R.id.card); tv = v.findViewById(R.id.tvSubjectName); }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_subject_card, p, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Subject s = items.get(pos);
        h.tv.setText(s.nameTR);

        // Renk belirleme: colorHex varsa onu kullan, yoksa stabil pastel seç
        Integer color = null;
        if (s.colorHex != null && s.colorHex.startsWith("#")) {
            try { color = Color.parseColor(s.colorHex); } catch (Exception ignored) {}
        }
        if (color == null) {
            int index = Math.abs((s.id != null ? s.id.hashCode() : s.nameTR.hashCode())) % PASTELS.length;
            color = PASTELS[index];
        }
        h.card.setCardBackgroundColor(color);

        h.itemView.setOnClickListener(v -> { if (listener!=null) listener.onClick(s); });
    }



    @Override public int getItemCount() { return items.size(); }
}
