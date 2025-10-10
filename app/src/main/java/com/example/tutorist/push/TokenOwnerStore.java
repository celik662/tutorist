package com.example.tutorist.push;

import android.content.Context;
import android.content.SharedPreferences;

public class TokenOwnerStore {
    private static final String PREF = "fcm_owner_prefs";
    private static final String K_UID_FOR_TOKEN = "uid_for_token";
    private static final String K_LAST_TOKEN = "last_token";

    public static void save(Context ctx, String uid, String token){
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putString(K_UID_FOR_TOKEN, uid).putString(K_LAST_TOKEN, token).apply();
    }
    public static String getUid(Context ctx){
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_UID_FOR_TOKEN, null);
    }
    public static String getToken(Context ctx){
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_LAST_TOKEN, null);
    }
    public static void clear(Context ctx){
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
