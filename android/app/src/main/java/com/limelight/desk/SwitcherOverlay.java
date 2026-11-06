package com.limelight.desk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The launcher / window switcher over the stream: workspaces, the host's
 * other windows and its apps, filtered as you type. Keys come from the
 * stream activity (it sees every key first), touches land here directly.
 * Also shows the little workspace badge after a switch.
 */
public class SwitcherOverlay extends FrameLayout {
    private static final int BG = 0xE0121216;
    private static final int CARD = 0xFF1C1C22;
    private static final int SELECTED = 0x33FFFFFF;
    private static final int TEXT = 0xFFF2F2F5;
    private static final int DIM = 0xFF9A9AA6;
    private static final int ACCENT = 0xFF7C9CFF;
    private static final int APPS_UNFILTERED = 12;
    private static final int MATCHES = 60;

    private final Workspaces ws;
    private final Runnable onHidden;

    private final LinearLayout card;
    private final TextView search;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView footer;
    private final TextView hud;

    private final StringBuilder query = new StringBuilder();
    /** Opened for an empty workspace: what is picked goes there */
    private int targetSlot = 0;
    private int selected = 0;
    private final List<Entry> entries = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();

    private final Runnable hideHud = new Runnable() {
        @Override
        public void run() {
            hud.animate().alpha(0f).setDuration(250).withEndAction(() -> hud.setVisibility(GONE)).start();
        }
    };

    private static class Entry {
        static final int WORKSPACE = 0, EMPTY = 1, WINDOW = 2, APP = 3, HEADER = 4, NOTE = 5;
        final int kind;
        final int slot;
        final String title;
        final String detail;
        final Workspaces.Window window;
        final Workspaces.App app;

        Entry(int kind, int slot, String title, String detail, Workspaces.Window window, Workspaces.App app) {
            this.kind = kind;
            this.slot = slot;
            this.title = title;
            this.detail = detail;
            this.window = window;
            this.app = app;
        }

        boolean selectable() {
            return kind != HEADER && kind != NOTE;
        }
    }

    public SwitcherOverlay(Context context, Workspaces ws, Runnable onHidden) {
        super(context);
        this.ws = ws;
        this.onHidden = onHidden;

        // The panel: dimmed stream behind, a card in the middle
        FrameLayout panel = new FrameLayout(context);
        panel.setBackgroundColor(BG);
        panel.setClickable(true);
        panel.setOnClickListener(v -> hide());
        addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD);
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setElevation(dp(12));
        LayoutParams cardLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardLp.setMargins(dp(24), dp(24), dp(24), dp(24));
        panel.addView(card, cardLp);

