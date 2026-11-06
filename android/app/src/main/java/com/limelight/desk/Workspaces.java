package com.limelight.desk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App windows on a phone: host windows are moved onto the client's desk (a
 * private host display the stream shows) and fill it, one per workspace
 * (1-9). The top-most one is what the stream shows; switching workspaces
 * restacks them. Windows an app opens on the desk get a workspace of their
 * own, windows that close leave theirs.
 */
public class Workspaces {
    public static final int COUNT = 9;

    /** A host window as the switcher lists it */
    public static class Window {
        public final String hwnd;
        public final String title;
        public final String process;
        public final String icon;

        Window(String hwnd, String title, String process, String icon) {
            this.hwnd = hwnd;
            this.title = title;
            this.process = process;
            this.icon = icon;
        }

        /** "Firefox" from "firefox.exe" when the window has no title */
        public String label() {
            if (!title.isEmpty()) return title;
            String p = process.endsWith(".exe") ? process.substring(0, process.length() - 4) : process;
            return p.isEmpty() ? hwnd : p;
        }
    }

    /** A Start menu app of the host */
    public static class App {
        public final String name;
        public final String id;
        public final String icon;

        App(String name, String id, String icon) {
            this.name = name;
            this.id = id;
            this.icon = icon;
        }
    }

    public interface Listener {
        /** Workspaces or their windows changed */
        void onWorkspacesChanged();
        /** Workspace n is in front now (0: none) */
        void onSwitched(int n, Window window);
        /** No window on the desk (show the launcher) */
        void onEmpty();
        void onMessage(String text);
    }

    private final NvHTTP http;
    private final SharedPreferences prefs;
    private final String prefsKey;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private final ExecutorService fetches = Executors.newFixedThreadPool(2);

    // Guarded by this
    private final String[] slots = new String[COUNT + 1];
    private int current = 0;
    /** Workspaces in the order they were last in front, most recent first */
    private final LinkedList<Integer> recent = new LinkedList<>();
    /** Windows on the desk as of the last layout (app windows, not dialogs) */
    private final Map<String, Window> onDesk = new HashMap<>();
    /** Of those, the minimized ones (other workspaces' windows) */
    private final Set<String> minimized = new HashSet<>();
    /** Sizes asked for, so a window that won't take the desk's size isn't asked again and again */
    private final Map<String, Long> fitAsked = new HashMap<>();
    /** Windows the user can't size (fixed dialogs): placed, not maximized */
    private final Set<String> fixedSize = new HashSet<>();
    /** Each desk window's state as of the last layout: normal, maximized, minimized */
    private final Map<String, String> states = new HashMap<>();
    private int pendingLaunchSlot = 0;
    /** When the last switch was asked for or done: what the host shows just
     *  after (focus moving, windows minimizing) is the switch itself, not the user */
    private long switching = 0;
    private final java.util.concurrent.atomic.AtomicInteger switchGen = new java.util.concurrent.atomic.AtomicInteger();
    private static final long SETTLE_MS = 1500;
    private long pendingLaunchUntil = 0;
    private boolean firstLayout = true;

    private int deskW, deskH;
    private volatile boolean running = true;
    private Thread poller;

    private final Map<String, Bitmap> icons = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> iconsAsked = Collections.synchronizedSet(new HashSet<>());

    private volatile List<Window> hostWindows = new ArrayList<>();
    private volatile List<App> apps = new ArrayList<>();
    private volatile boolean appsLoading = false;

    public Workspaces(Context context, NvHTTP http, String hostUuid, Listener listener) {
        this.http = http;
        this.listener = listener;
        this.prefs = context.getSharedPreferences("workspaces", Context.MODE_PRIVATE);
        this.prefsKey = "host_" + hostUuid;
    }

    public void start() {
        poller = new Thread(this::pollLayout, "desk layout");
        poller.start();
        refreshHostWindows();
        refreshApps();
    }

    public void stop() {
        running = false;
        if (poller != null) poller.interrupt();
        actions.shutdown();
        fetches.shutdown();
    }

    // --- What the switcher shows ---

    public synchronized int current() {
        return current;
    }

