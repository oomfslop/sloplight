package com.limelight;

/** Names shared by the app screens, shortcuts and the stream (what AppView used to define) */
public final class AppExtras {
    private AppExtras() {}

    /** The SharedPreferences file of each host's hidden apps (by host UUID) */
    public static final String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public static final String NAME_EXTRA = "Name";
    public static final String UUID_EXTRA = "UUID";
    public static final String NEW_PAIR_EXTRA = "NewPair";
    public static final String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    /** The "Apps & windows" tile: host windows as workspaces (Sloplight hosts that allow this device) */
    public static final int DESK_APP_ID = -7;
}
