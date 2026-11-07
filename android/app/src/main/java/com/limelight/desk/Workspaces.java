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
 * private host display the stream shows), into workspaces 1-9. A workspace
 * holds one window, maximized, or a few side by side (tiles, left to right;
 * the host lays them out in equal columns). Switching workspaces minimizes
 * the other workspaces' windows. Windows an app opens on the desk get a
 * workspace of their own, windows that close leave theirs.
 */
public class Workspaces {
    public static final int COUNT = 9;
    /** Windows side by side in one workspace, at most */
    public static final int MAX_TILES = 3;

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
        /** Workspace n is in front now (0: none); window is its focused one */
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
    /** Each workspace's windows, left to right (null: empty, never an empty list) */
    @SuppressWarnings("unchecked")
    private final List<String>[] slots = new List[COUNT + 1];
    /** Each workspace's focused window, an index into its list */
    private final int[] focus = new int[COUNT + 1];
    private int current = 0;
    /** Workspaces in the order they were last in front, most recent first */
    private final LinkedList<Integer> recent = new LinkedList<>();
    /** Windows on the desk as of the last layout (app windows, not dialogs) */
    private final Map<String, Window> onDesk = new HashMap<>();
    /** Sizes asked for, so a window that won't take the desk's size isn't asked again and again */
    private final Map<String, Long> fitAsked = new HashMap<>();
    /** Windows the user can't size (fixed dialogs): placed, not maximized */
    private final Set<String> fixedSize = new HashSet<>();
    /** Each desk window's state as of the last layout: normal, maximized, minimized */
    private final Map<String, String> states = new HashMap<>();
    /** Windows being brought over, and when: layouts from just before (not on
     *  the desk yet) or just after (not placed yet) leave them to bring() */
    private final Map<String, Long> arriving = new HashMap<>();
    private static final long ARRIVING_MS = 3000;
    /** Workspace windows missing from the desk's layout, since when: a UWP
     *  window (Calculator) drops out of it for a moment while it is restored */
    private final Map<String, Long> missing = new HashMap<>();
    private static final long MISSING_MS = 1200;
    /** How long a layout request may wait for a change: short while a missing
     *  window is waited for, so it is let go of in time when it is really gone */
    private volatile int pollWaitMs = 20000;
    private int pendingLaunchSlot = 0;
    /** The launch goes beside the windows in pendingLaunchSlot, not instead of them */
    private boolean pendingLaunchBeside = false;
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

    // --- Workspace contents (call with the lock held) ---

    /** Workspace n's focused window, null when it is empty */
    private String focused(int n) {
        List<String> tiles = slots[n];
        if (tiles == null) return null;
        focus[n] = Math.max(0, Math.min(focus[n], tiles.size() - 1));
        return tiles.get(focus[n]);
    }

    /** The workspace holding this window, 0 when none does */
    private int slotOf(String hwnd) {
        for (int i = 1; i <= COUNT; i++) {
            if (slots[i] != null && slots[i].contains(hwnd)) return i;
        }
        return 0;
    }

    /** Out of its workspace; the workspace is empty when it was the only one */
    private int remove(String hwnd) {
        int n = slotOf(hwnd);
        if (n == 0) return 0;
        int i = slots[n].indexOf(hwnd);
        slots[n].remove(i);
        if (i < focus[n] || (i == focus[n] && focus[n] == slots[n].size())) focus[n] = Math.max(0, focus[n] - 1);
        if (slots[n].isEmpty()) {
            slots[n] = null;
            focus[n] = 0;
            recent.remove((Integer) n);
        }
        return n;
    }

    /** Into workspace n, right of its others, focused */
    private void add(int n, String hwnd) {
        if (slots[n] == null) slots[n] = new ArrayList<>();
        slots[n].add(hwnd);
        focus[n] = slots[n].size() - 1;
    }

    private boolean solo(String hwnd) {
        int n = slotOf(hwnd);
        return n == 0 || slots[n].size() == 1;
    }

    /** The focused one first, then the rest of the workspace */
    private static List<String> focusedFirst(List<String> tiles, String front) {
        List<String> out = new ArrayList<>();
        out.add(front);
        for (String t : tiles) {
            if (!t.equals(front)) out.add(t);
        }
        return out;
    }

