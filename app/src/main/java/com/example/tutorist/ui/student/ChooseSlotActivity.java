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
import com.example.tutorist.payment.PaymentActivity;
import com.example.tutorist.repo.BookingRepo;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.google.firebase.functions.FirebaseFunctions;
import com.example.tutorist.BuildConfig;

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
    private String selDateIso; // yyyy-MM-dd
    private Integer selHour = -1;

    private FirebaseFunctions functions;
    private static final String FUNCTIONS_REGION = "europe-west1";

    // DEBUG callback base (proje id’nizi yazın!)
    private static final String CALLBACK_BASE_DEBUG =
            "http://10.0.2.2:5001/tutorist-f2a46"; // <-- kendi projectId’niz
    private static final String CALLBACK_BASE_PROD =
            "https://europe-west1-tutorist-f2a46.cloudfunctions.net"; // prod’da değiştir




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



        functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION);
        if (BuildConfig.DEBUG && BuildConfig.FUNCTIONS_HOST != null && !BuildConfig.FUNCTIONS_HOST.isEmpty()) {
            functions.useEmulator(BuildConfig.FUNCTIONS_HOST, BuildConfig.FUNCTIONS_PORT);
        }

        // öğrenci adını tek sefer çek (opsiyonel)
        db.collection("users").document(uid).get()
                .addOnSuccessListener(d -> studentName = d.getString("fullName"));

        btnPickDate.setOnClickListener(v -> openDatePicker());
        btnRequest.setOnClickListener(v -> submitRequest());

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
                studentName != null ? studentName : uid,
                subjectId != null ? subjectId : "",
                subjectName != null ? subjectName : "",
                selDateIso,
                selHour
        ).addOnSuccessListener(x -> {
            Toast.makeText(this, "Talebiniz iletildi. Ödeme başlatılıyor…", Toast.LENGTH_SHORT).show();
            // ➊ Booking oluşturulduktan sonra ödeme akışını aç
            startPayment();
            btnRequest.setEnabled(true);
        }).addOnFailureListener(e -> {
            btnRequest.setEnabled(true);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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


    private void startPayment() {
        if (teacherId == null || subjectId == null || subjectName == null || selDateIso == null || selHour == null) {
            Toast.makeText(this, "Eksik bilgi.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Booking id’yi client tarafında da hesaplayalım (server’da da aynı formül var)
        String bookingId = BookingRepo.slotId(teacherId, selDateIso, selHour);

        // Bu iterasyonda fiyatı sabit veriyoruz — GERÇEKTE fiyat server’dan alınmalı!
        int price = 300;        // ₺
        int teacherShare = 270; // ₺

        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", bookingId);
        payload.put("teacherId", teacherId);
        payload.put("subjectId", subjectId);
        payload.put("subjectName", subjectName);
        payload.put("price", price);
        payload.put("teacherShare", teacherShare);

        // Kart kaydı (opsiyonel)
        payload.put("saveCard", true);
        // Eğer Firestore’dan user.iyzico.cardUserKey çekiyorsanız buraya koyun:
        // payload.put("cardUserKey", userCardUserKey);

        // Buyer + fatura (şimdilik makul dummy veriler)
        Map<String, Object> buyer = new HashMap<>();
        String name = (studentName != null && studentName.contains(" "))
                ? studentName.split(" ")[0] : (studentName != null ? studentName : "Ad");
        String surname = (studentName != null && studentName.contains(" "))
                ? studentName.substring(studentName.indexOf(' ') + 1) : "Soyad";
        buyer.put("name", name);
        buyer.put("surname", surname);
        buyer.put("gsmNumber", "+905000000000");
        buyer.put("email", "test@example.com");
        buyer.put("nationalId", "11111111111");
        buyer.put("ip", "85.34.78.112");
        payload.put("buyer", buyer);

        Map<String, Object> bill = new HashMap<>();
        bill.put("address", "Adres");
        bill.put("city", "Istanbul");
        bill.put("country", "Turkey");
        bill.put("zipCode", "34000");
        payload.put("billingAddress", bill);

        // Callback base (emülatör/prod)
        String callbackBase = BuildConfig.DEBUG ? CALLBACK_BASE_DEBUG : CALLBACK_BASE_PROD;
        payload.put("callbackBase", callbackBase);

        functions.getHttpsCallable("iyziInitCheckout")
                .call(payload)
                .addOnSuccessListener(r -> {
                    Map<?, ?> result = (Map<?, ?>) r.getData();
                    String html = (String) result.get("checkoutFormContent");
                    String token = (String) result.get("token"); // bilgi amaçlı
                    if (html == null) {
                        Toast.makeText(this, "Ödeme başlatılamadı.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // WebView ile aç (PaymentActivity aşağıda)
                    com.example.tutorist.payment.PaymentActivity.start(
                            this, bookingId, html
                    );
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Ödeme hatası: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }




}
