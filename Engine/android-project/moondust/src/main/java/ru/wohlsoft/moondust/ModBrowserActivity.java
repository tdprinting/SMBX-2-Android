package ru.wohlsoft.moondust;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * In-app browser for finding and installing SMBX 2 mods.
 *
 * Controls: touch, or gamepad: D-pad moves focus / scrolls the page, A = click, B = back (closes at the
 * first page), SELECT = jump between the toolbar and the page, Y = focus the address bar.
 *
 * Downloads use HttpURLConnection rather than DownloadManager: DownloadManager writes to shared storage
 * and shows system notifications, cannot enforce our size cap mid-stream, and gives no progress callback
 * for an in-app indicator. A plain connection lets us cap the size, restrict redirects (no https->http),
 * forward the WebView cookies (needed by most file hosts) and keep the file in the private app cache.
 */
public class ModBrowserActivity extends Activity
{
    // ----------------------------------------------------------------- Easy to change ----
    /** Start page. A generic web search; replace with a curated page when one exists. */
    public static final String HOME_URL = "https://duckduckgo.com/?q=SMBX2+episodes+download";
    public static final String SEARCH_URL = "https://duckduckgo.com/?q=";
    public static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L; // 512 MB
    private static final int MAX_REDIRECTS = 8;

    private WebView m_web;
    private EditText m_url;
    private ProgressBar m_pageProgress;
    private Button m_btnBack, m_btnFwd, m_btnReload;
    private final Handler m_ui = new Handler(Looper.getMainLooper());

    // download state
    private volatile boolean m_cancelDl = false;
    private AlertDialog m_dlDialog;
    private ProgressBar m_dlBar;
    private TextView m_dlText;

