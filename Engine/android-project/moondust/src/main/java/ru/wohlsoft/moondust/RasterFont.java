package ru.wohlsoft.moondust;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws text with a config pack raster font (fonts/NAME.font.ini + its font-map tables), the same format the
 * engine reads in Engine/fontman/raster_font.cpp: entries are "row-col = X[padding-left][padding-right]".
 */
public class RasterFont
{
    private static class Glyph
    {
        Bitmap tex;
        Rect src;
        int padLeft, padRight;
    }

    private final Map<Integer, Glyph> m_glyphs = new HashMap<>();
    private int m_cellW = 0, m_cellH = 0;
    private int m_spaceWidth = 0, m_interLetter = 0;

    public boolean isValid()
    {
        return !m_glyphs.isEmpty();
    }

    public int height()
    {
        return m_cellH;
    }

    /** Loads fontsDir/fontFile (for example "font2.font.ini"). Returns null when the font can not be read. */
    public static RasterFont load(String fontsDir, String fontFile)
    {
        RasterFont f = new RasterFont();
        Map<String, Map<String, String>> ini = readIni(new File(fontsDir, fontFile));
        if(ini == null)
            return null;
        Map<String, String> main = ini.get("font");
        if(main != null)
        {
            f.m_spaceWidth = toInt(main.get("space-width"), 0);
            f.m_interLetter = toInt(main.get("interletter-space"), 0);
        }
        Map<String, String> tables = ini.get("tables");
        if(tables != null)
        {
            for(String table : tables.values())
                f.loadTable(fontsDir, table);
        }
        if(f.m_spaceWidth == 0)
            f.m_spaceWidth = f.m_cellW;
        return f.isValid() ? f : null;
    }

    private void loadTable(String fontsDir, String tableFile)
    {
        Map<String, Map<String, String>> ini = readIni(new File(fontsDir, tableFile));
        if(ini == null || ini.get("font-map") == null || ini.get("entries") == null)
            return;
        Map<String, String> map = ini.get("font-map");
        int cols = toInt(map.get("width"), 0), rows = toInt(map.get("height"), 0);
        String texName = map.get("texture");
        if(cols <= 0 || rows <= 0 || texName == null)
            return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        Bitmap tex = BitmapFactory.decodeFile(new File(fontsDir, texName).getAbsolutePath(), o);
        if(tex == null)
            return;
        int cw = tex.getWidth() / cols, ch = tex.getHeight() / rows;
        if(m_cellW == 0)
        {
            m_cellW = cw;
            m_cellH = ch;
        }

        for(Map.Entry<String, String> e : ini.get("entries").entrySet())
        {
            String[] pos = e.getKey().split("-");
            String val = e.getValue();
            if(pos.length < 2 || val.isEmpty())
                continue;
            int row = toInt(pos[0].trim(), -1), col = toInt(pos[1].trim(), -1);
            if(row < 0 || col < 0)
                continue;
            Glyph g = new Glyph();
            g.tex = tex;
            g.src = new Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch);
            int cp = val.codePointAt(0);
            int rest = Character.charCount(cp);
            g.padLeft = val.length() > rest ? Character.digit(val.charAt(rest), 10) : 0;
            g.padRight = val.length() > rest + 1 ? Character.digit(val.charAt(rest + 1), 10) : 0;
            if(g.padLeft < 0) g.padLeft = 0;
            if(g.padRight < 0) g.padRight = 0;
            m_glyphs.put(cp, g);
        }
    }

    public int textWidth(String text)
    {
        int w = 0;
        for(int i = 0; i < text.length(); )
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Glyph g = m_glyphs.get(cp);
            if(g == null)
                w += m_spaceWidth + m_interLetter / 2;
            else
                w += m_cellW - g.padLeft - g.padRight + m_interLetter;
        }
        return w;
    }

    /** Draws text with its top-left corner at x, y. Unknown characters are drawn as spaces. */
    public void draw(Canvas c, String text, int x, int y, Paint p)
    {
        int offset = 0;
        Rect dst = new Rect();
        for(int i = 0; i < text.length(); )
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Glyph g = m_glyphs.get(cp);
            if(g == null)
            {
                offset += m_spaceWidth + m_interLetter / 2;
                continue;
            }
            dst.set(x + offset - g.padLeft, y, x + offset - g.padLeft + m_cellW, y + m_cellH);
            c.drawBitmap(g.tex, g.src, dst, p);
            offset += m_cellW - g.padLeft - g.padRight + m_interLetter;
        }
    }

    private static int toInt(String s, int def)
    {
        if(s == null)
            return def;
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch(NumberFormatException e)
        {
            return def;
        }
    }

    /** Minimal INI reader: [section], key = value, ';' comments, double-quoted values with \" escapes. */
    private static Map<String, Map<String, String>> readIni(File file)
    {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Map<String, String> cur = null;
        try(BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)))
        {
            String line;
            while((line = r.readLine()) != null)
            {
                line = line.trim();
                if(!line.isEmpty() && line.charAt(0) == '﻿')
                    line = line.substring(1);
                if(line.isEmpty() || line.startsWith(";"))
                    continue;
                if(line.startsWith("["))
                {
                    int end = line.indexOf(']');
                    cur = new LinkedHashMap<>();
                    out.put(line.substring(1, end > 0 ? end : line.length()).trim(), cur);
                    continue;
                }
                int eq = line.indexOf('=');
                if(eq < 0 || cur == null)
                    continue;
                cur.put(line.substring(0, eq).trim(), parseValue(line.substring(eq + 1).trim()));
            }
        }
        catch(IOException e)
        {
            return null;
        }
        return out;
    }

    private static String parseValue(String v)
    {
        if(v.startsWith("\""))
        {
            StringBuilder sb = new StringBuilder();
            for(int i = 1; i < v.length(); i++)
            {
                char ch = v.charAt(i);
                if(ch == '\\' && i + 1 < v.length())
                    sb.append(v.charAt(++i));
                else if(ch == '"')
                    break;
                else
                    sb.append(ch);
            }
            return sb.toString();
        }
        int sc = v.indexOf(';');
        return (sc >= 0 ? v.substring(0, sc) : v).trim();
    }
}
