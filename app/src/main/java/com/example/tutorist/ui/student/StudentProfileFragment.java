// app/src/main/java/com/example/tutorist/ui/student/StudentProfileFragment.java
package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.tutorist.R;
import com.example.tutorist.payment.PaymentActivity;
import com.example.tutorist.push.AppMessagingService;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.HashMap;
import java.util.Map;

public class StudentProfileFragment extends Fragment {

    private static final String FUNCTIONS_REGION = "europe-west1";
    // Sadece PROD callback (Cloud Functions domain’in)
    private static final String CALLBACK_BASE_PROD =
            "https://europe-west1-tutorist-f2a46.cloudfunctions.net";

    private EditText etName, etPhone;
    private TextView tvMsg;
    private LinearLayout llCards;
    private Button btnAddCard, btnSave, btnLogout;

    private final UserRepo userRepo = new UserRepo();
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseFunctions functions;

    private String uid;
    private ListenerRegistration userReg;

    private EditText etBookingId; // Test için eklendi
    private Button btnTest10, btnTest60;
    private Button btnTestPush;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_student_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        etName    = root.findViewById(R.id.etName);
        etPhone   = root.findViewById(R.id.etPhone);
        tvMsg     = root.findViewById(R.id.tvMsg);
        llCards   = root.findViewById(R.id.llCards);
        btnAddCard= root.findViewById(R.id.btnAddCard);
        btnSave   = root.findViewById(R.id.btnSave);
        btnLogout = root.findViewById(R.id.btnLogout);

        etBookingId = root.findViewById(R.id.etBookingId);     //test için eklendi
        btnTest10   = root.findViewById(R.id.btnTestReminder10);
        btnTest60   = root.findViewById(R.id.btnTestReminder60);



        View.OnClickListener trigger = click -> {
            String bookingId = etBookingId.getText() != null ? etBookingId.getText().toString().trim() : "";
            if (bookingId.isEmpty()) { showError("Önce Booking ID gir."); return; }

            Map<String, Object> req = new HashMap<>();
            req.put("bookingId", bookingId);
            req.put("minutes", click == btnTest60 ? 60 : 10);

            FirebaseFunctions.getInstance("europe-west1")
                    .getHttpsCallable("debugSendReminder")
                    .call(req)
                    .addOnSuccessListener(r -> showSuccess("Test bildirimi gönderildi."))
                    .addOnFailureListener(e -> Log.e("FUNC","err code="+
                            (e instanceof FirebaseFunctionsException ? ((FirebaseFunctionsException)e).getCode() : "unknown")
                            + " msg=" + e.getMessage(), e));
        };

        btnTest10.setOnClickListener(trigger);
        btnTest60.setOnClickListener(trigger);

        btnTestPush = root.findViewById(R.id.btnTestPush);

