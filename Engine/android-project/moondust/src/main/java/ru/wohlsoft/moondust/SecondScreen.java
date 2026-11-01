package ru.wohlsoft.moondust;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.util.Locale;

/**
 * HUD on the second display of dual-screen handhelds (AYN Thor: the 1240x1080 bottom screen).
 *
 * The engine publishes a snapshot of the HUD (Engine/common_features/second_screen.cpp); this class polls it,
 * shows a Presentation on the second display while the "Second screen HUD" option is on, and draws the HUD with
 * the config pack's own graphics (commonGFX) and raster fonts, so it matches the SMBX2 look. While it is shown the
 * engine skips the in-game HUD. A touch "Pause" button sends Escape to the engine.
 */
public class SecondScreen implements DisplayManager.DisplayListener
{
    private static final String LOG_TAG = "SecondScreen";
    private static final int POLL_MS = 50;

    private static final int MODE_TITLE = 0;
    private static final int MODE_WORLD = 1;
    private static final int MODE_LEVEL = 2;

    private static native void nativeSetDisplayAttached(boolean attached);
    private static native int[] nativeGetState();
    private static native String[] nativeGetTexts();
    private static native void nativePressPause();

    private final Activity m_act;
    private final DisplayManager m_dm;
    private final Handler m_handler = new Handler(Looper.getMainLooper());
    private HudPresentation m_presentation = null;
    private boolean m_running = false;
    private int m_lastRevision = -1;

    private final Runnable m_poll = new Runnable()
    {
        @Override
        public void run()
        {
            poll();
            if(m_running)
                m_handler.postDelayed(this, POLL_MS);
        }
    };

    public SecondScreen(Activity act)
    {
        m_act = act;
        m_dm = (DisplayManager)act.getSystemService(Context.DISPLAY_SERVICE);
    }

    public void start()
    {
        if(m_running || m_dm == null)
            return;
        m_running = true;
        m_dm.registerDisplayListener(this, m_handler);
        m_handler.post(m_poll);
    }

    public void stop()
    {
        if(!m_running)
            return;
        m_running = false;
        m_handler.removeCallbacks(m_poll);
        m_dm.unregisterDisplayListener(this);
        hide();
    }

