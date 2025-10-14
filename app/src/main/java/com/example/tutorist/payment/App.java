package com.example.tutorist.payment;

import android.app.Application;

import com.google.firebase.BuildConfig;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

public class App extends Application {
    private static App INSTANCE;

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;

        FirebaseApp.initializeApp(this);
        if (BuildConfig.DEBUG) {
            FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
            appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
            );
        }

        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
        // Prod:
        appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance());
    }

    /** Uygulama context’i için kolay erişim */
    public static App get() { return INSTANCE; }
}