    public synchronized Window windowIn(int slot) {
        String h = slots[slot];
        return h == null ? null : onDesk.get(h);
    }

    public synchronized boolean isEmpty(int slot) {
        return slots[slot] == null;
    }

    public synchronized int firstFree() {
        for (int i = 1; i <= COUNT; i++) {
            if (slots[i] == null) return i;
        }
        return 0;
    }

    /** Host windows elsewhere on the host (its own screens) */
    public List<Window> otherWindows() {
        List<Window> out = new ArrayList<>();
        synchronized (this) {
            for (Window w : hostWindows) {
                if (!onDesk.containsKey(w.hwnd)) out.add(w);
            }
        }
        return out;
    }

    public List<App> apps() {
        return apps;
    }

    public boolean appsLoading() {
        return appsLoading;
    }

    public Bitmap icon(String key, String hwnd) {
        String k = key != null && !key.isEmpty() ? key : hwnd;
        if (k == null) return null;
        Bitmap b = icons.get(k);
        if (b == null && hwnd != null && iconsAsked.add(k)) {
            fetches.execute(() -> {
                try {
                    byte[] png = http.getWindowIcon(hwnd);
                    Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bmp != null) {
                        icons.put(k, bmp);
                        changed();
                    }
                } catch (Exception ignored) {
                }
            });
        }
        return b;
    }

    // --- Actions ---

    /** Workspace n to the front: its window alone is on the desk, the other
     *  workspaces' windows are minimized (they can't be reached or covered) */
    public void switchTo(int n) {
        Window w;
        final String hwnd;
        final List<String> others = new ArrayList<>();
        synchronized (this) {
            if (n < 1 || n > COUNT || slots[n] == null) {
                return;
            }
            current = n;
            recent.remove((Integer) n);
            recent.addFirst(n);
            hwnd = slots[n];
            // Every other workspace's window: what is known minimized may not be by now
            for (int i = 1; i <= COUNT; i++) {
                if (i != n && slots[i] != null) others.add(slots[i]);
            }
            w = onDesk.get(hwnd);
            switching = System.currentTimeMillis();
        }
        save();
        final int gen = switchGen.incrementAndGet();
        actions.execute(() -> {
            // Switches pressed quickly one after another: only the last one is done
            if (gen != switchGen.get()) return;
            try {
                // The host's guard learns the new front first, or it would undo the stashes
                syncPins();
                // Restored (when it was minimized) and fitted, in front with focus,
                // then the rest go: the desk never shows them in between
                fit(hwnd, true);
                http.orderDesk(Collections.singletonList(hwnd), true);
                for (String other : others) {
                    http.windowAction(other, "stash", 0, 0, 0, 0);
                }
            } catch (Exception e) {
                LimeLog.warning("Workspaces: switch failed: " + e);
            } finally {
                synchronized (Workspaces.this) {
                    switching = System.currentTimeMillis();
                }
            }
        });
        final Window shown = w;
        ui.post(() -> listener.onSwitched(n, shown));
        changed();
    }

    /** The window in front trades places with workspace n's (or moves there); it stays in front */
    public void swapWith(int n) {
        synchronized (this) {
            if (n < 1 || n > COUNT || current == 0 || current == n) return;
            String a = slots[current];
            slots[current] = slots[n];
            slots[n] = a;
            int old = current;
            current = n;
            recent.remove((Integer) n);
            recent.remove((Integer) old);
            recent.addFirst(n);
            if (slots[old] != null) recent.add(1, old);
        }
        save();
        final Window w = windowIn(n);
        ui.post(() -> listener.onSwitched(n, w));
        changed();
    }

    /** A window of the host's own screens onto the desk, into workspace slot (0: the first free one) */
    public void bring(String hwnd, int slot) {
        int target;
        synchronized (this) {
            target = slot > 0 ? slot : firstFree();
            if (target == 0) target = current > 0 ? current : 1;
            // A window already in a workspace just comes to the front
            for (int i = 1; i <= COUNT; i++) {
                if (hwnd.equalsIgnoreCase(slots[i])) {
                    target = -i;
                    break;
                }
            }
        }
        if (target < 0) {
            switchTo(-target);
            return;
        }
        final int into = target;
        actions.execute(() -> {
            try {
                http.windowAction(hwnd, "adopt", 0, 0, deskW, deskH);
                synchronized (Workspaces.this) {
                    String displaced = slots[into];
                    slots[into] = hwnd.toLowerCase();
                    if (displaced != null) {
                        // What was there moves to a free workspace, or back to the host's screens
                        int free = firstFree();
                        if (free > 0) slots[free] = displaced;
                        else releaseLater(displaced);
                    }
                    if (!onDesk.containsKey(hwnd.toLowerCase())) {
                        Window known = find(hostWindows, hwnd);
                        onDesk.put(hwnd.toLowerCase(), known != null ? known : new Window(hwnd.toLowerCase(), "", "", ""));
                    }
                }
                ui.post(() -> switchTo(into));
            } catch (Exception e) {
                message("Couldn't bring the window over: " + e.getMessage());
            }
        });
    }

    /** Launches a host app; its first window goes into workspace slot (0: the first free one) */
    public void launch(App app, int slot) {
        final int into;
        synchronized (this) {
            int t = slot > 0 ? slot : firstFree();
            into = t == 0 ? (current > 0 ? current : 1) : t;
            pendingLaunchSlot = into;
            pendingLaunchUntil = System.currentTimeMillis() + 20000;
        }
        message("Opening " + app.name + "…");
        actions.execute(() -> {
            try {
                Set<String> before = new HashSet<>();
                for (Window w : parseHostWindows(http.getHostWindows())) before.add(w.hwnd);
                http.launchHostApp(app.id);
                long until = System.currentTimeMillis() + 20000;
                while (running && System.currentTimeMillis() < until) {
                    Thread.sleep(400);
                    synchronized (Workspaces.this) {
                        // The desk took it itself (opened from a window on it): done
                        if (pendingLaunchSlot == 0) return;
                    }
                    for (Window w : parseHostWindows(http.getHostWindows())) {
                        if (!before.contains(w.hwnd)) {
                            synchronized (Workspaces.this) {
                                pendingLaunchSlot = 0;
                            }
                            bring(w.hwnd, into);
                            return;
                        }
                    }
                }
                synchronized (Workspaces.this) {
                    pendingLaunchSlot = 0;
                }
                message(app.name + " started, but no window appeared");
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                synchronized (Workspaces.this) {
                    pendingLaunchSlot = 0;
                }
                message("Couldn't open " + app.name + ": " + e.getMessage());
            }
        });
    }

    /** The window of workspace n back to the host's own screens */
    public void release(int n) {
        String hwnd;
        synchronized (this) {
            hwnd = slots[n];
            if (hwnd == null) return;
            slots[n] = null;
            onDesk.remove(hwnd);
            recent.remove((Integer) n);
            if (current == n) current = 0;
        }
        save();
        actions.execute(() -> {
            try {
                http.windowAction(hwnd, "release", 0, 0, 0, 0);
            } catch (Exception e) {
                LimeLog.warning("Workspaces: release failed: " + e);
            }
        });
        showMostRecent();
        refreshHostWindows();
    }

    /** Asks the window of workspace n to close (the app may ask to save first) */
    public void close(int n) {
        String hwnd;
        synchronized (this) {
            hwnd = slots[n];
        }
        if (hwnd == null) return;
        actions.execute(() -> {
            try {
                http.windowAction(hwnd, "close", 0, 0, 0, 0);
            } catch (Exception e) {
                message("Couldn't close the window: " + e.getMessage());
            }
        });
    }

    public void refreshHostWindows() {
        fetches.execute(() -> {
            try {
                hostWindows = parseHostWindows(http.getHostWindows());
                changed();
            } catch (Exception e) {
                LimeLog.warning("Workspaces: window list failed: " + e);
            }
        });
    }

    public void refreshApps() {
        if (appsLoading) return;
        appsLoading = true;
        fetches.execute(() -> {
            try {
                JSONObject o = new JSONObject(http.getLaunchables());
                JSONArray arr = o.optJSONArray("apps");
                List<App> list = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.getJSONObject(i);
                        App app = new App(a.optString("name"), a.optString("id"), a.optString("icon"));
                        if (app.name.isEmpty() || app.id.isEmpty()) continue;
                        list.add(app);
                        if (!app.icon.isEmpty() && !icons.containsKey(app.icon)) keys.add(app.icon);
                    }
                }
                Collections.sort(list, (x, y) -> x.name.compareToIgnoreCase(y.name));
                apps = list;
                appsLoading = false;
                changed();
                // Their icons, a batch at a time
                for (int i = 0; i < keys.size() && running; i += 40) {
                    List<String> batch = keys.subList(i, Math.min(keys.size(), i + 40));
                    JSONObject map = new JSONObject(http.getLaunchableIcons(batch));
                    for (java.util.Iterator<String> it = map.keys(); it.hasNext(); ) {
                        String k = it.next();
                        byte[] png = Base64.decode(map.getString(k), Base64.DEFAULT);
                        Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                        if (bmp != null) icons.put(k, bmp);
                    }
                    changed();
                }
            } catch (Exception e) {
                appsLoading = false;
                LimeLog.warning("Workspaces: launchables failed: " + e);
                changed();
            }
        });
    }

    // --- The desk's layout ---

    private void pollLayout() {
        String etag = "0";
        int failures = 0;
        while (running) {
            try {
                String body = http.getDeskLayout(etag, 20000);
                failures = 0;
                JSONObject o = new JSONObject(body);
                etag = o.optString("etag", "0");
                onLayout(o.optJSONArray("windows"));
            } catch (FileNotFoundException e) {
                // No desk (yet): it is opened before the stream starts
                if (!sleep(1000)) return;
            } catch (Exception e) {
                if (!running) return;
                LimeLog.warning("Workspaces: layout failed: " + e);
                if (!sleep(Math.min(5000, 500L << Math.min(failures++, 4)))) return;
            }
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void onLayout(JSONArray windows) throws JSONException {
        if (windows == null) return;
        Map<String, Window> now = new HashMap<>();
        Set<String> nowMinimized = new HashSet<>();
        Set<String> nowFixed = new HashSet<>();
        Map<String, String> nowStates = new HashMap<>();
        String focusedHwnd = null;
        Map<String, int[]> rects = new HashMap<>();
        List<String> bottomFirst = new ArrayList<>();
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = windows.getJSONObject(i);
            String owner = w.optString("owner", "");
            if (!"window".equals(w.optString("kind")) || (!owner.isEmpty() && !owner.equals("0") && !owner.equals("0x0"))) continue;
            String hwnd = w.optString("hwnd").toLowerCase();
            now.put(hwnd, new Window(hwnd, w.optString("title"), w.optString("process"), w.optString("icon")));
            nowStates.put(hwnd, w.optString("state"));
            if (!w.optBoolean("resizable", true)) nowFixed.add(hwnd);
            if ("minimized".equals(w.optString("state"))) {
                nowMinimized.add(hwnd);
                continue;
            }
            if (w.optBoolean("focused")) focusedHwnd = hwnd;
            JSONArray r = w.optJSONArray("rect");
            // Out of shape: not maximized (moved, sized, restored by the user), or a fixed one moved
            boolean fixed = !w.optBoolean("resizable", true);
            boolean inPlace = fixed
                    ? (r != null && r.length() == 4 && r.getInt(0) == 0 && r.getInt(1) == 0)
                    : "maximized".equals(w.optString("state"));
            if (!inPlace) rects.put(hwnd, new int[0]);
            bottomFirst.add(hwnd);
        }
        boolean switchCurrent = false;
        boolean restoreCurrent = false;
        int newSlot = 0;
        int focusedSlot = 0;
        boolean empty;
        boolean first;
        synchronized (this) {
            first = firstLayout;
            firstLayout = false;
            if (first) restore(now.keySet());
            onDesk.clear();
            onDesk.putAll(now);
            minimized.clear();
            minimized.addAll(nowMinimized);
            fixedSize.clear();
            fixedSize.addAll(nowFixed);
            states.clear();
            states.putAll(nowStates);
            // The workspace in front minimized (its title bar button): it
            // comes right back, a workspace's app is never just gone
            boolean settled = System.currentTimeMillis() - switching > SETTLE_MS;
            if (settled && current > 0 && slots[current] != null && nowMinimized.contains(slots[current])) {
                restoreCurrent = true;
            }
            // Gone (closed, or taken off the desk on the host): its workspace is free
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] != null && !now.containsKey(slots[i])) {
                    slots[i] = null;
                    recent.remove((Integer) i);
                    if (current == i) {
                        current = 0;
                        switchCurrent = true;
                    }
                }
            }
            // New on the desk (an app opened another window, or one was
            // launched): a workspace of its own, top-most first
            Set<String> assigned = new HashSet<>();
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] != null) assigned.add(slots[i]);
            }
            for (int i = bottomFirst.size() - 1; i >= 0; i--) {
                String h = bottomFirst.get(i);
                if (assigned.contains(h)) continue;
                int slot = 0;
                if (pendingLaunchSlot > 0 && System.currentTimeMillis() < pendingLaunchUntil) {
                    slot = pendingLaunchSlot;
                    pendingLaunchSlot = 0;
                    String displaced = slots[slot];
                    if (displaced != null) {
                        int free = firstFree();
                        if (free > 0 && free != slot) slots[free] = displaced;
                    }
                } else {
                    slot = firstFree();
                }
                if (slot == 0) {
                    releaseLater(h);
                    continue;
                }
                slots[slot] = h;
                assigned.add(h);
                if (newSlot == 0) newSlot = slot;
            }
            empty = true;
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] != null) empty = false;
            }
            // A workspace's window came back on the host (Alt+Tab, a click on
            // its notification): that workspace is the one in front now
            if (settled && !first && newSlot == 0 && !switchCurrent && focusedHwnd != null) {
                for (int i = 1; i <= COUNT; i++) {
                    if (focusedHwnd.equals(slots[i]) && i != current) {
                        focusedSlot = i;
                        break;
                    }
                }
            }
        }
        save();
        // Windows out of shape (moved, sized, a new one) are put back, once a
        // switch has settled (a window being restored is out of shape for a moment)
        boolean quiet;
        synchronized (this) {
            quiet = System.currentTimeMillis() - switching > SETTLE_MS;
        }
        if (quiet || newSlot > 0) {
            for (String h : rects.keySet()) {
                actions.execute(() -> fit(h, false));
            }
        }
        if (restoreCurrent && newSlot == 0) {
            final int n = current();
            ui.post(() -> switchTo(n));
        } else if (newSlot > 0 && !first) {
            final int n = newSlot;
            ui.post(() -> switchTo(n));
        } else if (focusedSlot > 0) {
            final int n = focusedSlot;
            ui.post(() -> switchTo(n));
        } else if (first && !empty) {
            ui.post(this::showMostRecent);
        } else if (switchCurrent) {
            ui.post(this::showMostRecent);
        }
        if (empty) ui.post(listener::onEmpty);
        changed();
    }

    /** The desk's size, once it is known */
    public void setDeskSize(int w, int h) {
        deskW = w;
        deskH = h;
    }

    /** On the desk and filling it: maximized (no borders to drag, a title bar
     *  that doesn't move it), or at the desk's size when it can't be */
    private void fit(String hwnd, boolean force) {
        if (deskW <= 0 || deskH <= 0) return;
        long now = System.currentTimeMillis();
        boolean resizable;
        String state;
        synchronized (this) {
            // Not again and again while the user drags it: once it settles
            Long last = fitAsked.get(hwnd);
            if (!force && last != null && now - last < 700) return;
            fitAsked.put(hwnd, now);
            resizable = !fixedSize.contains(hwnd);
            state = states.get(hwnd);
        }
        try {
            if ("minimized".equals(state)) {
                // Back as it was (maximized); the next layout puts it right if not
                http.windowAction(hwnd, "restore", 0, 0, 0, 0);
                return;
            }
            if (resizable && "maximized".equals(state)) return;
            // (moving a maximized window would leave it maximized at a size that fills nothing)
            if ("maximized".equals(state)) http.windowAction(hwnd, "restore", 0, 0, 0, 0);
            http.windowAction(hwnd, "adopt", 0, 0, deskW, deskH);
            if (resizable) http.windowAction(hwnd, "maximize", 0, 0, 0, 0);
        } catch (Exception e) {
            LimeLog.warning("Workspaces: fitting " + hwnd + " failed: " + e);
        }
    }

    private void releaseLater(String hwnd) {
        actions.execute(() -> {
            try {
                http.windowAction(hwnd, "release", 0, 0, 0, 0);
            } catch (Exception ignored) {
            }
        });
        message("All 9 workspaces are taken: the new window went back to the PC's screen");
    }

    private void showMostRecent() {
        int n = 0;
        synchronized (this) {
            for (int s : recent) {
                if (slots[s] != null) {
                    n = s;
                    break;
                }
            }
            if (n == 0) {
                for (int i = 1; i <= COUNT; i++) {
                    if (slots[i] != null) {
                        n = i;
                        break;
                    }
                }
            }
        }
        if (n > 0) switchTo(n);
        else {
            ui.post(() -> listener.onSwitched(0, null));
            changed();
        }
    }

    /** Top-most first: the current workspace, then the others as last used */
    private List<String> stackOrder() {
        List<String> out = new ArrayList<>();
        if (current > 0 && slots[current] != null) out.add(slots[current]);
        for (int s : recent) {
            if (s != current && slots[s] != null) out.add(slots[s]);
        }
        for (int i = 1; i <= COUNT; i++) {
            if (slots[i] != null && !out.contains(slots[i])) out.add(slots[i]);
        }
        return out;
    }

    // --- Kept across reconnects (the desk keeps its windows a while) ---

    private void save() {
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] != null) sb.append(slots[i]).append('=').append(i).append(',');
            }
            sb.append("current=").append(current);
        }
        prefs.edit().putString(prefsKey, sb.toString()).apply();
        if (!actions.isShutdown()) actions.execute(this::syncPins);
    }

    private String pinnedKey = "";

    /** Tells the host which window is in front and which are the other workspaces'
     *  (on the actions thread): the host keeps them from being moved, resized or minimized */
    private void syncPins() {
        String front;
        List<String> back = new ArrayList<>();
        synchronized (this) {
            front = current >= 1 && current <= COUNT ? slots[current] : null;
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] != null && !slots[i].equals(front)) back.add(slots[i]);
            }
        }
        String key = front + "|" + back;
        if (key.equals(pinnedKey)) return;
        try {
            http.pinDesk(front, back);
            pinnedKey = key;
        } catch (Exception e) {
            LimeLog.warning("Workspaces: pin failed: " + e);
        }
    }

    private void restore(Set<String> present) {
        String saved = prefs.getString(prefsKey, "");
        int cur = 0;
        for (String part : saved.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq);
            int v;
            try {
                v = Integer.parseInt(part.substring(eq + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (k.equals("current")) cur = v;
            else if (v >= 1 && v <= COUNT && present.contains(k) && slots[v] == null) slots[v] = k;
        }
        if (cur >= 1 && cur <= COUNT && slots[cur] != null) recent.addFirst(cur);
    }

    // --- Helpers ---

    private static Window find(List<Window> list, String hwnd) {
        for (Window w : list) {
            if (w.hwnd.equalsIgnoreCase(hwnd)) return w;
        }
        return null;
    }

    private static List<Window> parseHostWindows(String body) throws JSONException {
        List<Window> out = new ArrayList<>();
        JSONArray arr = new JSONObject(body).optJSONArray("windows");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.getJSONObject(i);
            if ("minimized".equals(w.optString("state")) && w.optString("title").isEmpty()) continue;
            out.add(new Window(w.optString("hwnd").toLowerCase(), w.optString("title"), w.optString("process"), w.optString("icon")));
        }
        return out;
    }

    private void message(String text) {
        ui.post(() -> listener.onMessage(text));
    }

    private void changed() {
        ui.post(listener::onWorkspacesChanged);
    }
}
