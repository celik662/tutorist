package com.example.tutorist.ui.student;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.tutorist.R;

public class ChooseSlotActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_choose_slot);

        String teacherId = getIntent().getStringExtra("teacherId");
        TextView tv = findViewById(R.id.tvInfo);
        tv.setText("Takvim ekranı (stub). teacherId = " + teacherId);

        findViewById(R.id.btnDummy).setOnClickListener(v -> {
            Toast.makeText(this, "Burada takvime yönleneceğiz ✨", Toast.LENGTH_SHORT).show();
            finish(); // şimdilik kapan
        });
    }
}
