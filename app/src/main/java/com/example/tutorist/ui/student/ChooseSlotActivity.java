package com.example.tutorist.ui.student;

import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.tutorist.R;
import com.example.tutorist.repo.BookingRepo;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ChooseSlotActivity extends AppCompatActivity {

    private String teacherId, subjectId, subjectName;
    private String uid, studentName;
    private TextView tvDate, tvInfo, tvTitle;
    private Button btnPickDate, btnRequest;
    private RecyclerView rv;
    private HourAdapter adapter;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final BookingRepo bookingRepo = new BookingRepo();

    private final Calendar cal = Calendar.getInstance();
    private String selDateIso = ""; // yyyy-MM-dd
    private Integer selHour = null;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_choose_slot);

        teacherId   = getIntent().getStringExtra("teacherId");
        subjectId   = getIntent().getStringExtra("subjectId");
        subjectName = getIntent().getStringExtra("subjectName");

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || teacherId == null) { finish(); return; }

        tvTitle    = findViewById(R.id.tvTitle);
        tvDate     = findViewById(R.id.tvDate);
        tvInfo     = findViewById(R.id.tvInfo);
        btnPickDate= findViewById(R.id.btnPickDate);
        btnRequest = findViewById(R.id.btnRequest);
        rv         = findViewById(R.id.rvHours);

        tvTitle.setText(subjectName != null ? subjectName : "Ders saati seç");

        rv.setLayoutManager(new GridLayoutManager(this, 4));
        adapter = new HourAdapter(h -> { selHour = h; refreshButtons(); });
        rv.setAdapter(adapter);

        // öğrenci adını tek sefer çek (opsiyonel)
        db.collection("users").document(uid).get()
                .addOnSuccessListener(d -> studentName = d.getString("fullName"));

        btnPickDate.setOnClickListener(v -> openDatePicker());
        btnRequest.setOnClickListener(v -> submitRequest());

        adapter = new HourAdapter(hour -> {
            selHour = hour;         // Activity alanında saklıyorsun
            refreshButtons();       // “Ders talep et” butonu vs. güncellensin
        });
        rv.setAdapter(adapter);


        // bugünün tarihi ile başla (en yakın bütün saatten itibaren)
        setDate(cal);
    }

    private void openDatePicker() {
        DatePickerDialog dp = new DatePickerDialog(this,
                (view, y, m, d) -> {
                    Calendar c = Calendar.getInstance();
                    c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d);
                    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                    setDate(c);
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(System.currentTimeMillis());
        dp.show();
    }

    private void setDate(Calendar c) {
        cal.setTimeInMillis(c.getTimeInMillis());
        selHour = null;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        selDateIso = fmt.format(cal.getTime());
        tvDate.setText(selDateIso);
        loadDayAvailability();
        refreshButtons();
    }

    private void refreshButtons() {
        btnRequest.setEnabled(selHour != null);
        if (selHour == null) {
            tvInfo.setText("Bir saat seçin.");
        } else {
            tvInfo.setText(String.format(Locale.getDefault(), "%s • %02d:00", selDateIso, selHour));
        }
    }

    /** Seçili gün için 09–21 saatleri ve slotLock durumlarını oku */
    private void loadDayAvailability() {
        List<Hour> hours = new ArrayList<>();
        for (int h = 9; h <= 21; h++) hours.add(new Hour(h, false, false));

        // slotLocks/{teacherId_date_hour}
        List<Task<DocumentSnapshot>> tasks = new ArrayList<>();
        for (Hour hr : hours) {
            String slotId = BookingRepo.slotId(teacherId, selDateIso, hr.h);
            tasks.add(db.collection("slotLocks").document(slotId).get());
        }

        Tasks.whenAllSuccess(tasks).addOnSuccessListener(list -> {
            for (int i = 0; i < list.size(); i++) {
                DocumentSnapshot d = (DocumentSnapshot) list.get(i);
                boolean taken = d.exists() && !"declined".equals(d.getString("status")) && !"cancelled".equals(d.getString("status"));
                hours.get(i).taken = taken;
                // geçmiş saatleri kapat
                Calendar now = Calendar.getInstance();
                if (isPast(selDateIso, hours.get(i).h, now)) hours.get(i).disabled = true;
            }
            adapter.submit(hours);
        });
    }

    private boolean isPast(String dateIso, int hour, Calendar now) {
        String[] p = dateIso.split("-");
        int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]) - 1, d = Integer.parseInt(p[2]);
        Calendar c = Calendar.getInstance();
        c.set(y, m, d, hour, 0, 0); c.set(Calendar.MILLISECOND, 0);
        return c.before(now);
    }

    private void submitRequest() {
        if (selHour == null) return;
        btnRequest.setEnabled(false);

        bookingRepo.createBooking(
                teacherId,
                uid,
                studentName != null ? studentName : uid,  // boşsa uid
                subjectId != null ? subjectId : "",
                subjectName != null ? subjectName : "",
                selDateIso,
                selHour
        ).addOnSuccessListener(x -> {
            Toast.makeText(this, "Talebiniz iletildi.", Toast.LENGTH_SHORT).show();
            finish();
        }).addOnFailureListener(e -> {
            btnRequest.setEnabled(true);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            // olası yarış: slot dolduysa güncelle ve kullanıcıdan yeniden seçim iste
            loadDayAvailability();
        });
    }


    // ChooseSlotActivity.java içinde, class’ın gövdesinde (onCreate vb. metodların DIŞINDA) ekleyin:
    interface OnHourPick { void onPick(int hour); }

    // Seçili saat satırı
    static class Hour {
        int h; boolean taken; boolean disabled;
        Hour(int h, boolean taken, boolean disabled){ this.h=h; this.taken=taken; this.disabled=disabled; }
    }

    // ViewHolder
    static class HourVH extends RecyclerView.ViewHolder {
        TextView tv;
        HourVH(View v){ super(v); tv = v.findViewById(R.id.tvHour); }
    }

    // Adapter
    static class HourAdapter extends RecyclerView.Adapter<HourVH> {
        private final List<Hour> items = new ArrayList<>();
        private final OnHourPick onPick;
        private int selected = RecyclerView.NO_POSITION;

        HourAdapter(OnHourPick onPick){ this.onPick = onPick; }

        void submit(List<Hour> h){
            items.clear();
            items.addAll(h);
            selected = RecyclerView.NO_POSITION;
            notifyDataSetChanged();
        }

        @NonNull @Override public HourVH onCreateViewHolder(@NonNull ViewGroup p, int vt){
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_hour_slot, p, false);
            return new HourVH(v);
        }

        @Override public void onBindViewHolder(@NonNull HourVH h, @SuppressLint("RecyclerView") int pos){
            Hour it = items.get(pos);
            h.tv.setText(String.format(Locale.getDefault(), "%02d:00", it.h));

            boolean enabled = !it.taken && !it.disabled;
            h.itemView.setEnabled(enabled);
            h.itemView.setAlpha(enabled ? 1f : 0.4f);
            h.itemView.setSelected(selected == pos);

            h.itemView.setOnClickListener(v -> {
                if (!enabled) return;
                int prev = selected;
                selected = pos;
                if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev);
                notifyItemChanged(selected);
                onPick.onPick(it.h); // Activity’ye saat bilgisini ver
            });
        }

        @Override public int getItemCount(){ return items.size(); }
    }

}
