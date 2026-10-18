package ru.wohlsoft.moondust;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Shared styling helpers for the native Android UI (mod browser, installer dialogs, overlay button).
 * Chunky beveled buttons, gray controller-body panels and the four accent colours; all original artwork
 * drawn from XML drawables and plain Java, no bundled fonts. The SDL/engine menus are not affected.
 */
final class N64Ui
{
    private N64Ui() {}

    /** Button colour roles: BLUE = confirm/action (A), GREEN = back (B), RED = cancel/destructive, DARK = neutral */
    static final int BLUE = 0, GREEN = 1, RED = 2, DARK = 3;

    static int dp(Context c, int v)
    {
        return (int)(v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static int color(Context c, int res)
    {
        return c.getResources().getColor(res, c.getTheme()); // API 23+, minSdk 24 ok
    }

    /** Bold sans-serif display face (system font, no bundled fonts) */
    static Typeface boldFace()
    {
        return Typeface.create("sans-serif-black", Typeface.BOLD); // falls back to bold sans when unavailable
    }

    private static int buttonDrawable(int role)
    {
        switch(role)
        {
        case BLUE:  return R.drawable.n64_btn_blue;
        case GREEN: return R.drawable.n64_btn_green;
        case RED:   return R.drawable.n64_btn_red;
        default:    return R.drawable.n64_btn_dark;
        }
    }

    /** Apply the chunky button look (>= 56dp tall, focus ring through the drawable's focused state). */
    static void styleButton(Button b, int role)
    {
        Context c = b.getContext();
        b.setBackground(c.getResources().getDrawable(buttonDrawable(role), c.getTheme()));
        b.setTextColor(c.getResources().getColorStateList(R.color.n64_btn_text, c.getTheme()));
        b.setTypeface(boldFace());
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 56));
        b.setMinimumHeight(dp(c, 56));
        b.setMinWidth(dp(c, 56));
        b.setMinimumWidth(dp(c, 56));
        b.setStateListAnimator(null); // drop the flat Material elevation animation
        b.setPadding(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 9)); // extra bottom = shadow edge
    }

    static Button makeButton(Context c, String text, int role, View.OnClickListener l)
    {
        Button b = new Button(c);
        b.setText(text);
        styleButton(b, role);
        if(l != null)
            b.setOnClickListener(l);
        return b;
    }

    /** Four equal red/green/blue/yellow bands */
    static View accentStrip(Context c, int heightDp)
    {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] cols = { R.color.n64_red, R.color.n64_green, R.color.n64_blue, R.color.n64_yellow };
        for(int col : cols)
        {
            View v = new View(c);
            v.setBackgroundColor(color(c, col));
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return row;
    }

    static TextView makeTitle(Context c, String text)
    {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTypeface(boldFace());
        t.setTextSize(22);
        t.setTextColor(color(c, R.color.n64_text_light));
        t.setShadowLayer(3f, 0f, 3f, color(c, R.color.n64_shadow));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** Recessed dark address field; the focused state draws a thick yellow ring (drawable selector). */
    static void styleField(EditText e)
    {
        Context c = e.getContext();
        e.setBackground(c.getResources().getDrawable(R.drawable.n64_field, c.getTheme()));
        e.setTextColor(color(c, R.color.n64_text_light));
        e.setHintTextColor(color(c, R.color.n64_gray_dark));
        e.setTextSize(18);
        e.setMinHeight(dp(c, 56));
        e.setPadding(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 4));
        e.setHighlightColor(0x66FFD500);
    }

    // ------------------------------------------------------------------ progress ----

    /** Blocks of red, green, blue, yellow cycling along the bar; the clip around it reveals them as progress grows. */
    private static final class SegmentDrawable extends Drawable
    {
        private final int[] m_cols;
        private final float m_seg, m_gap;
        private final Paint m_p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SegmentDrawable(Context c)
        {
            m_cols = new int[] { color(c, R.color.n64_red), color(c, R.color.n64_green),
                                 color(c, R.color.n64_blue), color(c, R.color.n64_yellow) };
            m_seg = dp(c, 16);
            m_gap = dp(c, 3);
        }

        @Override
        public void draw(Canvas cv)
        {
            Rect b = getBounds();
            float x = b.left;
            int i = 0;
            while(x < b.right)
            {
                m_p.setColor(m_cols[i++ % m_cols.length]);
                cv.drawRect(x, b.top, Math.min(x + m_seg, b.right), b.bottom, m_p);
                x += m_seg + m_gap;
            }
        }

        @Override public void setAlpha(int a) { m_p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { m_p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Horizontal progress bar with a dark track and a segmented four-colour fill. */
    static ProgressBar makeProgress(Context c, int heightDp)
    {
        ProgressBar pb = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        Drawable track = c.getResources().getDrawable(R.drawable.n64_progress_track, c.getTheme());
        ClipDrawable fill = new ClipDrawable(new SegmentDrawable(c), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[] { track, fill });
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        pb.setProgressDrawable(ld);
        pb.setIndeterminateTintList(ColorStateList.valueOf(color(c, R.color.n64_yellow))); // API 21
        pb.setMinimumHeight(dp(c, heightDp));
        return pb;
    }

    // ------------------------------------------------------------------ dialogs ----

    /** AlertDialog builder with the gray panel theme and a bold coloured title bar. accent = colour resource. */
    static AlertDialog.Builder dialog(Context c, String title, int accentColorRes)
    {
        AlertDialog.Builder b = new AlertDialog.Builder(c, R.style.N64Dialog);
        int accent = color(c, accentColorRes);
        boolean light = accentColorRes == R.color.n64_yellow;

        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), 0); // sit inside the panel border

        TextView t = makeTitle(c, title);
        if(light)
        {
            t.setTextColor(color(c, R.color.n64_ink));
            t.setShadowLayer(0f, 0f, 0f, 0);
        }
        t.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent);
        float r = dp(c, 13);
        bg.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        t.setBackground(bg);
        wrap.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(accentStrip(c, 4));
        b.setCustomTitle(wrap);
        return b;
    }

    /**
     * Show the dialog and finish styling that needs the built views: larger message text, wider window,
     * spaced chunky buttons. Pass a role to override a button's default colour (positive=blue, negative=red,
     * neutral=dark), or -1 to keep the theme default. The positive button gets the initial focus.
     */
    static AlertDialog show(AlertDialog.Builder b, int positiveRole, int neutralRole, int negativeRole)
    {
        AlertDialog d = b.create();
        d.show();
        Context c = d.getContext();
        TextView msg = (TextView)d.findViewById(android.R.id.message);
        if(msg != null)
        {
            msg.setTextSize(20);
            msg.setTextColor(color(c, R.color.n64_ink));
        }
        Window w = d.getWindow();
        if(w != null)
        {
            int sw = c.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(dp(c, 560), (int)(sw * 0.9f)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        int[] which = { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE };
        int[] roles = { positiveRole, neutralRole, negativeRole };
        for(int i = 0; i < which.length; i++)
        {
            Button btn = d.getButton(which[i]);
            if(btn == null)
                continue;
            if(roles[i] >= 0)
                styleButton(btn, roles[i]);
            else
                btn.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 9));
            btn.setStateListAnimator(null);
            btn.setAllCaps(false);
            btn.setTypeface(boldFace());
            btn.setTextSize(20);
            btn.setMinHeight(dp(c, 56));
            btn.setMinimumHeight(dp(c, 56));
            btn.setMinWidth(dp(c, 120));
            btn.setMinimumWidth(dp(c, 120));
            ViewGroup.LayoutParams lp = btn.getLayoutParams();
            if(lp instanceof ViewGroup.MarginLayoutParams)
            {
                ((ViewGroup.MarginLayoutParams)lp).setMargins(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 8));
                btn.setLayoutParams(lp);
            }
        }
        Button first = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if(first != null)
            first.requestFocus();
        return d;
    }

    static AlertDialog show(AlertDialog.Builder b)
    {
        return show(b, -1, -1, -1);
    }
}
