package com.example.tutorist.ui.student;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.tutorist.R;
import com.example.tutorist.repo.UserRepo;
import com.example.tutorist.ui.auth.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Map;

public class StudentProfileFragment extends Fragment {

    private EditText etName, etPhone;
    private TextView tvMsg;
    private final UserRepo userRepo = new UserRepo();
    private final FirebaseAuth auth = FirebaseAuth.getInstance();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inf.inflate(R.layout.fragment_student_profile, container, false);
        etName = v.findViewById(R.id.etName);
        etPhone= v.findViewById(R.id.etPhone);
        tvMsg  = v.findViewById(R.id.tvMsg);
        Button btnSave = v.findViewById(R.id.btnSave);
        Button btnLogout = v.findViewById(R.id.btnLogout);

        String uid = auth.getUid();
        userRepo.loadUser(uid).addOnSuccessListener(data -> {
            if (data != null) {
                Object n = data.get("fullName");
                Object p = data.get("phone");
                if (n != null) etName.setText(String.valueOf(n));
                if (p != null) etPhone.setText(String.valueOf(p));
            }
        });

        btnSave.setOnClickListener(x -> {
            String name = etName.getText().toString().trim();
            String phone= etPhone.getText().toString().trim();
            userRepo.updateUserBasic(uid, name, phone)
                    .addOnSuccessListener(s -> tvMsg.setText("Kaydedildi."))
                    .addOnFailureListener(e -> tvMsg.setText("Hata: " + e.getMessage()));
        });

        btnLogout.setOnClickListener(x -> {
            auth.signOut();
            Intent i2 = new Intent(getContext(), LoginActivity.class);
            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i2);
            requireActivity().finish();
        });

        return v;
    }
}
