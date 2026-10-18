package ru.wohlsoft.moondust;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * In-app updater: checks the rolling "nightly" GitHub prerelease of this app for a newer build, downloads the
 * APK, verifies its SHA-256 and installs it over the running app through PackageInstaller (a normal package
 * update, no uninstall). See UPDATER.md.
 *
 * All UI goes through N64Ui and works with D-pad / A / B. Network and file work run on a worker thread; every
 * callback back to the UI is dropped if the operation was cancelled or the host activity is gone.
 */
final class Updater
{
    // ----------------------------------------------------------------- Easy to change ----
    static final String OWNER = "tdprinting";
    static final String REPO = "SMBX-2-Android";
    static final String TAG = "nightly";
    static final String VERSION_FILE = "version.json";
    static final String APK_FILE = "moondust-release.apk";

    static final long MAX_APK_BYTES = 300L * 1024L * 1024L; // 300 MB
    static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 h
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final String PREFS = "moondust_prefs";
    private static final String KEY_LAST_CHECK = "updater_last_check";
    static final String ACTION_RESULT = "ru.wohlsoft.moondust.UPDATE_RESULT";

    /** Directory URL of the release assets, with trailing slash */
    static String baseUrl()
    {
        return "https://github.com/" + OWNER + "/" + REPO + "/releases/download/" + TAG + "/";
    }

    /** Parsed version.json */
    private static final class Info
    {
        long versionCode;
        String versionName = "", sha256 = "", apk = APK_FILE, commit = "", notes = "";
    }

    private static boolean sAutoDone = false; // one automatic check per process launch
    private static WeakReference<Updater> sActive = new WeakReference<>(null);

    private final Activity m_act;
    private final Handler m_ui = new Handler(Looper.getMainLooper());
    private volatile int m_gen = 0;      // bumped on every new operation and on cancel; stale workers go quiet
    private boolean m_busy = false;      // UI thread only
    private boolean m_detached = false;
    private boolean m_awaitPerm = false; // user is in Settings granting "install unknown apps"
    private Info m_pending = null;       // offer waiting for that permission
    private AlertDialog m_dlg;           // the one progress dialog (checking / downloading)
    private ProgressBar m_bar;
    private TextView m_barText;

    Updater(Activity a)
    {
        m_act = a;
        sActive = new WeakReference<>(this);
    }

    // ----------------------------------------------------------------- Public entry points ----