        search = new TextView(context);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.setTextColor(TEXT);
        search.setSingleLine(true);
        search.setEllipsize(TextUtils.TruncateAt.START);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(search, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View rule = new View(context);
        rule.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        ruleLp.setMargins(0, dp(2), 0, dp(4));
        card.addView(rule, ruleLp);

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new TextView(context);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setTextColor(DIM);
        footer.setPadding(dp(12), dp(6), dp(12), dp(2));
        footer.setOnClickListener(v -> {
            if (!com.limelight.KeyboardAccessibilityService.running) {
                try {
                    getContext().startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });
        card.addView(footer);

        hud = new TextView(context);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hud.setTextColor(TEXT);
        hud.setTypeface(Typeface.DEFAULT_BOLD);
        hud.setSingleLine(true);
        hud.setEllipsize(TextUtils.TruncateAt.END);
        hud.setMaxWidth(dp(420));
        hud.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable hudBg = new GradientDrawable();
        hudBg.setColor(0xCC1C1C22);
        hudBg.setCornerRadius(dp(18));
        hud.setBackground(hudBg);
        hud.setVisibility(GONE);
        LayoutParams hudLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hudLp.topMargin = dp(16);
        addView(hud, hudLp);

        panel.setVisibility(GONE);
    }

    private View panel() {
        return getChildAt(0);
    }

    public boolean isShowing() {
        return panel().getVisibility() == VISIBLE;
    }

    /** Opens the switcher; slot > 0: for that empty workspace */
    public void show(int slot) {
        targetSlot = slot;
        query.setLength(0);
        selected = -1;
        updateFooter();
        panel().setVisibility(VISIBLE);
        ws.refreshHostWindows();
        if (ws.apps().isEmpty()) ws.refreshApps();
        rebuild();
        // The card stays inside the screen: at most 85% of its height
        int max = (int) (getHeight() * 0.85f);
        if (max > 0) {
            card.measure(MeasureSpec.makeMeasureSpec(Math.min(getWidth() - dp(48), dp(720)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = Math.min(Math.max(getWidth() - dp(48), dp(280)), dp(720));
        lp.height = max > 0 ? max : LayoutParams.WRAP_CONTENT;
        card.setLayoutParams(lp);
    }

    private void updateFooter() {
        boolean meta = com.limelight.KeyboardAccessibilityService.running;
        String m = meta ? "Win" : "Alt";
        String text = m + "+1–9 switch · " + m + "+Shift+1–9 move window there · " + m + "+Ctrl+1–9 swap · " + m + "+arrows focus · " + m + "+Shift+arrows move · "
                + m + "+D this · " + m + "+Shift+Q close · Shift+Enter open beside · Del send back to PC";
        if (!meta) {
            text += "\nTap here and turn on “" + getContext().getString(com.limelight.R.string.keyboard_service_label) + "” to use Win instead of Alt";
        }
        footer.setText(text);
    }

    public void hide() {
        if (!isShowing()) return;
        panel().setVisibility(GONE);
        onHidden.run();
    }

    /** Workspaces changed: the list follows */
    public void refresh() {
        if (isShowing()) rebuild();
    }

    public void showHud(String text) {
        removeCallbacks(hideHud);
        hud.animate().cancel();
        hud.setText(text);
        hud.setAlpha(1f);
        hud.setVisibility(VISIBLE);
        postDelayed(hideHud, 1100);
    }

    /** A key while the switcher is open; true when it was used */
    public boolean onKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                hide();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_TAB:
                move(event.isShiftPressed() ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                move(-8);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                move(8);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                activate(selected, event.isShiftPressed());
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (query.length() > 0) {
                    if (event.isCtrlPressed() || event.isAltPressed()) query.setLength(0);
                    else query.setLength(query.length() - 1);
                    selected = -1;
                    rebuild();
                }
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                Entry e = selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
                if (e != null && e.kind == Entry.WORKSPACE) {
                    if (event.isShiftPressed()) ws.close(e.slot);
                    else ws.release(e.slot);
                }
                return true;
            }
            default:
                break;
        }
        // Digits with nothing typed: that workspace
        if (query.length() == 0 && !event.isShiftPressed() && event.getKeyCode() >= KeyEvent.KEYCODE_1 && event.getKeyCode() <= KeyEvent.KEYCODE_9) {
            int n = event.getKeyCode() - KeyEvent.KEYCODE_0;
            if (!ws.isEmpty(n)) {
                ws.switchTo(n);
                hide();
            } else {
                targetSlot = n;
                rebuild();
            }
            return true;
        }
        int ch = event.getUnicodeChar();
        if (ch >= 0x20 && !event.isCtrlPressed() && !event.isMetaPressed()) {
            query.appendCodePoint(ch);
            selected = -1;
            rebuild();
        }
        return true;
    }

    private void move(int by) {
        if (entries.isEmpty()) return;
        int i = selected;
        int step = by > 0 ? 1 : -1;
        int left = Math.abs(by);
        while (left > 0) {
            int next = i + step;
            while (next >= 0 && next < entries.size() && !entries.get(next).selectable()) next += step;
            if (next < 0 || next >= entries.size()) break;
            i = next;
            left--;
        }
        select(i);
    }

    private void select(int i) {
        if (selected >= 0 && selected < rows.size()) rows.get(selected).setBackground(null);
        selected = i;
        if (i >= 0 && i < rows.size()) {
            View row = rows.get(i);
            row.setBackground(rounded(SELECTED));
            scroll.post(() -> {
                int top = row.getTop() - dp(8), bottom = row.getBottom() + dp(8);
                if (top < scroll.getScrollY()) scroll.smoothScrollTo(0, top);
                else if (bottom > scroll.getScrollY() + scroll.getHeight()) scroll.smoothScrollTo(0, bottom - scroll.getHeight());
            });
        }
    }

    private void activate(int i) {
        activate(i, false);
    }

    /** beside: a window or app opens next to the windows in front (or in the
     *  workspace picked) instead of in a workspace of its own */
    private void activate(int i, boolean beside) {
        if (i < 0 || i >= entries.size()) return;
        Entry e = entries.get(i);
        switch (e.kind) {
            case Entry.WORKSPACE:
                ws.switchTo(e.slot);
                hide();
                break;
            case Entry.EMPTY:
                targetSlot = e.slot;
                query.setLength(0);
                selected = -1;
                rebuild();
                break;
            case Entry.WINDOW:
                ws.bring(e.window.hwnd, targetSlot, beside);
                hide();
                break;
            case Entry.APP:
                ws.launch(e.app, targetSlot, beside);
                hide();
                break;
            default:
                break;
        }
    }

    private void rebuild() {
        String q = query.toString().toLowerCase(Locale.ROOT).trim();
        Entry keep = selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
        entries.clear();

        if (q.isEmpty()) {
            search.setText(targetSlot > 0 ? "Open in workspace " + targetSlot + "…  type to search" : "Search apps and windows");
            search.setTextColor(DIM);
        } else {
            search.setText(query);
            search.setTextColor(TEXT);
        }

        // Workspaces
        List<Entry> group = new ArrayList<>();
        for (int n = 1; n <= Workspaces.COUNT; n++) {
            Workspaces.Window w = ws.windowIn(n);
            if (w != null) {
                // Side by side ones: all their names, left to right; the focused one's icon
                List<Workspaces.Window> tiles = ws.windowsIn(n);
                StringBuilder title = new StringBuilder();
                boolean match = false;
                for (Workspaces.Window t : tiles) {
                    if (title.length() > 0) title.append("  │  ");
                    title.append(t.label());
                    match = match || matches(q, t.label(), t.process);
                }
                String detail = tiles.size() > 1 ? tiles.size() + " side by side" : processName(w.process);
                if (match) group.add(new Entry(Entry.WORKSPACE, n, title.toString(), detail, w, null));
            } else if (q.isEmpty() && n == firstEmptyToOffer()) {
                group.add(new Entry(Entry.EMPTY, n, "Empty", "pick something for it", null, null));
            }
        }
        if (!group.isEmpty() && targetSlot == 0) {
            entries.add(new Entry(Entry.HEADER, 0, "Workspaces", null, null, null));
            entries.addAll(group);
        }

        // The host's other windows
        group.clear();
        for (Workspaces.Window w : ws.otherWindows()) {
            if (matches(q, w.label(), w.process)) group.add(new Entry(Entry.WINDOW, 0, w.label(), processName(w.process), w, null));
        }
        if (!group.isEmpty()) {
            entries.add(new Entry(Entry.HEADER, 0, "On the PC's screen", null, null, null));
            entries.addAll(group);
        }

        // Apps
        group.clear();
        List<Workspaces.App> apps = ws.apps();
        int limit = q.isEmpty() ? APPS_UNFILTERED : MATCHES;
        for (Workspaces.App a : apps) {
            if (group.size() >= limit) break;
            if (matches(q, a.name, null)) group.add(new Entry(Entry.APP, 0, a.name, "", null, a));
        }
        if (!group.isEmpty()) {
            entries.add(new Entry(Entry.HEADER, 0, "Apps", null, null, null));
            entries.addAll(group);
            if (q.isEmpty() && apps.size() > limit) {
                entries.add(new Entry(Entry.NOTE, 0, "Type to search all " + apps.size() + " apps", null, null, null));
            }
        } else if (ws.appsLoading()) {
            entries.add(new Entry(Entry.NOTE, 0, "Loading the PC's apps…", null, null, null));
        } else if (!q.isEmpty() && entries.isEmpty()) {
            entries.add(new Entry(Entry.NOTE, 0, "Nothing matches “" + query + "”", null, null, null));
        }

        list.removeAllViews();
        rows.clear();
        for (int i = 0; i < entries.size(); i++) {
            View row = makeRow(entries.get(i), i);
            rows.add(row);
            list.addView(row);
        }

        // The selection stays on the same thing, or goes to the first one
        int sel = -1;
        if (keep != null) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.kind == keep.kind && e.slot == keep.slot && TextUtils.equals(e.title, keep.title)) {
                    sel = i;
                    break;
                }
            }
        }
        if (sel < 0) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                // Nothing typed: the workspace that isn't in front is the likely pick
                if (e.selectable() && !(q.isEmpty() && e.kind == Entry.WORKSPACE && e.slot == ws.current() && hasOtherWorkspace())) {
                    sel = i;
                    break;
                }
            }
        }
        selected = -1;
        select(sel);
    }

    private boolean hasOtherWorkspace() {
        for (int n = 1; n <= Workspaces.COUNT; n++) {
            if (n != ws.current() && !ws.isEmpty(n)) return true;
        }
        return false;
    }

    private int firstEmptyToOffer() {
        return targetSlot > 0 ? targetSlot : ws.firstFree();
    }

    private View makeRow(Entry e, int index) {
        Context c = getContext();
        if (e.kind == Entry.HEADER || e.kind == Entry.NOTE) {
            TextView t = new TextView(c);
            t.setText(e.kind == Entry.HEADER ? e.title.toUpperCase(Locale.ROOT) : e.title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, e.kind == Entry.HEADER ? 11 : 13);
            t.setTextColor(DIM);
            if (e.kind == Entry.HEADER) t.setLetterSpacing(0.08f);
            t.setPadding(dp(12), dp(e.kind == Entry.HEADER ? 10 : 6), dp(12), dp(4));
            return t;
        }
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(12), dp(6));
        row.setMinimumHeight(dp(48));

        TextView badge = new TextView(c);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        if (e.kind == Entry.WORKSPACE || e.kind == Entry.EMPTY) {
            badge.setText(String.valueOf(e.slot));
            boolean front = e.kind == Entry.WORKSPACE && e.slot == ws.current();
            badge.setTextColor(front ? Color.BLACK : TEXT);
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(dp(6));
            if (front) b.setColor(ACCENT);
            else b.setStroke(dp(1), 0x55FFFFFF);
            badge.setBackground(b);
        }
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        badgeLp.rightMargin = dp(10);
        row.addView(badge, badgeLp);

        ImageView icon = new ImageView(c);
        Bitmap bmp = null;
        if (e.window != null) bmp = ws.icon(e.window.icon, e.window.hwnd);
        else if (e.app != null && !e.app.icon.isEmpty()) bmp = ws.icon(e.app.icon, null);
        if (bmp != null) icon.setImageBitmap(bmp);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        TextView title = new TextView(c);
        title.setText(e.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(e.kind == Entry.EMPTY ? DIM : TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        if (e.detail != null && !e.detail.isEmpty()) {
            TextView detail = new TextView(c);
            detail.setText(e.detail);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            detail.setTextColor(DIM);
            detail.setSingleLine(true);
            detail.setPadding(dp(12), 0, 0, 0);
            row.addView(detail);
        }

        row.setClickable(true);
        row.setOnClickListener(v -> activate(index));
        if (e.kind == Entry.WORKSPACE) {
            row.setOnLongClickListener(v -> {
                PopupMenu menu = new PopupMenu(c, v);
                menu.getMenu().add(0, 1, 0, "Send back to the PC's screen");
                menu.getMenu().add(0, 2, 1, "Close window");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) ws.release(e.slot);
                    else ws.close(e.slot);
                    return true;
                });
                menu.show();
                return true;
            });
        } else if ((e.kind == Entry.WINDOW || e.kind == Entry.APP) && ws.canTileBeside()) {
            row.setOnLongClickListener(v -> {
                PopupMenu menu = new PopupMenu(c, v);
                menu.getMenu().add(0, 1, 0, "Open in a workspace of its own");
                menu.getMenu().add(0, 2, 1, "Open beside the window in front");
                menu.setOnMenuItemClickListener(item -> {
                    activate(index, item.getItemId() == 2);
                    return true;
                });
                menu.show();
                return true;
            });
        }
        return row;
    }

    private static boolean matches(String q, String a, String b) {
        if (q.isEmpty()) return true;
        return (a != null && a.toLowerCase(Locale.ROOT).contains(q)) || (b != null && b.toLowerCase(Locale.ROOT).contains(q));
    }

    private static String processName(String p) {
        if (p == null) return "";
        return p.toLowerCase(Locale.ROOT).endsWith(".exe") ? p.substring(0, p.length() - 4) : p;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
