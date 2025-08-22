package com.example.tutorist.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherMainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class RoleSelectActivity extends AppCompatActivity {

    private RadioGroup rg;
    private FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_role_select);
        rg = findViewById(R.id.rgRole);
        Button btn = findViewById(R.id.btnContinue);

        btn.setOnClickListener(v -> saveRoleAndProceed());
    }

    private void saveRoleAndProceed() {
        int id = rg.getCheckedRadioButtonId();
        String role = (id == R.id.rbTeacher) ? "teacher" : "student";

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        DocumentReference userRef = db.collection("users").document(uid);
        Map<String, Object> data = new HashMap<>();
        data.put("role", role);
        data.put("email", FirebaseAuth.getInstance().getCurrentUser().getEmail());

        userRef.set(data, SetOptions.merge())
                .addOnSuccessListener(v -> {
                    if ("teacher".equals(role)) {
                        startActivity(new Intent(this, TeacherMainActivity.class));
                    } else {
                        startActivity(new Intent(this, StudentMainActivity.class));
                    }
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Rol kaydı başarısız: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}
