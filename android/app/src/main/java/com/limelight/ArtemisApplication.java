package com.limelight;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import android.widget.Toast;

import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // The app's colors from the wallpaper (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this);
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }
    }
}