        btnTestPush.setOnClickListener(xx -> {
            FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> {
                        Log.d("TEST_PUSH", "token=" + token);
                        sendTestPush(token); // mevcut metodun
                    })
                    .addOnFailureListener(e -> {
                        Log.e("TEST_PUSH", "token fail", e);
                        showError("FCM token alınamadı: " + (e.getMessage()!=null?e.getMessage():""));
                    });
        });


        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();
        functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION);

        uid = auth.getUid();
        if (uid == null) {
            startActivity(new Intent(requireContext(), LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            requireActivity().finish();
            return;
        }

        loadProfile();
        listenUserCards(uid);

        btnAddCard.setOnClickListener(x -> startAddCardFlow());

        btnSave.setOnClickListener(x -> {
            String name  = etName != null ? etName.getText().toString().trim() : "";
            String phone = etPhone != null ? etPhone.getText().toString().trim() : "";
            if (name.isEmpty()) { showError("Lütfen ad-soyad girin."); return; }

            btnSave.setEnabled(false);
            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> { if (isAdded()) { showSuccess("Kaydedildi ✅"); btnSave.setEnabled(true); }})
                    .addOnFailureListener(e -> { if (isAdded()) { showError("Kaydedilemedi: " + (e!=null&&e.getMessage()!=null?e.getMessage():"")); btnSave.setEnabled(true); }});
        });

        btnLogout.setOnClickListener(clicked -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Çıkış yapılsın mı?")
                    .setMessage("Hesabınızdan çıkış yapacaksınız.")
                    .setNegativeButton("İptal", null)
                    .setPositiveButton("Çıkış yap", (d, w) -> {
                        // çift tıklamayı engelle
                        clicked.setEnabled(false);

                        // 1) önce token’ı kullanıcıdan kopar + cihazdan sil
                        com.example.tutorist.push.AppMessagingService
                                .detachTokenFromCurrentUserAndDeleteAsync(requireContext())
                                .addOnCompleteListener(t -> {
                                    // 2) sonra signOut
                                    FirebaseAuth.getInstance().signOut();

                                    // 3) login’e tertemiz dönüş
                                    Intent i = new Intent(requireContext(), com.example.tutorist.ui.auth.LoginActivity.class);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(i);
                                    requireActivity().finish();
                                });
                    })
                    .show();
        });

    }

    private void sendTestPush(String token){
        FirebaseFunctions.getInstance("europe-west1")
                .getHttpsCallable("sendTestDataMsg")
                .call(new java.util.HashMap<String,Object>() {{
                    put("token", token);
                }})
                .addOnSuccessListener(r -> android.util.Log.d("TEST_PUSH","ok"))
                .addOnFailureListener(e -> android.util.Log.e("TEST_PUSH","fail", e));
    }

    private void loadProfile() {
        userRepo.loadUser(uid)
                .addOnSuccessListener(data -> {
                    if (!isAdded()) return;
                    if (data != null) {
                        Object n = data.get("fullName");
                        Object p = data.get("phone");
                        if (etName != null && n != null)  etName.setText(String.valueOf(n));
                        if (etPhone != null && p != null) etPhone.setText(String.valueOf(p));
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    showError("Profil yüklenemedi: " + (e != null && e.getMessage()!=null ? e.getMessage() : ""));
                });
    }

    private void listenUserCards(String uid) {
        userReg = db.collection("users").document(uid)
                .addSnapshotListener((snap, e) -> {
                    if (!isAdded()) return;
                    if (e != null || snap == null) return;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> iyz = (Map<String, Object>) snap.get("iyzico");
                    renderCards(iyz);
                });
    }

    private void renderCards(Map<String, Object> iyz) {
        if (llCards == null || !isAdded()) return;
        llCards.removeAllViews();

        if (iyz == null) { addCardRow("Kayıtlı kart yok."); return; }

        @SuppressWarnings("unchecked")
        Map<String, Object> cards = (Map<String, Object>) iyz.get("cards");
        if (cards == null || cards.isEmpty()) { addCardRow("Kayıtlı kart yok."); return; }

        for (Map.Entry<String, Object> e : cards.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) e.getValue();
            String last4  = c.get("lastFour") != null ? String.valueOf(c.get("lastFour")) : "••••";
            String bank   = c.get("bank") != null ? String.valueOf(c.get("bank")) : "";
            String scheme = c.get("scheme") != null ? String.valueOf(c.get("scheme")) : "";
            addCardRow((bank + " " + scheme + " •••• " + last4).trim());
        }
    }

    private void addCardRow(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setPadding(0, 12, 0, 12);
        llCards.addView(tv);
    }

    private void startAddCardFlow() {
        Map<String, Object> payload = new HashMap<>();
        // Her zaman prod callback kullan
        payload.put("callbackBase", CALLBACK_BASE_PROD);

        functions.getHttpsCallable("iyziInitCardSave")
                .call(payload)
                .addOnSuccessListener(r -> {
                    if (!isAdded()) return;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> res = (Map<String, Object>) r.getData();
                    String opsId = res != null ? (String) res.get("opsId") : null;
                    String html  = res != null ? (String) res.get("checkoutFormContent") : null;
                    if (opsId == null || html == null) {
                        Toast.makeText(requireContext(), "Form açılamadı.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    PaymentActivity.startCardSave(requireContext(), opsId, html);
                })
                .addOnFailureListener(err -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Hata: " + (err != null && err.getMessage()!=null ? err.getMessage() : ""), Toast.LENGTH_LONG).show();
                });
    }

    @Override public void onDestroyView() {
        if (userReg != null) userReg.remove();
        super.onDestroyView();
    }

    private void showSuccess(String msg) { showMsg(msg, true); }
    private void showError(String msg) { showMsg(msg, false); }
    private void showMsg(String msg, boolean success) {
        if (!isAdded()) return;
        View root = getView();
        if (tvMsg != null) {
            tvMsg.setVisibility(View.VISIBLE);
            tvMsg.setText(msg);
            int color = ContextCompat.getColor(requireContext(),
                    success ? R.color.tutorist_success : R.color.tutorist_error);
            tvMsg.setTextColor(color);
        } else if (root != null) {
            Snackbar.make(root, msg, success ? Snackbar.LENGTH_SHORT : Snackbar.LENGTH_LONG).show();
        }
    }
}
