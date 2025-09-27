package com.example.tutorist.push;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.tutorist.R;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherRequestsActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

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

        // 1) Eski yapın: devices alt koleksiyonu
        String deviceId = token;
        Map<String,Object> data = new HashMap<>();
        data.put("token", token);
        data.put("platform", "android");
        data.put("lastSeen", FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("devices").document(deviceId)
                .set(data);

        // 2) Yeni: functions’ın okuduğu dizi alanı
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .update("fcmTokens", FieldValue.arrayUnion(token));
    }

    @Override public void onMessageReceived(@NonNull RemoteMessage msg) {
        super.onMessageReceived(msg);

        // Başlık/içerik
        String title = msg.getNotification() != null ? msg.getNotification().getTitle() : msg.getData().get("title");
        String body  = msg.getNotification() != null ? msg.getNotification().getBody()  : msg.getData().get("body");

        // Türler:
        // - bookingCreated (öğretmene istek)
        // - bookingAccepted / general
        // - lessonReminder (functions: bookingId, roomUrl, subjectName, startAt)
        String kind      = msg.getData().get("kind");
        String bookingId = msg.getData().get("bookingId");
        String roomUrl   = msg.getData().get("roomUrl");

        PendingIntent pi;

        if ("lessonReminder".equals(kind) && roomUrl != null && !roomUrl.isEmpty()) {
            // 10 dk / 60 dk hatırlatması → doğrudan odayı aç
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(
                    this, 2001, view,
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT
            );

            // Varsayılan başlık/içerik gelmediyse sadeleştir
            if (title == null) title = "Ders başlıyor";
            if (body == null)  body  = "Toplantı odasına bağlanmak için dokun.";
        } else if ("bookingCreated".equals(kind)) {
            // Öğretmen — Gelen Talepler ekranı
            Intent i = new Intent(this, TeacherRequestsActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(
                    this, 1001, i,
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT
            );
            if (title == null) title = "Yeni ders isteği";
        } else {
            // Öğrenci — geçmiş/ana ekran (gerekirse openTab ile yönlendir)
            Intent i = new Intent(this, StudentMainActivity.class);
            i.putExtra("openTab", "history");
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(
                    this, 1002, i,
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT
            );
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_notification) // drawable’da küçük ikon bulunsun
                .setContentTitle(title != null ? title : "Tutorist")
                .setContentText(body != null ? body : "")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // İzin servis içinde istenemez; Activity’de isteyeceğiz.
            return;
        }
        NotificationManagerCompat.from(this)
                .notify((int) System.currentTimeMillis(), b.build());
    }
}