    /** First presentation display other than the one the game runs on, or null. */
    private Display findDisplay()
    {
        Display own = m_act.getWindowManager().getDefaultDisplay();
        for(Display d : m_dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
        {
            if(own == null || d.getDisplayId() != own.getDisplayId())
                return d;
        }
        return null;
    }

    private void poll()
    {
        int[] st;
        try
        {
            st = nativeGetState();
        }
        catch(UnsatisfiedLinkError e)
        {
            return; // Engine library not loaded yet
        }
        if(st == null || st.length < 9)
            return;

        boolean enabled = st[0] != 0;
        Display d = enabled ? findDisplay() : null;
        if(d == null)
        {
            hide();
            return;
        }
        if(m_presentation != null && m_presentation.getDisplay().getDisplayId() != d.getDisplayId())
            hide();
        if(m_presentation == null)
        {
            try
            {
                m_presentation = new HudPresentation(m_act, d);
                m_presentation.show();
                m_lastRevision = -1;
            }
            catch(WindowManager.InvalidDisplayException e)
            {
                Log.w(LOG_TAG, "Second display went away: " + e.getMessage());
                m_presentation = null;
                return;
            }
        }
        nativeSetDisplayAttached(true);

        if(st[2] != m_lastRevision)
        {
            m_lastRevision = st[2];
            m_presentation.view.setState(st, nativeGetTexts());
        }
    }

    private void hide()
    {
        if(m_presentation != null)
        {
            m_presentation.dismiss();
            m_presentation = null;
        }
        try
        {
            nativeSetDisplayAttached(false);
        }
        catch(UnsatisfiedLinkError e)
        {
            // Engine library not loaded yet
        }
    }

    @Override
    public void onDisplayAdded(int displayId)
    {
        poll();
    }

    @Override
    public void onDisplayRemoved(int displayId)
    {
        if(m_presentation != null && m_presentation.getDisplay().getDisplayId() == displayId)
            hide();
    }

    @Override
    public void onDisplayChanged(int displayId) {}

    private static class HudPresentation extends Presentation
    {
        final HudView view;

        HudPresentation(Context outer, Display display)
        {
            super(outer, display);
            view = new HudView(getContext());
        }

        @Override
        protected void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            setContentView(view);
            if(getWindow() != null)
                // Not focusable: touches still arrive, but the game window keeps focus and gamepad input
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                                     WindowManager.LayoutParams.FLAG_FULLSCREEN |
                                     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                       View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /**
     * Draws on a 620x540 virtual canvas scaled by whole steps (x2 on the Thor's 1240x1080 screen),
     * without filtering, so the pixel art stays sharp.
     */
    private static class HudView extends View
    {
        private static final int VW = 620, VH = 540;

        private final Paint m_paint = new Paint();
        private final Rect m_pauseRect = new Rect(160, 404, 460, 484);
        private boolean m_pausePressed = false;
        private float m_scale = 1f, m_offX = 0f, m_offY = 0f;

        private int m_mode = MODE_TITLE;
        private int m_lives, m_coins, m_stars, m_points, m_health, m_character = 1;
        private String m_title = "", m_subtitle = "", m_characterName = "";
        private String m_gfxDir = null, m_fontsDir = null;

        private Bitmap m_titleBg, m_worldBg, m_logo, m_box, m_itemSlot, m_heart, m_heartEmpty, m_oneUp, m_ex, m_star, m_coin;
        private RasterFont m_font, m_numeric;

        HudView(Context ctx)
        {
            super(ctx);
            m_paint.setFilterBitmap(false);
            m_paint.setAntiAlias(false);
            setBackgroundColor(Color.BLACK);
        }

        void setState(int[] st, String[] texts)
        {
            m_mode = st[1];
            m_lives = st[3];
            m_coins = st[4];
            m_stars = st[5];
            m_points = st[6];
            m_health = st[7];
            m_character = st[8];
            if(texts != null && texts.length >= 5)
            {
                m_title = texts[0] == null ? "" : texts[0];
                m_subtitle = texts[1] == null ? "" : texts[1];
                m_characterName = texts[4] == null ? "" : texts[4];
                loadAssets(texts[2], texts[3]);
            }
            invalidate();
        }

        private void loadAssets(String gfxDir, String fontsDir)
        {
            if(gfxDir == null || gfxDir.isEmpty() || gfxDir.equals(m_gfxDir) && fontsDir.equals(m_fontsDir))
                return;
            m_gfxDir = gfxDir;
            m_fontsDir = fontsDir;
            m_titleBg = img("title_background.png");
            m_worldBg = img("world_back.png");
            m_logo = img("title.png");
            m_box = img("message_box.png");
            m_itemSlot = img("itemslot.png");
            m_heart = img("heart.png");
            m_heartEmpty = img("heart_n.png");
            m_oneUp = img("1up.png");
            m_ex = img("ex.png");
            m_star = img("stars.png");
            m_coin = img("coins.png");
            m_font = RasterFont.load(fontsDir, "font2.font.ini");
            m_numeric = RasterFont.load(fontsDir, "numeric.font.ini");
        }

        private Bitmap img(String name)
        {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeFile(new File(m_gfxDir, name).getAbsolutePath(), o);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh)
        {
            float s = Math.min(w / (float)VW, h / (float)VH);
            m_scale = s >= 1f ? (float)Math.floor(s) : s;
            m_offX = (w - VW * m_scale) / 2f;
            m_offY = (h - VH * m_scale) / 2f;
        }

        @Override
        protected void onDraw(Canvas c)
        {
            c.save();
            c.translate(m_offX, m_offY);
            c.scale(m_scale, m_scale);
            c.clipRect(0, 0, VW, VH);

            if(m_mode == MODE_TITLE)
                drawTitle(c);
            else
                drawGame(c);

            c.restore();
        }

        private void drawTitle(Canvas c)
        {
            cover(c, m_titleBg);
            if(m_logo != null)
                c.drawBitmap(m_logo, (VW - m_logo.getWidth()) / 2f, 40, m_paint);
            box(c, new Rect(110, 330, 510, 400));
            text(c, m_font, "SELECT AN EPISODE", VW / 2, 354, true);
        }

        private void drawGame(Canvas c)
        {
            pattern(c, m_worldBg);

            // Title bar: level name, or episode name + the level under the cursor on the world map
            Rect titleBox = new Rect(20, 16, VW - 20, m_mode == MODE_WORLD ? 112 : 84);
            box(c, titleBox);
            text(c, m_font, fit(m_title.toUpperCase(Locale.ROOT), titleBox.width() - 40), VW / 2, titleBox.top + 22, true);
            if(m_mode == MODE_WORLD)
                text(c, m_font, fit(m_subtitle.toUpperCase(Locale.ROOT), titleBox.width() - 40), VW / 2, titleBox.top + 56, true);

            // Status panel, laid out like the SMBX2 in-level HUD, at twice the size
            Rect panel = new Rect(20, m_mode == MODE_WORLD ? 128 : 100, VW - 20, 384);
            box(c, panel);
            int cx = VW / 2;
            if(m_character >= 3)
            {
                for(int i = 0; i < 3; i++)
                {
                    Bitmap h = (m_health >= i + 1) ? m_heart : m_heartEmpty;
                    sprite(c, h, cx - 70 + 50 * i - 22, 186, 2);
                }
            }
            else
                sprite(c, m_itemSlot, cx - 56, 172, 2);

            text(c, m_font, m_characterName.toUpperCase(Locale.ROOT), cx, 304, true);

            // Left: lives and stars
            sprite(c, m_oneUp, 52, 196, 2);
            sprite(c, m_ex, 122, 198, 2);
            number(c, m_lives, 156, 198, false);
            sprite(c, m_star, 84, 254, 2);
            sprite(c, m_ex, 122, 256, 2);
            number(c, m_stars, 156, 256, false);

            // Right: coins and score
            sprite(c, m_coin, 412, 196, 2);
            sprite(c, m_ex, 450, 198, 2);
            number(c, m_coins, VW - 52, 198, true);
            number(c, m_points, VW - 52, 256, true);

            // Pause button (touch): message-box style, darker while pressed
            box(c, m_pauseRect);
            if(m_pausePressed)
            {
                Paint dim = new Paint();
                dim.setColor(0x66000000);
                c.drawRect(m_pauseRect, dim);
            }
            text(c, m_font, "PAUSE", m_pauseRect.centerX(), m_pauseRect.top + 24, true);
        }

        /** Background image scaled to cover the canvas, keeping its aspect ratio, cropped at the centre. */
        private void cover(Canvas c, Bitmap b)
        {
            if(b == null)
            {
                c.drawColor(0xFF5C94FC);
                return;
            }
            float s = Math.max(VW / (float)b.getWidth(), VH / (float)b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            Rect dst = new Rect(Math.round((VW - w) / 2), Math.round((VH - h) / 2),
                                Math.round((VW + w) / 2), Math.round((VH + h) / 2));
            c.drawBitmap(b, null, dst, m_paint);
        }

        /** Tiles the starry top band of world_back.png (above its map window) over the whole canvas. */
        private void pattern(Canvas c, Bitmap b)
        {
            if(b == null)
            {
                c.drawColor(0xFFD8CCA8);
                return;
            }
            int bandH = Math.min(120, b.getHeight());
            Rect src = new Rect(0, 0, Math.min(VW, b.getWidth()), bandH);
            for(int y = 0; y < VH; y += bandH)
                for(int x = 0; x < VW; x += src.width())
                    c.drawBitmap(b, src, new Rect(x, y, x + src.width(), y + bandH), m_paint);
        }

        /** Nine-slice of message_box.png, like the engine's message boxes. */
        private void box(Canvas c, Rect r)
        {
            if(m_box == null)
            {
                Paint p = new Paint();
                p.setColor(0xFF085EA8);
                c.drawRect(r, p);
                return;
            }
            int bw = m_box.getWidth(), bh = m_box.getHeight();
            int e = Math.min(8, Math.min(r.width(), r.height()) / 2);
            int[] sx = { 0, e, bw - e, bw }, sy = { 0, e, bh - e, bh };
            int[] dx = { r.left, r.left + e, r.right - e, r.right }, dy = { r.top, r.top + e, r.bottom - e, r.bottom };
            for(int i = 0; i < 3; i++)
                for(int j = 0; j < 3; j++)
                    c.drawBitmap(m_box, new Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]),
                                 new Rect(dx[i], dy[j], dx[i + 1], dy[j + 1]), m_paint);
        }

        private void sprite(Canvas c, Bitmap b, int x, int y, int scale)
        {
            if(b == null)
                return;
            c.drawBitmap(b, null, new Rect(x, y, x + b.getWidth() * scale, y + b.getHeight() * scale), m_paint);
        }

        /** Text in a raster font; centred on x when center is set. */
        private void text(Canvas c, RasterFont f, String s, int x, int y, boolean center)
        {
            if(f == null || s.isEmpty())
                return;
            int w = f.textWidth(s);
            f.draw(c, s, center ? x - w / 2 : x, y, m_paint);
        }

        /** Counter in the numeric font at double size; right-aligned to x when alignRight is set. */
        private void number(Canvas c, int value, int x, int y, boolean alignRight)
        {
            if(m_numeric == null)
                return;
            String s = Integer.toString(value);
            int w = m_numeric.textWidth(s) * 2;
            c.save();
            c.translate(alignRight ? x - w : x, y);
            c.scale(2, 2);
            m_numeric.draw(c, s, 0, 0, m_paint);
            c.restore();
        }

        /** Cuts the text so it fits the given width in the main font. */
        private String fit(String s, int maxW)
        {
            if(m_font == null || m_font.textWidth(s) <= maxW)
                return s;
            while(s.length() > 1 && m_font.textWidth(s + "...") > maxW)
                s = s.substring(0, s.length() - 1);
            return s + "...";
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev)
        {
            if(m_mode == MODE_TITLE)
                return false;
            int vx = Math.round((ev.getX() - m_offX) / m_scale), vy = Math.round((ev.getY() - m_offY) / m_scale);
            boolean inside = m_pauseRect.contains(vx, vy);
            switch(ev.getActionMasked())
            {
            case MotionEvent.ACTION_DOWN:
                m_pausePressed = inside;
                invalidate();
                return inside;
            case MotionEvent.ACTION_MOVE:
                if(m_pausePressed != inside)
                {
                    m_pausePressed = inside;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(m_pausePressed && inside)
                {
                    try
                    {
                        nativePressPause();
                    }
                    catch(UnsatisfiedLinkError e)
                    {
                        // Engine library not loaded
                    }
                }
                m_pausePressed = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                m_pausePressed = false;
                invalidate();
                return true;
            }
            return super.onTouchEvent(ev);
        }
    }
}
