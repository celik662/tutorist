package com.example.tutorist.ui.student;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.*;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tutorist.R;
import com.example.tutorist.model.Subject;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public SubjectAdapter(OnItemClick l) { this.listener = l; setHasStableIds(true); }

    /** DiffUtil ile akıcı güncelleme */
    public void setItems(List<Subject> list) {
        List<Subject> newList = (list == null) ? new ArrayList<>() : new ArrayList<>(list);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCb(this.items, newList));
        this.items.clear();
        this.items.addAll(newList);
        diff.dispatchUpdatesTo(this);
    }

    static class VH extends RecyclerView.ViewHolder {
        MaterialCardView card;
        TextView tv;
        VH(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            tv   = v.findViewById(R.id.tvSubjectName);
        }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_subject_card, p, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Subject s = items.get(pos);

        // İsim önceliği: name > nameTR > id
        String display = s.nameTR != null && !s.nameTR.isEmpty() ? s.nameTR
                : (s.nameTR != null && !s.nameTR.isEmpty() ? s.nameTR
                : (s.id != null ? s.id : "Ders"));
        h.tv.setText(display);

        // Arka plan rengi: colorHex varsa, yoksa stabil pastel
        int bg = pickBackgroundColor(s);

        // Yazı rengi (kontrast): açık zeminde koyu, koyu zeminde beyaz
        int fg = pickTextColorFor(bg);

        // İnce stroke: zeminin biraz koyusu
        int stroke = darken(bg, 0.12f);

        h.card.setCardBackgroundColor(bg);
        h.card.setStrokeColor(stroke);
        h.card.setStrokeWidth(dp(h.card, 1));

        // Ripple rengi: yazı renginin yarı saydamı (nazik etki)
        h.card.setRippleColor(ColorStateList.valueOf(adjustAlpha(fg, 0.2f)));

        h.tv.setTextColor(fg);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(s); });

        // A11y: içerik açıklaması
        h.itemView.setContentDescription(display);
    }

    @Override public int getItemCount() { return items.size(); }

    @Override public long getItemId(int position) {
        Subject s = items.get(position);
        String key = s.id != null ? s.id : (s.nameTR != null ? s.nameTR : s.nameTR);
        return key != null ? key.hashCode() : RecyclerView.NO_ID;
    }

    /* ---------- Helpers ---------- */

    private static int pickBackgroundColor(Subject s) {
        // Önce colorHex
        if (s != null && s.colorHex != null && s.colorHex.startsWith("#")) {
            try { return Color.parseColor(s.colorHex); } catch (Exception ignore) {}
        }
        // Stabil pastel (id veya name hash’i ile)
        String key = s != null && s.id != null ? s.id
                : (s != null && s.nameTR != null ? s.nameTR : (s != null ? s.nameTR : "x"));
        int idx = Math.abs(key != null ? key.hashCode() : 0) % PASTELS.length;
        return PASTELS[idx];
    }

    private static int pickTextColorFor(int bg) {
        // Relative luminance (yaklaşık): 0..1
        double r = Color.red(bg) / 255.0;
        double g = Color.green(bg) / 255.0;
        double b = Color.blue(bg) / 255.0;
        // sRGB → Luma (yaklaşık)
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return (luma > 0.6) ? Color.parseColor("#1F2937")  // koyu gri (tutorist_onSurface benzeri)
                : Color.WHITE;                 // koyu zeminde beyaz
    }

    private static int darken(int color, float amount) {
        int r = (int) Math.max(0, Color.red(color)   * (1f - amount));
        int g = (int) Math.max(0, Color.green(color) * (1f - amount));
        int b = (int) Math.max(0, Color.blue(color)  * (1f - amount));
        return Color.rgb(r, g, b);
    }

    private static int adjustAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * alpha);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int dp(View v, int dp){
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, v.getResources().getDisplayMetrics()));
    }

    /** DiffUtil callback */
    static class DiffCb extends DiffUtil.Callback {
        private final List<Subject> oldL, newL;
        DiffCb(List<Subject> o, List<Subject> n){ this.oldL = o; this.newL = n; }

        @Override public int getOldListSize() { return oldL.size(); }
        @Override public int getNewListSize() { return newL.size(); }

        @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            String a = oldL.get(oldItemPosition) != null ? oldL.get(oldItemPosition).id : null;
            String b = newL.get(newItemPosition) != null ? newL.get(newItemPosition).id : null;
            // id yoksa name/nameTR ile best-effort
            if (a == null || b == null) {
                String an = oldL.get(oldItemPosition) != null ? (oldL.get(oldItemPosition).nameTR) : null;
                String bn = newL.get(newItemPosition) != null ? (newL.get(newItemPosition).nameTR) : null;
                return Objects.equals(an, bn);
            }
            return Objects.equals(a, b);
        }

        @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Subject o = oldL.get(oldItemPosition);
            Subject n = newL.get(newItemPosition);
            return Objects.equals(o.nameTR, n.nameTR)
                    && Objects.equals(o.nameTR, n.nameTR)
                    && Objects.equals(o.colorHex, n.colorHex);
        }
    }
}
