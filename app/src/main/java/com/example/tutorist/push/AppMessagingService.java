package com.example.tutorist.push;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.tutorist.R;
import com.example.tutorist.payment.App;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherRequestsActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;

public class AppMessagingService extends FirebaseMessagingService {
    public static final String CH_ID = "booking_updates";

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /** Login olmuş kullanıcı için token’ı yeni uid’e takar, eski sahibinden söker. */
    public static void attachCurrentTokenToUser(Context ctx, String newUid) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || newUid == null) return;

        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t -> {
            if (t == null || t.isEmpty()) return;

            String prevUid = TokenOwnerStore.getUid(ctx);
            String prevTok = TokenOwnerStore.getToken(ctx);
            FirebaseFirestore db = FirebaseFirestore.getInstance();

            if (prevUid != null && prevTok != null && !prevUid.equals(newUid)) {
                db.collection("users").document(prevUid)
                        .update("fcmTokens", FieldValue.arrayRemove(prevTok));
                db.collection("users").document(prevUid)
                        .collection("devices").document(prevTok)
                        .delete();
            }

            db.collection("users").document(newUid)
                    .set(new HashMap<String,Object>() {{
                        put("fcmTokens", FieldValue.arrayUnion(t));
                        put("updatedAt", FieldValue.serverTimestamp());
                    }}, com.google.firebase.firestore.SetOptions.merge());

            HashMap<String,Object> dev = new HashMap<>();
            dev.put("token", t);
            dev.put("platform", "android");
            dev.put("lastSeen", FieldValue.serverTimestamp());
            db.collection("users").document(newUid)
                    .collection("devices").document(t)
                    .set(dev, com.google.firebase.firestore.SetOptions.merge());

            TokenOwnerStore.save(ctx, newUid, t);
        });
    }

    /** Logout’ta çağırın: mevcut token’ı kullanıcıdan sök ve cihazda sil. */
// AppMessagingService.java
    public static com.google.android.gms.tasks.Task<Void> detachTokenFromCurrentUserAndDeleteAsync(Context ctx) {
        com.google.android.gms.tasks.TaskCompletionSource<Void> tcs =
                new com.google.android.gms.tasks.TaskCompletionSource<>();

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { tcs.setResult(null); return tcs.getTask(); }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        FirebaseMessaging fm = FirebaseMessaging.getInstance();

        fm.getToken().addOnSuccessListener(tok -> {
            // Firestore’dan düş
            if (tok != null && !tok.isEmpty()) {
                db.collection("users").document(u.getUid())
                        .update("fcmTokens", FieldValue.arrayRemove(tok))
                        .addOnCompleteListener(rm1 -> {
                            // opsiyonel: devices/{token} aynasını da sil
                            db.collection("users").document(u.getUid())
                                    .collection("devices").document(tok).delete();

                            // Cihazdan da sil
                            fm.deleteToken()
                                    .addOnCompleteListener(x -> tcs.setResult(null));
                        });
            } else {
                // token yoksa da tamam say
                tcs.setResult(null);
            }
        }).addOnFailureListener(e -> {
            // yine de signOut’a izin verelim; loglayıp tamamla
            android.util.Log.w("AppMessagingService", "getToken failed on logout: " + e);
            tcs.setResult(null);
        });

        return tcs.getTask();
    }


    /** Eski alışkanlık için kısayol: login sonrası çağırabilirsiniz. */
    public static void syncCurrentFcmToken() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        attachCurrentTokenToUser(App.get(), u.getUid());
    }

    @Override public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) attachCurrentTokenToUser(getApplicationContext(), u.getUid());
    }

    @Override public void onMessageReceived(@NonNull RemoteMessage msg) {
        super.onMessageReceived(msg);

        String title = msg.getNotification() != null ? msg.getNotification().getTitle() : msg.getData().get("title");
        String body  = msg.getNotification() != null ? msg.getNotification().getBody()  : msg.getData().get("body");
        String kind  = msg.getData().get("kind");
        String roomUrl = msg.getData().get("roomUrl");

        PendingIntent pi;
        if ("lessonReminder".equals(kind) && roomUrl != null && !roomUrl.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 2001, view,
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            if (title == null) title = "Ders başlıyor";
            if (body == null)  body  = "Toplantı odasına bağlanmak için dokun.";
        } else if ("bookingCreated".equals(kind)) {
            Intent i = new Intent(this, TeacherRequestsActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 1001, i,
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            if (title == null) title = "Yeni ders isteği";
        } else {
            Intent i = new Intent(this, StudentMainActivity.class);
            i.putExtra("openTab", "history");
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 1002, i,
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title != null ? title : "Tutorist")
                .setContentText(body != null ? body : "")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(this).notify((int) System.currentTimeMillis(), b.build());
    }
}
