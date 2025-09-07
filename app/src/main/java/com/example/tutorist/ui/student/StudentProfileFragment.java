package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.tutorist.BuildConfig;
import com.example.tutorist.R;
import com.example.tutorist.payment.PaymentActivity;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;

public class StudentProfileFragment extends Fragment {

    private static final String FUNCTIONS_REGION = "europe-west1";
    private static final String CALLBACK_BASE_DEBUG = "http://10.0.2.2:5001/tutorist-f2a46";
    private static final String CALLBACK_BASE_PROD  = "https://europe-west1-tutorist-f2a46.cloudfunctions.net";

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

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_student_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        etName    = v.findViewById(R.id.etName);
        etPhone   = v.findViewById(R.id.etPhone);
        tvMsg     = v.findViewById(R.id.tvMsg);       // layout’ta yoksa null olabilir (bilerek)
        llCards   = v.findViewById(R.id.llCards);
        btnAddCard= v.findViewById(R.id.btnAddCard);
        btnSave   = v.findViewById(R.id.btnSave);
        btnLogout = v.findViewById(R.id.btnLogout);

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
            String name  = etName != null ? etName.getText().toString().trim() : "";
            String phone = etPhone != null ? etPhone.getText().toString().trim() : "";

            if (name.isEmpty()) { showError("Lütfen ad-soyad girin."); return; }

            btnSave.setEnabled(false);

            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> {
                        if (!isAdded()) return;
                        showSuccess("Kaydedildi ✅");
                        btnSave.setEnabled(true);
                    })
                    .addOnFailureListener(e -> {
                        if (!isAdded()) return;
                        showError("Kaydedilemedi: " + (e != null && e.getMessage() != null ? e.getMessage() : ""));
                        btnSave.setEnabled(true);
                    });
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
            if (!isAdded()) return;
            if (data != null) {
                Object n = data.get("fullName");
                Object p = data.get("phone");
                if (etName != null && n != null)  etName.setText(String.valueOf(n));
                if (etPhone != null && p != null) etPhone.setText(String.valueOf(p));
            }
        }).addOnFailureListener(e -> {
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
        payload.put("callbackBase", BuildConfig.DEBUG ? CALLBACK_BASE_DEBUG : CALLBACK_BASE_PROD);

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

    /* ---------- mesaj yardımcıları ---------- */

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
