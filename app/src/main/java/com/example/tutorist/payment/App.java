package com.example.tutorist.payment;

import android.app.Application;

import com.google.firebase.functions.FirebaseFunctions;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        FirebaseFunctions fn = FirebaseFunctions.getInstance("europe-west1");
        fn.useEmulator("10.0.2.2", 5001);
    }
}