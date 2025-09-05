package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.tutorist.BuildConfig;
import com.example.tutorist.R;
import com.example.tutorist.payment.PaymentActivity;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;

public class StudentProfileFragment extends Fragment {

    private static final String FUNCTIONS_REGION = "europe-west1";
    // Proje ID'ni yaz:
    private static final String CALLBACK_BASE_DEBUG =
            "http://10.0.2.2:5001/tutorist-f2a46";
    private static final String CALLBACK_BASE_PROD  =
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_student_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        etName   = v.findViewById(R.id.etName);
        etPhone  = v.findViewById(R.id.etPhone);
        tvMsg    = v.findViewById(R.id.tvMsg);
        llCards  = v.findViewById(R.id.llCards);
        btnAddCard = v.findViewById(R.id.btnAddCard);
        btnSave  = v.findViewById(R.id.btnSave);
        btnLogout= v.findViewById(R.id.btnLogout);

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();
        functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION);
        if (BuildConfig.DEBUG && BuildConfig.FUNCTIONS_HOST != null && !BuildConfig.FUNCTIONS_HOST.isEmpty()) {
            functions.useEmulator(BuildConfig.FUNCTIONS_HOST, BuildConfig.FUNCTIONS_PORT);
        }

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
            String name  = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> tvMsg.setText("Kaydedildi."))
                    .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
        });
        btnLogout.setOnClickListener(x -> {
            auth.signOut();
            startActivity(new Intent(requireContext(), LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            requireActivity().finish();
        });
    }

    private void loadProfile() {
        userRepo.loadUser(uid).addOnSuccessListener(data -> {
            if (data != null) {
                Object n = data.get("fullName");
                Object p = data.get("phone");
                if (n != null) etName.setText(String.valueOf(n));
                if (p != null) etPhone.setText(String.valueOf(p));
            }
        });
    }

    private void listenUserCards(String uid) {
        userReg = db.collection("users").document(uid)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> iyz = (Map<String, Object>) snap.get("iyzico");
                    renderCards(iyz);
                });
    }

    private void renderCards(Map<String, Object> iyz) {
        llCards.removeAllViews();
        if (iyz == null) {
            TextView tv = new TextView(requireContext());
            tv.setText("Kayıtlı kart yok.");
            llCards.addView(tv);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cards = (Map<String, Object>) iyz.get("cards");
        if (cards == null || cards.isEmpty()) {
            TextView tv = new TextView(requireContext());
            tv.setText("Kayıtlı kart yok.");
            llCards.addView(tv);
            return;
        }
        for (Map.Entry<String, Object> e : cards.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) e.getValue();
            String last4  = c.get("lastFour") != null ? String.valueOf(c.get("lastFour")) : "••••";
            String bank   = c.get("bank") != null ? String.valueOf(c.get("bank")) : "";
            String scheme = c.get("scheme") != null ? String.valueOf(c.get("scheme")) : "";

            TextView tv = new TextView(requireContext());
            tv.setText((bank + " " + scheme + " •••• " + last4).trim());
            tv.setPadding(0, 12, 0, 12);
            llCards.addView(tv);
        }
    }

    private void startAddCardFlow() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("callbackBase", BuildConfig.DEBUG ? CALLBACK_BASE_DEBUG : CALLBACK_BASE_PROD);

        functions.getHttpsCallable("iyziInitCardSave")
                .call(payload)
                .addOnSuccessListener(r -> {
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
                .addOnFailureListener(err ->
                        Toast.makeText(requireContext(), "Hata: " + err.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroyView() {
        if (userReg != null) userReg.remove();
        super.onDestroyView();
    }
}