    private int dp(int v)
    {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button makeButton(String text, int role, View.OnClickListener l)
    {
        return N64Ui.makeButton(this, text, role, l);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable); // never in release

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.n64_charcoal, getTheme()));

        // 4-colour accent strip along the very top, then the gray controller-body toolbar
        root.addView(N64Ui.accentStrip(this, 6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(getResources().getDrawable(R.drawable.n64_toolbar_bg, getTheme()));
        bar.setPadding(dp(8), dp(6), dp(8), dp(10));

        m_btnBack = makeButton("<", N64Ui.GREEN, new View.OnClickListener() { public void onClick(View v) { goBackOrFinish(false); } });
        m_btnFwd = makeButton(">", N64Ui.DARK, new View.OnClickListener() { public void onClick(View v) { if(m_web.canGoForward()) m_web.goForward(); } });
        m_btnReload = makeButton("Reload", N64Ui.DARK, new View.OnClickListener()
        {
            public void onClick(View v) { if(m_loading) m_web.stopLoading(); else m_web.reload(); }
        });
        Button home = makeButton("Home", N64Ui.DARK, new View.OnClickListener() { public void onClick(View v) { m_web.loadUrl(HOME_URL); } });
        Button go = makeButton("Go", N64Ui.BLUE, new View.OnClickListener() { public void onClick(View v) { submitUrl(); } });
        Button close = makeButton("Close", N64Ui.RED, new View.OnClickListener() { public void onClick(View v) { finish(); } });

        m_url = new EditText(this);
        m_url.setSingleLine(true);
        m_url.setSelectAllOnFocus(true);
        N64Ui.styleField(m_url);
        m_url.setHint("Search or enter address");
        m_url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        m_url.setImeOptions(EditorInfo.IME_ACTION_GO);
        m_url.setOnEditorActionListener(new TextView.OnEditorActionListener()
        {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e)
            {
                if(actionId == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER))
                {
                    submitUrl();
                    return true;
                }
                return false;
            }
        });

        TextView title = N64Ui.makeTitle(this, "MODS");
        title.setTextColor(getResources().getColor(R.color.n64_ink, getTheme()));
        title.setShadowLayer(0f, 0f, 0f, 0);
        title.setTextSize(24);
        title.setPadding(dp(8), 0, dp(14), 0);
        bar.addView(title);
        bar.addView(m_btnBack);
        bar.addView(m_btnFwd);
        bar.addView(m_btnReload);
        bar.addView(home);
        for(Button bt : new Button[] { m_btnBack, m_btnFwd, m_btnReload, home, go, close })
        {
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gap.setMargins(dp(3), 0, dp(3), 0);
            bt.setLayoutParams(gap);
        }
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        urlLp.setMargins(dp(5), 0, dp(5), dp(5)); // bottom margin lines the field up with the buttons' face
        bar.addView(m_url, urlLp);
        bar.addView(go);
        bar.addView(close);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        m_pageProgress = N64Ui.makeProgress(this, 8);
        m_pageProgress.setMax(100);
        m_pageProgress.setVisibility(View.GONE);
        root.addView(m_pageProgress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));

        m_web = new WebView(this);
        m_web.setFocusable(true);
        m_web.setFocusableInTouchMode(true);
        setupWebView();
        root.addView(m_web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        cleanOldDownloads();

        if(savedInstanceState != null)
            m_web.restoreState(savedInstanceState);
        else
            m_web.loadUrl(HOME_URL);
        m_web.requestFocus();
    }

    private boolean m_loading = false;

    private void setupWebView()
    {
        WebSettings s = m_web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        if(Build.VERSION.SDK_INT >= 26)
            s.setSafeBrowsingEnabled(true);

        CookieManager.getInstance().setAcceptThirdPartyCookies(m_web, false);

        m_web.setWebViewClient(new WebViewClient()
        {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r)
            {
                String scheme = r.getUrl().getScheme();
                if("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    return false;
                // file:, content:, intent:, javascript: from links, custom app schemes, ...: refuse
                Toast.makeText(ModBrowserActivity.this, "Blocked link type: " + scheme, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap fav)
            {
                m_loading = true;
                m_btnReload.setText("Stop");
                if(!m_url.hasFocus())
                    m_url.setText(url);
                updateNavButtons();
            }

            @Override
            public void onPageFinished(WebView v, String url)
            {
                m_loading = false;
                m_btnReload.setText("Reload");
                m_pageProgress.setVisibility(View.GONE);
                if(!m_url.hasFocus())
                    m_url.setText(url);
                updateNavButtons();
            }
            // SSL errors: default behaviour cancels the load. Deliberately not overridden.
        });

        m_web.setWebChromeClient(new WebChromeClient()
        {
            @Override
            public void onProgressChanged(WebView v, int p)
            {
                m_pageProgress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                m_pageProgress.setProgress(p);
            }
        });

        m_web.setDownloadListener(new DownloadListener()
        {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength)
            {
                startDownload(url, userAgent, contentDisposition, mimetype, contentLength);
            }
        });
    }

    private void updateNavButtons()
    {
        m_btnBack.setEnabled(m_web.canGoBack());
        m_btnFwd.setEnabled(m_web.canGoForward());
    }

    private void submitUrl()
    {
        String in = m_url.getText().toString().trim();
        if(in.isEmpty())
            return;
        String target;
        String lower = in.toLowerCase(Locale.ROOT);
        if(lower.startsWith("https://") || lower.startsWith("http://"))
            target = in;
        else if(in.contains(" ") || !in.contains(".") || lower.contains("://") || lower.startsWith("javascript:") || lower.startsWith("file:"))
        {
            // Not a plain host name: treat as search text (blocks file:, javascript:, etc.)
            try { target = SEARCH_URL + URLEncoder.encode(in, "UTF-8"); }
            catch(Exception e) { return; }
        }
        else
            target = "https://" + in; // https preferred
        InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm != null)
            imm.hideSoftInputFromWindow(m_url.getWindowToken(), 0);
        m_web.loadUrl(target);
        m_web.requestFocus();
    }

    private void goBackOrFinish(boolean finishIfNone)
    {
        if(m_web.canGoBack())
            m_web.goBack();
        else if(finishIfNone)
            finish();
    }

    // ----------------------------------------------------------------- Input (gamepad) ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e)
    {
        int k = e.getKeyCode();
        if(k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK)
        {
            if(e.getAction() == KeyEvent.ACTION_UP)
                goBackOrFinish(true);
            return true;
        }
        if(e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)
        {
            if(k == KeyEvent.KEYCODE_BUTTON_SELECT)
            {
                if(m_web.hasFocus())
                    m_btnBack.requestFocus();
                else
                    m_web.requestFocus();
                return true;
            }
            if(k == KeyEvent.KEYCODE_BUTTON_Y)
            {
                m_url.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev)
    {
        // Left stick scrolls the page
        if((ev.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK &&
           ev.getAction() == MotionEvent.ACTION_MOVE)
        {
            float y = ev.getAxisValue(MotionEvent.AXIS_Y);
            float x = ev.getAxisValue(MotionEvent.AXIS_X);
            if(Math.abs(y) > 0.25f || Math.abs(x) > 0.25f)
            {
                m_web.scrollBy((int)(x * 30), (int)(y * 30));
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // ----------------------------------------------------------------- Lifecycle ----

    @Override
    protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        m_web.saveState(out);
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        m_web.onPause();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        m_web.onResume();
    }

    @Override
    protected void onDestroy()
    {
        m_cancelDl = true;
        if(m_dlDialog != null && m_dlDialog.isShowing())
            m_dlDialog.dismiss();
        m_web.stopLoading();
        m_web.setDownloadListener(null);
        m_web.destroy();
        super.onDestroy();
    }

    // ----------------------------------------------------------------- Downloads ----

    private File downloadDir()
    {
        File d = new File(getCacheDir(), "mod_downloads");
        if(!d.isDirectory())
            d.mkdirs();
        return d;
    }

    private void cleanOldDownloads()
    {
        File[] fs = downloadDir().listFiles();
        if(fs != null)
            for(File f : fs)
                f.delete();
    }

    private static String safeFileName(String name)
    {
        int s = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if(s >= 0)
            name = name.substring(s + 1);
        name = name.replaceAll("[^\\p{L}\\p{N}._ ()\\-\\[\\]]", "_").trim();
        while(name.startsWith("."))
            name = name.substring(1);
        if(name.length() > 100)
        {
            int d = name.lastIndexOf('.');
            String ext = (d > 0 && name.length() - d <= 8) ? name.substring(d) : "";
            name = name.substring(0, 100 - ext.length()) + ext;
        }
        return name.isEmpty() ? "download.bin" : name;
    }

    private static boolean allowedExt(String lower)
    {
        String[] ok = {".zip", ".rar", ".7z", ".lvl", ".lvlx", ".wld", ".wldx"};
        for(String e : ok)
            if(lower.endsWith(e))
                return true;
        return false;
    }

    private void startDownload(final String url, final String ua, final String cd, final String mime, final long len)
    {
        final Uri u = Uri.parse(url);
        String sch = u.getScheme();
        if(!"https".equalsIgnoreCase(sch) && !"http".equalsIgnoreCase(sch))
        {
            Toast.makeText(this, "Only http(s) downloads are supported (not " + sch + ")", Toast.LENGTH_LONG).show();
            return;
        }
        if(len > MAX_DOWNLOAD_BYTES)
        {
            Toast.makeText(this, "File is larger than the " + (MAX_DOWNLOAD_BYTES >> 20) + " MB limit", Toast.LENGTH_LONG).show();
            return;
        }
        final String guess = safeFileName(URLUtil.guessFileName(url, cd, mime));
        if(!allowedExt(guess.toLowerCase(Locale.ROOT)))
        {
            // name may only be known after redirects: still let the user try, verified again after download
            N64Ui.show(N64Ui.dialog(this, "Download this file?", R.color.n64_yellow)
                .setMessage("\"" + guess + "\" does not look like a mod (.zip, .lvl, .lvlx, .wld, .wldx). Download anyway?")
                .setPositiveButton("Download", new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface d, int w) { runDownload(url, ua, cd, mime, len, guess); }
                })
                .setNegativeButton("Cancel", null));
            return;
        }
        runDownload(url, ua, cd, mime, len, guess);
    }

    private void runDownload(final String url, final String ua, final String cd, final String mime, final long len, final String guess)
    {
        m_cancelDl = false;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        m_dlText = new TextView(this);
        m_dlText.setText("Connecting...");
        m_dlText.setTextSize(20);
        m_dlText.setTextColor(getResources().getColor(R.color.n64_ink, getTheme()));
        m_dlText.setPadding(0, 0, 0, dp(10));
        m_dlBar = N64Ui.makeProgress(this, 20);
        m_dlBar.setMax(1000);
        m_dlBar.setIndeterminate(len <= 0);
        box.addView(m_dlText);
        box.addView(m_dlBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));

        m_dlDialog = N64Ui.show(N64Ui.dialog(this, "Downloading " + guess, R.color.n64_green)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
            {
                public void onClick(DialogInterface d, int w) { m_cancelDl = true; }
            }));

        final String cookie = CookieManager.getInstance().getCookie(url);

        new Thread(new Runnable()
        {
            public void run()
            {
                File part = null;
                try
                {
                    URL cur = new URL(url);
                    HttpURLConnection c = null;
                    for(int i = 0; ; i++)
                    {
                        if(i > MAX_REDIRECTS)
                            throw new IOException("Too many redirects");
                        c = (HttpURLConnection)cur.openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        if(ua != null) c.setRequestProperty("User-Agent", ua);
                        // cookies only for the origin the WebView gave them for
                        if(cookie != null && cur.getHost().equalsIgnoreCase(new URL(url).getHost()))
                            c.setRequestProperty("Cookie", cookie);
                        int code = c.getResponseCode();
                        if(code >= 300 && code < 400 && c.getHeaderField("Location") != null)
                        {
                            URL next = new URL(cur, c.getHeaderField("Location"));
                            String ns = next.getProtocol();
                            if(!ns.equals("http") && !ns.equals("https"))
                                throw new IOException("Redirect to unsupported protocol");
                            if(cur.getProtocol().equals("https") && ns.equals("http"))
                                throw new IOException("Refused redirect from https to insecure http");
                            c.disconnect();
                            cur = next;
                            continue;
                        }
                        if(code != 200)
                            throw new IOException("Server answered HTTP " + code);
                        break;
                    }

                    String disp = c.getHeaderField("Content-Disposition");
                    final String name = safeFileName(URLUtil.guessFileName(cur.toString(), disp != null ? disp : cd,
                            c.getContentType() != null ? c.getContentType() : mime));
                    long total = c.getContentLengthLong();
                    if(total > MAX_DOWNLOAD_BYTES)
                        throw new IOException("File is larger than the " + (MAX_DOWNLOAD_BYTES >> 20) + " MB limit");

                    final File out = new File(downloadDir(), name);
                    part = new File(downloadDir(), name + ".part");
                    InputStream in = c.getInputStream();
                    OutputStream os = new FileOutputStream(part);
                    try
                    {
                        byte[] buf = new byte[64 * 1024];
                        long got = 0;
                        long lastUi = 0;
                        int n;
                        while((n = in.read(buf)) > 0)
                        {
                            if(m_cancelDl)
                                throw new IOException("Cancelled");
                            got += n;
                            if(got > MAX_DOWNLOAD_BYTES)
                                throw new IOException("File exceeds the " + (MAX_DOWNLOAD_BYTES >> 20) + " MB limit");
                            os.write(buf, 0, n);
                            long now = System.currentTimeMillis();
                            if(now - lastUi > 150)
                            {
                                lastUi = now;
                                postProgress(got, total);
                            }
                        }
                    }
                    finally
                    {
                        try { in.close(); } catch(IOException ignored) {}
                        os.close();
                    }
                    if(out.exists())
                        out.delete();
                    if(!part.renameTo(out))
                        throw new IOException("Cannot store downloaded file");
                    part = null;
                    m_ui.post(new Runnable() { public void run() { downloadDone(out, name); } });
                }
                catch(final Exception e)
                {
                    if(part != null)
                        part.delete();
                    m_ui.post(new Runnable() { public void run() { downloadFailed(e.getMessage()); } });
                }
            }
        }, "ModBrowser-download").start();
    }

    private void postProgress(final long got, final long total)
    {
        m_ui.post(new Runnable()
        {
            public void run()
            {
                if(m_dlText == null)
                    return;
                if(total > 0)
                {
                    m_dlBar.setIndeterminate(false);
                    m_dlBar.setProgress((int)(got * 1000 / total));
                    m_dlText.setText((got >> 10) + " / " + (total >> 10) + " KB");
                }
                else
                    m_dlText.setText((got >> 10) + " KB");
            }
        });
    }

    private void dismissDl()
    {
        if(m_dlDialog != null && m_dlDialog.isShowing())
            m_dlDialog.dismiss();
        m_dlDialog = null;
    }

    private void downloadFailed(String msg)
    {
        if(isFinishing() || isDestroyed())
            return;
        dismissDl();
        Toast.makeText(this, "Download failed: " + msg, Toast.LENGTH_LONG).show();
    }

    private void downloadDone(final File file, final String name)
    {
        if(isFinishing() || isDestroyed())
            return;
        dismissDl();
        String lower = name.toLowerCase(Locale.ROOT);
        if(!allowedExt(lower))
        {
            Toast.makeText(this, "\"" + name + "\" is not a supported mod file; removed", Toast.LENGTH_LONG).show();
            file.delete();
            return;
        }
        new ModInstaller(this).install(file, name, new ModInstaller.Listener()
        {
            @Override
            public void onFinished(boolean success, File dest, String message)
            {
                file.delete();
                if(success)
                {
                    Toast.makeText(ModBrowserActivity.this,
                            message + "\n" + dest.getAbsolutePath() +
                            "\nIt will appear in the episode list the next time the game scans (restart the game if you do not see it).",
                            Toast.LENGTH_LONG).show();
                    finish(); // back to the game
                }
                else if(message != null && !message.startsWith("Not installed"))
                    Toast.makeText(ModBrowserActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
