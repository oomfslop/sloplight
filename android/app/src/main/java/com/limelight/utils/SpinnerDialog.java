package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.loadingindicator.LoadingIndicator;

public class SpinnerDialog implements Runnable,OnCancelListener {
    private final String title;
    private final String message;
    private final Activity activity;
    private AlertDialog progress;
    private TextView messageView;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.progress = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    if (dialog.progress.isShowing()) {
                        dialog.progress.dismiss();
                    }
                }
            }
        }
    }

    public void dismiss()
    {
        // Running again with progress != null will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (messageView != null) messageView.setText(message);
            }
        });
    }

    @Override
    public void run() {

        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (progress == null)
        {
            // A Material dialog: the Expressive loading indicator beside the message
            float density = activity.getResources().getDisplayMetrics().density;
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Math.round(24 * density);
            content.setPadding(pad, Math.round(8 * density), pad, Math.round(8 * density));
            LoadingIndicator indicator = new LoadingIndicator(activity);
            content.addView(indicator, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            messageView = new TextView(activity);
            messageView.setText(message);
            messageView.setTextColor(MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurfaceVariant));
            messageView.setTextSize(16);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            textParams.setMarginStart(Math.round(16 * density));
            content.addView(messageView, textParams);

            progress = new MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setView(content)
                    .create();
            progress.setOnCancelListener(this);

            // If we want to finish the activity when this is killed, make it cancellable
            if (finish)
            {
                progress.setCancelable(true);
                progress.setCanceledOnTouchOutside(false);
            }
            else
            {
                progress.setCancelable(false);
            }

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                progress.show();
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && progress.isShowing()) {
                    progress.dismiss();
                }
            }
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish();
    }
}
