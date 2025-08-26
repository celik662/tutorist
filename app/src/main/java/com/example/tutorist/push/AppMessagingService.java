package com.example.tutorist.push;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.example.tutorist.R;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.auth.FirebaseAuth;

import java.util.HashMap;
import java.util.Map;

public class AppMessagingService extends FirebaseMessagingService {

    public static final String CH_ID = "booking_updates";

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Rezervasyon Bildirimleri",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Rezervasyon ve istek bildirimleri");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        // Basit cihaz id’si: FCM token’ı kullan (istersen Settings.Secure.ANDROID_ID da olur)
        String deviceId = token;
        Map<String,Object> data = new HashMap<>();
        data.put("token", token);
        data.put("platform", "android");
        data.put("lastSeen", com.google.firebase.firestore.FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("devices").document(deviceId)
                .set(data);
    }

    @Override public void onMessageReceived(@NonNull RemoteMessage msg) {
        super.onMessageReceived(msg);

        String title = msg.getNotification() != null ? msg.getNotification().getTitle() : msg.getData().get("title");
        String body  = msg.getNotification() != null ? msg.getNotification().getBody()  : msg.getData().get("body");

        // Deep link amacıyla tür ve id al (isteğe bağlı)
        String kind = msg.getData().get("kind"); // bookingCreated | bookingAccepted | bookingDeclined
        String bookingId = msg.getData().get("bookingId");

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.message) // bir vektör ikon ekle
                .setContentTitle(title != null ? title : "Tutorist")
                .setContentText(body != null ? body : "")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        // Tıklayınca nereye gideceği:
        android.app.PendingIntent pi = null;
        if ("bookingCreated".equals(kind)) {
            // Öğretmen — Gelen Talepler ekranı
            Intent i = new Intent(this, com.example.tutorist.ui.teacher.TeacherRequestsActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = android.app.PendingIntent.getActivity(this, 1001, i,
                    android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        } else {
            // Öğrenci — History tab’ını açan ana aktivite (senin main yapına göre düzenle)
            Intent i = new Intent(this, com.example.tutorist.ui.student.StudentMainActivity.class);
            i.putExtra("openTab", "history");
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = android.app.PendingIntent.getActivity(this, 1002, i,
                    android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        }
        if (pi != null) b.setContentIntent(pi);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(this).notify((int)System.currentTimeMillis(), b.build());
    }
}
