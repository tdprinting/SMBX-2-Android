package ru.wohlsoft.moondust;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.hardware.input.InputManager;
import android.view.Display;
import android.view.InputDevice;
import android.view.Gravity;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.libsdl.app.SDLActivity;

enum ControllerKeys
{
    key_BEGIN(0),
    key_start(0),
    key_left(1),
    key_right(2),
    key_up(3),
    key_down(4),
    key_run(5),
    key_jump(6),
    key_altrun(7),
    key_altjump(8),
    key_drop(9),
    key_END(10);

    private final int value;

    ControllerKeys(final int newValue)
    {
        value = newValue;
    }

    public int getValue()
    {
        return value;
    }
}

public class moondustActivity extends SDLActivity implements InputManager.InputDeviceListener
{
    final String LOG_TAG = "Moondust";
    public static final int READWRITE_PERMISSION_FOR_GAME = 1;
    private Bundle m_savedInstanceState = null;
    //! On-screen controls overlay (hidden when a physical gamepad is connected)
    private LinearLayout m_touchOverlay = null;
    //! Small "Mods" button, visible only together with the touch overlay (no gamepad)
    private Button m_modsButton = null;
    //! Select+Start long-press tracking (opens the mod browser)
    private boolean m_selectDown = false, m_startDown = false;
    private final Handler m_comboHandler = new Handler(Looper.getMainLooper());
    private final Runnable m_comboRun = new Runnable()
    {
        @Override
        public void run()
        {
            if(m_selectDown && m_startDown)
                openModBrowser();
        }
    };

    protected String[] getLibraries()
    {
        return new String[] {
//          "SDL2",
            "pge_engine"
        };
    }

    private String detectLanguage()
    {
        String lang = Locale.getDefault().toString();
        String[] langD = lang.split("_");
        if(langD.length >= 1)
            return langD[0];
        return "";
    }

