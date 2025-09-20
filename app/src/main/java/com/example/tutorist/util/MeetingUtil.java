// MeetingUtil.java
package com.example.tutorist.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MeetingUtil {
    public static void joinDailyMeeting(Context ctx, String bookingId) {
        Log.d("MeetingUtil", "getJoinToken call bookingId=" + bookingId);

        // *** Bölgeyi sabitle: europe-west1 ***
        FirebaseFunctions functions = FirebaseFunctions.getInstance("europe-west1");

        // Parametreler: bookingId + debug=true (test için pencere kontrolünü bypass)
        Map<String, Object> data = new HashMap<>();
        data.put("bookingId", bookingId);
        data.put("debug", true);

        functions.getHttpsCallable("getJoinToken")
                .call(data)
                .addOnSuccessListener(r -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> res = (Map<String, Object>) r.getData();
                    String roomUrl = (String) res.get("roomUrl");
                    String token   = (String) res.get("token");
                    Log.d("MeetingUtil", "getJoinToken OK roomUrl=" + roomUrl);

                    if (roomUrl == null || token == null) {
                        Toast.makeText(ctx, "Link hazırlanamadı.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Tarayıcıyı aç
                    ctx.startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl + "?t=" + token))
                    );
                })
                .addOnFailureListener(e -> {
                    String msg;
                    if (e instanceof FirebaseFunctionsException) {
                        FirebaseFunctionsException fe = (FirebaseFunctionsException) e;
                        msg = fe.getCode().name() + " - " + (fe.getMessage() != null ? fe.getMessage() : "");
                    } else {
                        msg = e.getMessage();
                    }
                    Log.e("MeetingUtil", "getJoinToken FAIL: " + msg, e);

                    if (msg != null && msg.contains("Not started")) {
                        Toast.makeText(ctx, "Ders henüz başlamadı.", Toast.LENGTH_LONG).show();
                    } else if (msg != null && msg.contains("Time window")) {
                        Toast.makeText(ctx, "Ders süresi sona erdi.", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(ctx, "Bağlantı hatası: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    public static void startCloudRecording(Context ctx, String bookingId) {
        FirebaseFunctions.getInstance("europe-west1")
                .getHttpsCallable("startRecording")
                .call(Collections.singletonMap("bookingId", bookingId))
                .addOnSuccessListener(r -> Toast.makeText(ctx, "Kayıt başlatıldı.", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(ctx, "Kayıt başlatılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    public static void stopCloudRecording(Context ctx, String bookingId) {
        FirebaseFunctions.getInstance("europe-west1")
                .getHttpsCallable("stopRecording")
                .call(Collections.singletonMap("bookingId", bookingId))
                .addOnSuccessListener(r -> Toast.makeText(ctx, "Kayıt durduruldu.", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(ctx, "Kayıt durdurulamadı: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

}