    /** Once per launch, at most once per 6 h, silent unless an update exists. Never blocks the game. */
    void checkAutomatic()
    {
        if(sAutoDone)
            return;
        sAutoDone = true;
        SharedPreferences sp = prefs();
        long last = sp.getLong(KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        if(now >= last && now - last < AUTO_CHECK_INTERVAL_MS)
        {
            cleanUpdatesDir(); // still tidy up leftovers of an earlier install
            return;
        }
        runCheck(false);
    }

    /** Always runs; reports "up to date" and errors */
    void checkManual()
    {
        runCheck(true);
    }

    /** Host activity calls this from onResume(): continues after the user returned from the permission page */
    void onResume()
    {
        if(!m_detached)
            sActive = new WeakReference<>(this); // install results go to the activity in front
        if(!m_awaitPerm)
            return;
        m_awaitPerm = false;
        Info info = m_pending;
        m_pending = null;
        if(info == null)
            return;
        if(canInstall())
            beginDownload(info);
        else
        {
            m_busy = false;
            message("Update", R.color.n64_yellow,
                    "Installing is still not allowed for this app, so the update was not started.");
        }
    }

    /** Host activity calls this from onDestroy() */
    void detach()
    {
        m_detached = true;
        m_gen++;
        dismissDlg();
        if(sActive.get() == this)
            sActive = new WeakReference<>(null);
    }

    // ----------------------------------------------------------------- Helpers ----

    private SharedPreferences prefs()
    {
        return m_act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean alive()
    {
        return !m_detached && !m_act.isFinishing() && !m_act.isDestroyed();
    }

    private long installedCode()
    {
        try
        {
            PackageInfo pi = m_act.getPackageManager().getPackageInfo(m_act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : (long)pi.versionCode;
        }
        catch(Exception e)
        {
            return 0L;
        }
    }

    private String installedName()
    {
        try
        {
            PackageInfo pi = m_act.getPackageManager().getPackageInfo(m_act.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "?";
        }
        catch(Exception e)
        {
            return "?";
        }
    }

    private String installedText()
    {
        return installedName() + " (build " + installedCode() + ")";
    }

    private boolean canInstall()
    {
        if(Build.VERSION.SDK_INT >= 26)
            return m_act.getPackageManager().canRequestPackageInstalls();
        return true;
    }

    private File updatesDir()
    {
        File d = new File(m_act.getCacheDir(), "updates");
        if(!d.isDirectory())
            d.mkdirs();
        return d;
    }

    private void cleanUpdatesDir()
    {
        final File d = updatesDir();
        new Thread(new Runnable()
        {
            public void run()
            {
                File[] fs = d.listFiles();
                if(fs != null)
                    for(File f : fs)
                        f.delete();
            }
        }, "Updater-clean").start();
    }

    private static String shortNet(Exception e)
    {
        if(e instanceof UnknownHostException || e instanceof java.net.ConnectException || e instanceof SocketTimeoutException)
            return "Could not reach GitHub. Check the network connection and try again.";
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    // ----------------------------------------------------------------- Dialog helpers ----

    private void dismissDlg()
    {
        if(m_dlg != null && m_dlg.isShowing())
        {
            try { m_dlg.dismiss(); } catch(Exception ignored) {}
        }
        m_dlg = null;
        m_bar = null;
        m_barText = null;
    }

    private void message(String title, int accent, String text)
    {
        if(!alive())
            return;
        dismissDlg();
        m_dlg = N64Ui.show(N64Ui.dialog(m_act, title, accent)
            .setMessage(text)
            .setPositiveButton("OK", null), N64Ui.BLUE, -1, -1);
    }

    /** Progress dialog with a Cancel button. total < 0 => indeterminate. */
    private void progressDialog(String title, String text, boolean indeterminate)
    {
        if(!alive())
            return;
        dismissDlg();
        Context c = m_act;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(N64Ui.dp(c, 24), N64Ui.dp(c, 16), N64Ui.dp(c, 24), N64Ui.dp(c, 8));
        m_barText = new TextView(c);
        m_barText.setText(text);
        m_barText.setTextSize(20);
        m_barText.setTextColor(N64Ui.color(c, R.color.n64_ink));
        m_barText.setPadding(0, 0, 0, N64Ui.dp(c, 10));
        m_bar = N64Ui.makeProgress(c, 20);
        m_bar.setMax(1000);
        m_bar.setIndeterminate(indeterminate);
        box.addView(m_barText);
        box.addView(m_bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, N64Ui.dp(c, 20)));

        m_dlg = N64Ui.show(N64Ui.dialog(c, title, R.color.n64_green)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
            {
                public void onClick(DialogInterface d, int w)
                {
                    m_gen++; // silences the worker
                    m_busy = false;
                    m_dlg = null;
                    m_bar = null;
                    m_barText = null;
                }
            }), -1, -1, N64Ui.RED);
        // Cancel is the only button: it already holds focus after show()
    }

    // ----------------------------------------------------------------- Check ----

    private void runCheck(final boolean manual)
    {
        if(m_busy || !alive())
            return;
        m_busy = true;
        final int gen = ++m_gen;
        if(manual)
            progressDialog("Checking for updates", "Contacting GitHub...", true);

        new Thread(new Runnable()
        {
            public void run()
            {
                Info info = null;
                Exception err = null;
                try
                {
                    info = fetchInfo();
                }
                catch(Exception e)
                {
                    err = e;
                }
                final Info fi = info;
                final Exception fe = err;
                m_ui.post(new Runnable()
                {
                    public void run() { checkDone(gen, manual, fi, fe); }
                });
            }
        }, "Updater-check").start();
    }

    private void checkDone(int gen, boolean manual, Info info, Exception err)
    {
        if(gen != m_gen || !alive())
            return;
        m_busy = false;
        dismissDlg();
        if(err != null)
        {
            if(manual) // automatic checks stay silent (offline, rate limits, no release yet)
                message("Update check failed", R.color.n64_red, shortNet(err));
            return;
        }
        // Only a completed check counts for the 6 h throttle (offline launches retry next time)
        prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

        long have = installedCode();
        if(info.versionCode > have)
            offer(info);
        else if(manual)
            message("Up to date", R.color.n64_green, "You are up to date.\n\nInstalled: " + installedText()
                    + "\nLatest published: " + info.versionName + " (build " + info.versionCode + ")");
    }

    private void offer(final Info info)
    {
        String notes = info.notes == null ? "" : info.notes.trim();
        if(notes.length() > 1200)
            notes = notes.substring(0, 1200) + "...";
        StringBuilder sb = new StringBuilder();
        sb.append("New: ").append(info.versionName).append(" (build ").append(info.versionCode).append(")\n");
        sb.append("Installed: ").append(installedText()).append('\n');
        if(info.commit.length() >= 7)
            sb.append("Commit: ").append(info.commit.substring(0, 7)).append('\n');
        if(!notes.isEmpty())
            sb.append('\n').append(notes);
        m_busy = true; // the offer dialog counts as an operation in progress
        m_dlg = N64Ui.show(N64Ui.dialog(m_act, "Update available", R.color.n64_yellow)
            .setMessage(sb.toString())
            .setCancelable(true)
            .setOnCancelListener(new DialogInterface.OnCancelListener()
            {
                public void onCancel(DialogInterface d) { m_busy = false; m_dlg = null; }
            })
            .setPositiveButton("Update", new DialogInterface.OnClickListener()
            {
                public void onClick(DialogInterface d, int w) { m_dlg = null; accept(info); }
            })
            .setNegativeButton("Later", new DialogInterface.OnClickListener()
            {
                public void onClick(DialogInterface d, int w) { m_busy = false; m_dlg = null; }
            }), N64Ui.BLUE, -1, N64Ui.DARK);
        // The dialog can pop up in the middle of a game: start on "Later" so a stray A press is harmless
        Button later = m_dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
        if(later != null)
            later.requestFocus();
    }

    // ----------------------------------------------------------------- Permission ----

    private void accept(final Info info)
    {
        if(!alive())
        {
            m_busy = false;
            return;
        }
        if(canInstall())
        {
            beginDownload(info);
            return;
        }
        dismissDlg();
        m_dlg = N64Ui.show(N64Ui.dialog(m_act, "Allow installing updates", R.color.n64_yellow)
            .setMessage("Android needs your permission before this app can install its own updates. "
                    + "On the next screen, switch on \"Allow from this source\", then press Back to return here. "
                    + "The update continues automatically.")
            .setCancelable(true)
            .setOnCancelListener(new DialogInterface.OnCancelListener()
            {
                public void onCancel(DialogInterface d) { m_busy = false; m_dlg = null; }
            })
            .setPositiveButton("Open Settings", new DialogInterface.OnClickListener()
            {
                public void onClick(DialogInterface d, int w) { m_dlg = null; openPermissionPage(info); }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
            {
                public void onClick(DialogInterface d, int w) { m_busy = false; m_dlg = null; }
            }), N64Ui.BLUE, -1, N64Ui.RED);
    }

    private void openPermissionPage(Info info)
    {
        m_pending = info;
        m_awaitPerm = true;
        try
        {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + m_act.getPackageName()));
            m_act.startActivity(i);
        }
        catch(Exception e)
        {
            m_awaitPerm = false;
            m_pending = null;
            m_busy = false;
            message("Update", R.color.n64_red, "Could not open the Settings page. Open Settings > Apps > "
                    + "Special app access > Install unknown apps, and allow this app.");
        }
    }

    // ----------------------------------------------------------------- Download ----

    private void beginDownload(final Info info)
    {
        if(!alive())
        {
            m_busy = false;
            return;
        }
        m_busy = true;
        final int gen = ++m_gen;
        progressDialog("Downloading " + info.versionName, "Connecting...", true);

        new Thread(new Runnable()
        {
            public void run()
            {
                File part = null;
                try
                {
                    File dir = updatesDir();
                    File[] old = dir.listFiles();
                    if(old != null)
                        for(File f : old)
                            f.delete();
                    part = new File(dir, "update-" + gen + ".apk.part");
                    File out = new File(dir, "update-" + info.versionCode + ".apk");

                    HttpURLConnection c = open(new URL(baseUrl() + info.apk));
                    final long total = c.getContentLengthLong();
                    try
                    {
                        if(total > MAX_APK_BYTES)
                            throw new IOException("The update is larger than the " + (MAX_APK_BYTES >> 20) + " MB limit.");
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        InputStream in = c.getInputStream();
                        OutputStream os = new FileOutputStream(part);
                        long got = 0, lastUi = 0;
                        try
                        {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while((n = in.read(buf)) > 0)
                            {
                                if(gen != m_gen)
                                    throw new IOException("Cancelled");
                                got += n;
                                if(got > MAX_APK_BYTES)
                                    throw new IOException("The update exceeds the " + (MAX_APK_BYTES >> 20) + " MB limit.");
                                md.update(buf, 0, n);
                                os.write(buf, 0, n);
                                long now = System.currentTimeMillis();
                                if(now - lastUi > 150)
                                {
                                    lastUi = now;
                                    postProgress(gen, got, total);
                                }
                            }
                        }
                        finally
                        {
                            try { in.close(); } catch(IOException ignored) {}
                            os.close();
                        }
                        if(got == 0 || (total > 0 && got != total))
                            throw new IOException("The download was incomplete. Try again.");
                        if(gen != m_gen)
                            throw new IOException("Cancelled");

                        String hex = hex(md.digest());
                        if(!hex.equalsIgnoreCase(info.sha256))
                        {
                            part.delete();
                            part = null;
                            throw new IOException("Checksum mismatch: the downloaded file is not the one that was "
                                    + "published. It was deleted and nothing was installed.");
                        }
                    }
                    finally
                    {
                        c.disconnect();
                    }
                    if(!part.renameTo(out))
                        throw new IOException("Cannot store the downloaded file.");
                    part = null;
                    final File fout = out;
                    m_ui.post(new Runnable() { public void run() { downloaded(gen, info, fout); } });
                }
                catch(final Exception e)
                {
                    if(part != null)
                        part.delete();
                    m_ui.post(new Runnable() { public void run() { failed(gen, "Download failed", shortNet(e)); } });
                }
            }
        }, "Updater-download").start();
    }

    private void postProgress(final int gen, final long got, final long total)
    {
        m_ui.post(new Runnable()
        {
            public void run()
            {
                if(gen != m_gen || m_bar == null || m_barText == null)
                    return;
                if(total > 0)
                {
                    m_bar.setIndeterminate(false);
                    m_bar.setProgress((int)(got * 1000L / total));
                    m_barText.setText((got >> 10) + " / " + (total >> 10) + " KB");
                }
                else
                    m_barText.setText((got >> 10) + " KB");
            }
        });
    }

    private void failed(int gen, String title, String text)
    {
        if(gen != m_gen || !alive())
            return;
        m_busy = false;
        message(title, R.color.n64_red, text);
    }

    private void downloaded(int gen, Info info, File apk)
    {
        if(gen != m_gen || !alive())
            return;
        // Downgrade / same-version guard, evaluated against the installed app right before installing
        if(info.versionCode <= installedCode())
        {
            apk.delete();
            m_busy = false;
            message("Update", R.color.n64_green, "You are up to date.");
            return;
        }
        if(!canInstall())
        {
            // Permission revoked meanwhile: go through the explanation again
            apk.delete();
            accept(info);
            return;
        }
        if(m_barText != null)
            m_barText.setText("Handing over to Android...");
        if(m_bar != null)
            m_bar.setIndeterminate(true);
        install(gen, apk);
    }

    // ----------------------------------------------------------------- Install ----

    private void install(final int gen, final File apk)
    {
        final Context app = m_act.getApplicationContext();
        new Thread(new Runnable()
        {
            public void run()
            {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.Session session = null;
                int id = -1;
                try
                {
                    // abandon leftovers of earlier attempts
                    List<PackageInstaller.SessionInfo> mine = pi.getMySessions();
                    for(PackageInstaller.SessionInfo si : mine)
                    {
                        try { pi.abandonSession(si.getSessionId()); } catch(Exception ignored) {}
                    }

                    PackageInstaller.SessionParams p =
                            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setAppPackageName(app.getPackageName()); // can only ever update this app
                    p.setSize(apk.length());
                    if(Build.VERSION.SDK_INT >= 31)
                        p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                    id = pi.createSession(p);
                    session = pi.openSession(id);

                    OutputStream os = session.openWrite("moondust-update.apk", 0, apk.length());
                    InputStream in = new FileInputStream(apk);
                    try
                    {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while((n = in.read(buf)) > 0)
                            os.write(buf, 0, n);
                        session.fsync(os);
                    }
                    finally
                    {
                        try { in.close(); } catch(IOException ignored) {}
                        os.close();
                    }

                    Intent cb = new Intent(app, UpdateInstallReceiver.class);
                    cb.setAction(ACTION_RESULT);
                    cb.setPackage(app.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if(Build.VERSION.SDK_INT >= 31)
                        flags |= PendingIntent.FLAG_MUTABLE; // the installer adds its status extras
                    PendingIntent pend = PendingIntent.getBroadcast(app, id, cb, flags);
                    session.commit(pend.getIntentSender());
                    session.close();
                    session = null;
                    apk.delete(); // the session holds its own copy
                    m_ui.post(new Runnable() { public void run() { installHandedOver(gen); } });
                }
                catch(final Exception e)
                {
                    if(session != null)
                    {
                        try { session.abandon(); } catch(Exception ignored) {}
                    }
                    apk.delete();
                    m_ui.post(new Runnable()
                    {
                        public void run() { failed(gen, "Install failed", shortNet(e)); }
                    });
                }
            }
        }, "Updater-install").start();
    }

    private void installHandedOver(int gen)
    {
        if(gen != m_gen)
            return;
        m_busy = false;
        dismissDlg();
        if(alive())
            Toast.makeText(m_act, "Installing the update. The game closes when it is done; open it again afterwards.",
                    Toast.LENGTH_LONG).show();
    }

    // ----------------------------------------------------------------- Result from the receiver ----

    /** Called by UpdateInstallReceiver (main thread) with the final result of a failed or finished session */
    static void deliverResult(Context ctx, boolean ok, String text)
    {
        Updater u = sActive.get();
        if(u != null && u.alive())
        {
            u.m_busy = false;
            u.message(ok ? "Update installed" : "Update failed", ok ? R.color.n64_green : R.color.n64_red, text);
        }
        else
            Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }

    static String describeFailure(int status, String platformMsg)
    {
        String m = platformMsg == null ? "" : platformMsg;
        String up = m.toUpperCase(Locale.ROOT);
        if(status == PackageInstaller.STATUS_FAILURE_CONFLICT || up.contains("UPDATE_INCOMPATIBLE")
                || up.contains("SIGNATURES DO NOT MATCH"))
            return "The installed copy was signed with a different certificate, so Android cannot update it in place. "
                    + "Uninstall the game once and install the new build (this removes the game's app data).";
        switch(status)
        {
        case PackageInstaller.STATUS_FAILURE_ABORTED:
            return "The update was cancelled.";
        case PackageInstaller.STATUS_FAILURE_BLOCKED:
            return "Android blocked the update (device policy or a security app). " + m;
        case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            return "The new build is not compatible with this device. " + m;
        case PackageInstaller.STATUS_FAILURE_INVALID:
            return "Android rejected the downloaded APK as invalid. " + m;
        case PackageInstaller.STATUS_FAILURE_STORAGE:
            return "Not enough free storage to install the update. " + m;
        default:
            return "The install failed" + (m.isEmpty() ? "." : ": " + m);
        }
    }

    // ----------------------------------------------------------------- Network ----

    /** GET with https-only redirects, 10 s timeouts; returns a connection with HTTP 200 (caller disconnects). */
    private static HttpURLConnection open(URL start) throws IOException
    {
        URL cur = start;
        for(int i = 0; ; i++)
        {
            if(i > MAX_REDIRECTS)
                throw new IOException("Too many redirects");
            if(!"https".equalsIgnoreCase(cur.getProtocol()))
                throw new IOException("Refused a non-https address");
            HttpURLConnection c = (HttpURLConnection)cur.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "Moondust-Updater");
            c.setRequestProperty("Accept-Encoding", "identity"); // keep Content-Length honest for the size check
            int code = c.getResponseCode();
            if(code >= 300 && code < 400 && c.getHeaderField("Location") != null)
            {
                URL next = new URL(cur, c.getHeaderField("Location"));
                c.disconnect();
                if(!"https".equalsIgnoreCase(next.getProtocol()))
                    throw new IOException("Refused a redirect to a non-https address");
                cur = next;
                continue;
            }
            if(code == 404)
            {
                c.disconnect();
                throw new IOException("No update has been published yet (HTTP 404).");
            }
            if(code != 200)
            {
                c.disconnect();
                throw new IOException("The server answered HTTP " + code);
            }
            return c;
        }
    }

    private static Info fetchInfo() throws Exception
    {
        HttpURLConnection c = open(new URL(baseUrl() + VERSION_FILE));
        String text;
        try
        {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while((n = in.read(buf)) > 0)
            {
                bo.write(buf, 0, n);
                if(bo.size() > MAX_JSON_BYTES)
                    throw new IOException("The update information is too large");
            }
            text = bo.toString("UTF-8");
        }
        finally
        {
            c.disconnect();
        }

        JSONObject j;
        try { j = new JSONObject(text); }
        catch(Exception e) { throw new IOException("The update information was not understood"); }

        Info i = new Info();
        i.versionCode = j.optLong("versionCode", -1L);
        i.versionName = j.optString("versionName", "");
        i.sha256 = j.optString("sha256", "").trim();
        i.commit = j.optString("commit", "");
        i.notes = j.optString("notes", "");
        String apk = j.optString("apk", APK_FILE);
        // a plain file name only: it becomes part of the download URL
        i.apk = apk.matches("[A-Za-z0-9._-]{1,100}\\.apk") ? apk : APK_FILE;
        if(i.versionCode < 0 || !i.sha256.matches("[0-9a-fA-F]{64}"))
            throw new IOException("The update information is incomplete");
        if(i.versionName.isEmpty())
            i.versionName = String.valueOf(i.versionCode);
        return i;
    }

    private static String hex(byte[] b)
    {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for(byte x : b)
            sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }
}