    protected String[] getArguments()
    {
        List<String> args = new ArrayList<>();

        // Detect current language of the system
        String lang = detectLanguage();
        if(lang.length() >= 1)
            args.add("--lang=" + lang);

        String[] argsOut = new String[args.size()];
        args.toArray(argsOut);

        return argsOut;
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        // The engine thread starts only after the window gets focus and onResume() runs, which never
        // happens for an activity finished inside onCreate(). So ask for all-files access first and
        // bail out before the engine can cache wrong (non-writable) storage paths.
        final boolean needFsGrant = !hasManageAppFS();
        super.onCreate(savedInstanceState);
        if(needFsGrant)
        {
            finish();
            return;
        }

        setupWindow();

        LinearLayout ll = new LinearLayout(this);
        ll.setBackground(getResources().getDrawable(R.mipmap.buttons));
        addContentView(ll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        m_touchOverlay = ll;

        m_modsButton = new Button(this);
        m_modsButton.setText("Mods");
        m_modsButton.setAllCaps(false);
        m_modsButton.setAlpha(0.7f);
        m_modsButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v) { openModBrowser(); }
        });
        addContentView(m_modsButton, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));

        InputManager im = (InputManager)getSystemService(Context.INPUT_SERVICE);
        if(im != null)
            im.registerInputDeviceListener(this, null);
        updateTouchOverlay();
    }

    @Override
    protected void onDestroy()
    {
        InputManager im = (InputManager)getSystemService(Context.INPUT_SERVICE);
        if(im != null)
            im.unregisterInputDeviceListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        updateTouchOverlay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)
            applyImmersive();
    }

    @Override
    public void onInputDeviceAdded(int deviceId)
    {
        updateTouchOverlay();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId)
    {
        updateTouchOverlay();
    }

    @Override
    public void onInputDeviceChanged(int deviceId)
    {
        updateTouchOverlay();
    }

    /** Is any physical gamepad (built-in or external) connected? */
    private boolean hasGamepad()
    {
        for(int id : InputDevice.getDeviceIds())
        {
            InputDevice dev = InputDevice.getDevice(id);
            if(dev == null || dev.isVirtual())
                continue;
            if((dev.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                return true;
        }
        return false;
    }

    /** Hide the on-screen controls when a gamepad is connected, show them otherwise */
    private void updateTouchOverlay()
    {
        final boolean pad = hasGamepad();
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if(m_touchOverlay != null)
                    m_touchOverlay.setVisibility(pad ? View.INVISIBLE : View.VISIBLE);
                if(m_modsButton != null)
                    m_modsButton.setVisibility(pad ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void applyImmersive()
    {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setupWindow()
    {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams lp = w.getAttributes();

        // Draw under the display cutout, no letterboxing
        if(Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        // Request the highest refresh rate at the current resolution (120 Hz on AYN Odin 2 Portal)
        if(Build.VERSION.SDK_INT >= 23)
        {
            try
            {
                Display d = getWindowManager().getDefaultDisplay();
                Display.Mode cur = d.getMode();
                Display.Mode best = cur;
                for(Display.Mode m : d.getSupportedModes())
                {
                    if(m.getPhysicalWidth() == cur.getPhysicalWidth() &&
                       m.getPhysicalHeight() == cur.getPhysicalHeight() &&
                       m.getRefreshRate() > best.getRefreshRate())
                        best = m;
                }
                lp.preferredDisplayModeId = best.getModeId();
            }
            catch(Exception e)
            {
                // Keep the default display mode
            }
        }
        w.setAttributes(lp);
        applyImmersive();
    }

    /**
     * Returns true when the engine may start now. On API 30+ without all-files access, the Settings page is
     * opened once (the user then relaunches the game after granting). If the user already was asked and
     * declined, returns true so the game runs using its app-specific folder.
     */
    public boolean hasManageAppFS()
    {
        if(Build.VERSION.SDK_INT >= 30)
        {
            if(Environment.isExternalStorageManager())
                return true;

            SharedPreferences sp = getSharedPreferences("moondust_prefs", Context.MODE_PRIVATE);
            if(sp.getBoolean("fs_access_prompted", false))
                return true; // declined earlier: fall back to the app-specific folder

            sp.edit().putBoolean("fs_access_prompted", true).apply();
            try
            {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Allow access to all files, then start the game again", Toast.LENGTH_LONG).show();
                return false;
            }
            catch(Exception e)
            {
                return true; // Settings page unavailable: run with the app-specific folder
            }
        }

        return true;
    }

    /** Open the in-app mod browser. Safe to call from any thread (JNI: method "openModBrowser", signature "()V"
     *  on the activity object returned by SDL_AndroidGetActivity(), or the static variant below). */
    public void openModBrowser()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                startActivity(new Intent(moondustActivity.this, ModBrowserActivity.class));
            }
        });
    }

    /** Static JNI-friendly entry: GetStaticMethodID(cls, "openModBrowserStatic", "()V") */
    public static void openModBrowserStatic()
    {
        if(mSingleton instanceof moondustActivity)
            ((moondustActivity)mSingleton).openModBrowser();
    }

    /** Select+Start held for ~1.2 s opens the mod browser; events are still passed to the game. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        int k = event.getKeyCode();
        if(k == KeyEvent.KEYCODE_BUTTON_SELECT || k == KeyEvent.KEYCODE_BUTTON_START)
        {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if(k == KeyEvent.KEYCODE_BUTTON_SELECT) m_selectDown = down; else m_startDown = down;
            m_comboHandler.removeCallbacks(m_comboRun);
            if(m_selectDown && m_startDown)
                m_comboHandler.postDelayed(m_comboRun, 1200);
        }
        return super.dispatchKeyEvent(event);
    }

    public static native void setKeyPos(int cmd, float left, float top, float right, float bottom);
    public static native void setCanvasSize(float width, float height);

}
