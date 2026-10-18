package ru.wohlsoft.moondust;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.hardware.input.InputManager;
import android.view.Display;
import android.view.InputDevice;
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
        super.onCreate(savedInstanceState);

        setupWindow();

        if(!hasManageAppFS())
            return;

        LinearLayout ll = new LinearLayout(this);
        ll.setBackground(getResources().getDrawable(R.mipmap.buttons));
        addContentView(ll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        m_touchOverlay = ll;

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

    public boolean hasManageAppFS()
    {
        if(Build.VERSION.SDK_INT >= 30)
        {
            if(Environment.isExternalStorageManager())
                return true;

            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            String pName = getPackageName();
            Uri uri = Uri.fromParts("package", pName, null);
            intent.setData(uri);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

            return false;
        }

        return true;
    }

    public static native void setKeyPos(int cmd, float left, float top, float right, float bottom);
    public static native void setCanvasSize(float width, float height);

}
