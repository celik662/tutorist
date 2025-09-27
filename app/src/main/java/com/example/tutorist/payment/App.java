package com.example.tutorist.payment;

import android.app.Application;

import com.example.tutorist.BuildConfig; // <-- burası önemli!
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.functions.FirebaseFunctions;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();

        // (Varsa emülatör kullanımın)
        FirebaseFunctions fn = FirebaseFunctions.getInstance("europe-west1");
        fn.useEmulator("10.0.2.2", 5001);

        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this);

        FirebaseApp.initializeApp(this);

        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
        if (BuildConfig.DEBUG) {
            appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
            );
        } else {
            appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
            );
        }
    }
}