    // --- What the switcher shows ---

    public synchronized int current() {
        return current;
    }

    /** Workspace n's focused window */
    public synchronized Window windowIn(int slot) {
        String h = focused(slot);
        return h == null ? null : onDesk.get(h);
    }

    /** Workspace n's windows, left to right */
    public synchronized List<Window> windowsIn(int slot) {
        List<Window> out = new ArrayList<>();
        if (slots[slot] == null) return out;
        for (String h : slots[slot]) {
            Window w = onDesk.get(h);
            out.add(w != null ? w : new Window(h, "", "", ""));
        }
        return out;
    }

    public synchronized boolean isEmpty(int slot) {
        return slots[slot] == null;
    }

    /** Room for another window beside the ones in front */
    public synchronized boolean canTileBeside() {
        return current > 0 && slots[current] != null && slots[current].size() < MAX_TILES;
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

    /** Workspace n to the front: its windows alone are on the desk, the other
     *  workspaces' windows are minimized (they can't be reached or covered) */
    public void switchTo(int n) {
        Window w;
        final String front;
        final List<String> tiles;
        final List<String> others = new ArrayList<>();
        synchronized (this) {
            if (n < 1 || n > COUNT || slots[n] == null) {
                return;
            }
            current = n;
            recent.remove((Integer) n);
            recent.addFirst(n);
            front = focused(n);
            tiles = new ArrayList<>(slots[n]);
            // Every other workspace's window: what is known minimized may not be by now
            for (int i = 1; i <= COUNT; i++) {
                if (i != n && slots[i] != null) others.addAll(slots[i]);
            }
            w = onDesk.get(front);
            switching = System.currentTimeMillis();
        }
        save();
        final int gen = switchGen.incrementAndGet();
        actions.execute(() -> {
            // Switches pressed quickly one after another: only the last one is done
            if (gen != switchGen.get()) return;
            try {
                // The host's guard learns the new front first (it lays the
                // windows out and restores them), or it would undo the stashes
                syncPins();
                if (tiles.size() == 1) fit(front, true);
                http.orderDesk(focusedFirst(tiles, front), true);
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

    /** The focus to the window left (-1) or right (+1) of the focused one, in front */
    public void focusTile(int by) {
        final List<String> tiles;
        final String front;
        final int n;
        synchronized (this) {
            n = current;
            if (n == 0 || slots[n] == null || slots[n].size() < 2) return;
            int to = Math.max(0, Math.min(slots[n].size() - 1, focus[n] + by));
            if (to == focus[n]) return;
            focus[n] = to;
            front = focused(n);
            tiles = new ArrayList<>(slots[n]);
            switching = System.currentTimeMillis();
        }
        actions.execute(() -> {
            try {
                http.orderDesk(focusedFirst(tiles, front), true);
            } catch (Exception e) {
                LimeLog.warning("Workspaces: focus failed: " + e);
            }
        });
        final Window w = windowIn(n);
        ui.post(() -> listener.onSwitched(n, w));
        changed();
    }

    /** The focused window trades places with its neighbor left (-1) or right (+1) */
    public void moveTile(int by) {
        synchronized (this) {
            int n = current;
            if (n == 0 || slots[n] == null || slots[n].size() < 2) return;
            int from = focus[n], to = from + by;
            if (to < 0 || to >= slots[n].size()) return;
            Collections.swap(slots[n], from, to);
            focus[n] = to;
        }
        // The host lays them out again from the new order
        save();
        changed();
    }

    /** The focused window of the workspace in front goes beside the windows of
     *  workspace n (or alone there when it is empty), and n comes to the front */
    public void moveFocusedTo(int n) {
        synchronized (this) {
            if (n < 1 || n > COUNT || current == 0 || n == current) return;
            String h = focused(current);
            if (h == null) return;
            if (slots[n] != null && slots[n].size() >= MAX_TILES) {
                message("Workspace " + n + " has " + MAX_TILES + " windows side by side already");
                return;
            }
            remove(h);
            add(n, h);
        }
        switchTo(n);
    }

    /** The windows in front trade places with workspace n's (or move there); they stay in front */
    public void swapWith(int n) {
        synchronized (this) {
            if (n < 1 || n > COUNT || current == 0 || current == n) return;
            List<String> a = slots[current];
            slots[current] = slots[n];
            slots[n] = a;
            int f = focus[current];
            focus[current] = focus[n];
            focus[n] = f;
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

    /** A window of the host's own screens onto the desk, into workspace slot
     *  (0: the first free one); beside: next to the windows there (0: the
     *  workspace in front) instead of in place of them */
    public void bring(String hwnd, int slot, boolean beside) {
        final String h = hwnd.toLowerCase();
        int target;
        boolean already;
        synchronized (this) {
            if (beside) {
                target = slot > 0 ? slot : current;
                if (target == 0 || slots[target] == null) beside = false;
            } else {
                target = slot > 0 ? slot : firstFree();
                if (target == 0) target = current > 0 ? current : 1;
            }
            int in = slotOf(h);
            already = in > 0;
            if (already) {
                if (!beside || in == target) {
                    // A window already in a workspace just comes to the front (focused)
                    focus[in] = slots[in].indexOf(h);
                    target = -in;
                } else if (slots[target].size() >= MAX_TILES) {
                    target = 0;
                } else {
                    remove(h);
                    add(target, h);
                    target = -target;
                }
            } else if (beside && slots[target].size() >= MAX_TILES) {
                target = 0;
            }
        }
        if (target == 0) {
            message("There are " + MAX_TILES + " windows side by side already");
            return;
        }
        if (target < 0) {
            switchTo(-target);
            return;
        }
        final int into = target;
        final boolean tile = beside;
        synchronized (this) {
            arriving.put(h, System.currentTimeMillis());
        }
        actions.execute(() -> {
            try {
                http.windowAction(h, "adopt", 0, 0, deskW, deskH);
                synchronized (Workspaces.this) {
                    if (slotOf(h) == 0) {
                        if (tile && slots[into] != null && slots[into].size() < MAX_TILES) {
                            add(into, h);
                        } else {
                            List<String> displaced = slots[into];
                            int displacedFocus = focus[into];
                            slots[into] = null;
                            add(into, h);
                            if (displaced != null) {
                                // What was there moves to a free workspace, or back to the host's screens
                                int free = firstFree();
                                if (free > 0) {
                                    slots[free] = displaced;
                                    focus[free] = displacedFocus;
                                } else {
                                    for (String d : displaced) releaseLater(d);
                                }
                            }
                        }
                    }
                    if (!onDesk.containsKey(h)) {
                        Window known = find(hostWindows, h);
                        onDesk.put(h, known != null ? known : new Window(h, "", "", ""));
                    }
                }
                synchronized (Workspaces.this) {
                    // A little longer: layouts asked for before it was placed are still coming
                    arriving.put(h, System.currentTimeMillis());
                }
                ui.post(() -> switchTo(into));
            } catch (Exception e) {
                synchronized (Workspaces.this) {
                    arriving.remove(h);
                }
                message("Couldn't bring the window over: " + e.getMessage());
            }
        });
    }

    /** Launches a host app; its first window goes into workspace slot (0: the
     *  first free one), or beside the windows there (0: the workspace in front) */
    public void launch(App app, int slot, boolean beside) {
        final int into;
        final boolean tile;
        synchronized (this) {
            if (beside && (slot > 0 ? slots[slot] : (current > 0 ? slots[current] : null)) != null) {
                into = slot > 0 ? slot : current;
                if (slots[into].size() >= MAX_TILES) {
                    message("There are " + MAX_TILES + " windows side by side already");
                    return;
                }
                tile = true;
            } else {
                int t = slot > 0 ? slot : firstFree();
                into = t == 0 ? (current > 0 ? current : 1) : t;
                tile = false;
            }
            pendingLaunchSlot = into;
            pendingLaunchBeside = tile;
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
                            bring(w.hwnd, into, tile);
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

    /** The focused window of workspace n back to the host's own screens */
    public void release(int n) {
        String hwnd;
        boolean emptied;
        synchronized (this) {
            hwnd = focused(n);
            if (hwnd == null) return;
            remove(hwnd);
            onDesk.remove(hwnd);
            emptied = slots[n] == null;
            if (emptied && current == n) current = 0;
        }
        save();
        actions.execute(() -> {
            try {
                http.windowAction(hwnd, "release", 0, 0, 0, 0);
            } catch (Exception e) {
                LimeLog.warning("Workspaces: release failed: " + e);
            }
        });
        if (emptied) showMostRecent();
        else if (n == current()) switchTo(n);
        refreshHostWindows();
    }

    /** Asks the focused window of workspace n to close (the app may ask to save first) */
    public void close(int n) {
        String hwnd;
        synchronized (this) {
            hwnd = focused(n);
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
                String body = http.getDeskLayout(etag, pollWaitMs);
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
        Set<String> outOfShape = new HashSet<>();
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
            // Out of shape (alone in its workspace): not maximized (moved, sized,
            // restored by the user), or a fixed one moved. Side by side ones the host keeps.
            boolean fixed = !w.optBoolean("resizable", true);
            boolean inPlace = fixed
                    ? (r != null && r.length() == 4 && r.getInt(0) == 0 && r.getInt(1) == 0)
                    : "maximized".equals(w.optString("state"));
            if (!inPlace) outOfShape.add(hwnd);
            bottomFirst.add(hwnd);
        }
        boolean switchCurrent = false;
        boolean restoreCurrent = false;
        boolean relayout = false;
        int newSlot = 0;
        int focusedSlot = 0;
        boolean focusMoved = false;
        boolean empty;
        boolean first;
        List<String> toFit = new ArrayList<>();
        synchronized (this) {
            first = firstLayout;
            firstLayout = false;
            if (first) restore(now.keySet());
            onDesk.clear();
            onDesk.putAll(now);
            fixedSize.clear();
            fixedSize.addAll(nowFixed);
            states.clear();
            states.putAll(nowStates);
            boolean settled = System.currentTimeMillis() - switching > SETTLE_MS;
            long nowMs = System.currentTimeMillis();
            arriving.values().removeIf(t -> nowMs - t > ARRIVING_MS);
            // A window of the workspace in front minimized (its title bar
            // button): it comes right back, a workspace's app is never just gone
            if (settled && current > 0 && slots[current] != null) {
                for (String h : slots[current]) {
                    if (nowMinimized.contains(h)) restoreCurrent = true;
                }
            }
            // Gone (closed, or taken off the desk on the host): out of its
            // workspace, which is free when it was the only one there
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] == null) continue;
                for (String h : new ArrayList<>(slots[i])) {
                    if (now.containsKey(h) || arriving.containsKey(h)) {
                        missing.remove(h);
                        continue;
                    }
                    Long since = missing.get(h);
                    if (since == null) {
                        missing.put(h, nowMs);
                        continue;
                    }
                    if (nowMs - since < MISSING_MS) continue;
                    missing.remove(h);
                    remove(h);
                    if (i == current) {
                        if (slots[i] == null) {
                            current = 0;
                            switchCurrent = true;
                        } else {
                            relayout = true;
                        }
                    }
                }
            }
            // New on the desk (an app opened another window, a tab was torn
            // off, or one was launched): beside the windows in front while
            // there's room, else a workspace of its own; top-most first
            for (int i = bottomFirst.size() - 1; i >= 0; i--) {
                String h = bottomFirst.get(i);
                if (slotOf(h) > 0 || arriving.containsKey(h)) continue;
                int slot = 0;
                if (pendingLaunchSlot > 0 && System.currentTimeMillis() < pendingLaunchUntil) {
                    slot = pendingLaunchSlot;
                    pendingLaunchSlot = 0;
                    if (pendingLaunchBeside && slots[slot] != null && slots[slot].size() < MAX_TILES) {
                        add(slot, h);
                        if (newSlot == 0) newSlot = slot;
                        continue;
                    }
                    List<String> displaced = slots[slot];
                    if (displaced != null) {
                        int displacedFocus = focus[slot];
                        slots[slot] = null;
                        int free = firstFree();
                        if (free > 0 && free != slot) {
                            slots[free] = displaced;
                            focus[free] = displacedFocus;
                        }
                    }
                } else if (!first && current > 0 && slots[current] != null && slots[current].size() < MAX_TILES) {
                    slot = current;
                } else {
                    slot = firstFree();
                }
                if (slot == 0) {
                    releaseLater(h);
                    continue;
                }
                add(slot, h);
                if (newSlot == 0) newSlot = slot;
            }
            missing.keySet().removeIf(h -> slotOf(h) == 0);
            pollWaitMs = missing.isEmpty() ? 20000 : 500;
            empty = true;
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] != null) empty = false;
            }
            // A workspace's window came back on the host (Alt+Tab, a click on
            // its notification): that workspace is the one in front now. A
            // click on another window in front just focuses it.
            if (settled && !first && newSlot == 0 && !switchCurrent && focusedHwnd != null) {
                int in = slotOf(focusedHwnd);
                if (in > 0) {
                    int index = slots[in].indexOf(focusedHwnd);
                    if (in != current) {
                        focus[in] = index;
                        focusedSlot = in;
                    } else if (index != focus[in]) {
                        focus[in] = index;
                        focusMoved = true;
                    }
                }
            }
            for (String h : outOfShape) {
                if (solo(h)) toFit.add(h);
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
            for (String h : toFit) {
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
        } else if (relayout) {
            // One of the windows side by side closed: the rest take its room
            final int n = current();
            ui.post(() -> switchTo(n));
        } else if (focusMoved) {
            final int n = current();
            final Window w = windowIn(n);
            ui.post(() -> listener.onSwitched(n, w));
        }
        if (empty) ui.post(listener::onEmpty);
        changed();
    }

    /** The desk's size, once it is known */
    public void setDeskSize(int w, int h) {
        deskW = w;
        deskH = h;
    }

    /** The desk turned in place: the shown workspace is laid out again for its
     *  new shape (pinned again, so the host takes the new work area) */
    public void deskResized(int w, int h) {
        setDeskSize(w, h);
        actions.execute(() -> pinnedKey = "");
        int n = current();
        if (n > 0) switchTo(n);
    }

    /** A window alone in its workspace, on the desk and filling it: maximized
     *  (no borders to drag, a title bar that doesn't move it), or at the
     *  desk's size when it can't be. Side by side ones the host lays out. */
    private void fit(String hwnd, boolean force) {
        if (deskW <= 0 || deskH <= 0) return;
        long now = System.currentTimeMillis();
        boolean resizable;
        String state;
        synchronized (this) {
            if (!solo(hwnd)) return;
            // Not again and again while the user drags it: once it settles
            Long last = fitAsked.get(hwnd);
            if (!force && last != null && now - last < 700) return;
            fitAsked.put(hwnd, now);
            resizable = !fixedSize.contains(hwnd);
            state = states.get(hwnd);
        }
        try {
            // The host restores the windows in front itself (and maximizes this one):
            // a restore from here could come after that and undo it
            if ("minimized".equals(state)) return;
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

    // --- Kept across reconnects (the desk keeps its windows a while) ---

    private void save() {
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            // Left to right within a workspace
            for (int i = 1; i <= COUNT; i++) {
                if (slots[i] == null) continue;
                for (String h : slots[i]) sb.append(h).append('=').append(i).append(',');
            }
            sb.append("current=").append(current);
        }
        prefs.edit().putString(prefsKey, sb.toString()).apply();
        if (!actions.isShutdown()) actions.execute(this::syncPins);
    }

    private String pinnedKey = "";

    /** Tells the host which windows are in front (left to right) and which are
     *  the other workspaces' (on the actions thread): the host lays out the
     *  ones in front and keeps them all from being moved, resized or minimized */
    private void syncPins() {
        List<String> front = new ArrayList<>();
        List<String> back = new ArrayList<>();
        synchronized (this) {
            if (current >= 1 && current <= COUNT && slots[current] != null) front.addAll(slots[current]);
            for (int i = 1; i <= COUNT; i++) {
                if (i != current && slots[i] != null) back.addAll(slots[i]);
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
            else if (v >= 1 && v <= COUNT && present.contains(k) && slotOf(k) == 0
                    && (slots[v] == null || slots[v].size() < MAX_TILES)) {
                add(v, k);
                focus[v] = 0;
            }
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
