package com.winlator;

import android.app.Application;

import com.winlator.core.CrashReporter;

public class WinRunnerApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.apply(this);
    }
}
