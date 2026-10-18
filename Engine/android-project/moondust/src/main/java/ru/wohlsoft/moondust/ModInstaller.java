package ru.wohlsoft.moondust;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safely installs a downloaded mod (.zip / single level or world file) into the Moondust folders.
 * Uses only the Android SDK. Nothing downloaded is ever executed; native/executable file types are skipped.
 */
public class ModInstaller
{
    // ---------------------------------------------------------------- Destination (EDIT HERE) ----
    /** Root folder on the external storage. NOTE: the engine currently still uses "/sdcard/PGE Project";
     *  change these constants when the engine side layout is finalized (see MODS_ANDROID.md if present). */
    public static final String ROOT_DIR_NAME     = "Moondust";
    public static final String EPISODES_SUBDIR   = "episodes";
    public static final String CONFIGS_SUBDIR    = "configs";

    // ---------------------------------------------------------------- Safety limits ----
    public static final int  MAX_ENTRIES         = 20000;
    public static final long MAX_FILE_BYTES      = 1024L * 1024L * 1024L;     // 1 GB per file
    public static final long MAX_TOTAL_BYTES     = 2048L * 1024L * 1024L;     // 2 GB overall
    private static final int MAX_PATH_DEPTH      = 32;

    private static final String PREFS            = "mod_browser";
    private static final String PREF_WARNED      = "lua_warning_shown";

    public enum Kind { EPISODE, LEVEL, CONFIG }

    public interface Listener
    {
        /** Called on the UI thread. dest is null when cancelled or failed. */
        void onFinished(boolean success, File dest, String message);
    }

    /** One file to install: zipName is the entry name in the archive (null for a single-file install). */
    private static class Item
    {
        String zipName;
        String relPath;
    }

    private static class Plan
    {
        Kind kind;
        String folderName;
        List<Item> items = new ArrayList<>();
        int skippedBlocked = 0;
        long declaredTotal = 0;
    }

    private final Activity m_act;
    private final Handler m_ui = new Handler(Looper.getMainLooper());

    public ModInstaller(Activity act)
    {
        m_act = act;
    }

    public static File getRoot()
    {
        return new File(Environment.getExternalStorageDirectory(), ROOT_DIR_NAME);
    }

    // ================================================================= Public flow ====

    /** Entry point: shows warning (once), confirmation, handles conflicts, and installs on a worker thread. */
    public void install(final File file, final String displayName, final Listener l)
    {
        final String lower = displayName.toLowerCase(Locale.ROOT);

        if(lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") ||
           lower.endsWith(".gz") || lower.endsWith(".tgz"))
        {
            message("Unsupported archive",
                    "\"" + displayName + "\" is not a .zip archive and cannot be extracted inside the app.\n\n" +
                    "Extract it with a file manager and copy the folder into:\n" +
                    new File(getRoot(), EPISODES_SUBDIR).getAbsolutePath(), l);
            return;
        }

        final boolean isZip = lower.endsWith(".zip");
        if(!isZip && !isGameFile(lower))
        {
            message("Unsupported file", "\"" + displayName + "\" is not a mod file I can install.", l);
            return;
        }

        showLuaWarningOnce(new Runnable()
        {
            @Override
            public void run()
            {
                confirm(file, displayName, isZip, l);
            }
        });
    }

    private void showLuaWarningOnce(final Runnable next)
    {
        SharedPreferences sp = m_act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if(sp.getBoolean(PREF_WARNED, false))
        {
            next.run();
            return;
        }
        new AlertDialog.Builder(m_act)
            .setTitle("Mods can run scripts")
            .setMessage("Mods may contain Lua scripts that run inside the game engine with the game's permissions. " +
                        "Only install mods from sources you trust.\n\n" +
                        "Moondust never runs native code from downloads; executable files (.so, .exe, .apk, ...) are skipped.")
            .setCancelable(false)
            .setPositiveButton("I understand", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w)
                {
                    m_act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_WARNED, true).apply();
                    next.run();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirm(final File file, final String displayName, final boolean isZip, final Listener l)
    {
        new AlertDialog.Builder(m_act)
            .setTitle("Install mod?")
            .setMessage("Install \"" + displayName + "\" (" + (file.length() / 1024) + " KB)?")
            .setPositiveButton("Install", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w)
                {
                    analyzeThenInstall(file, displayName, isZip, l);
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w)
                {
                    l.onFinished(false, null, "Installation cancelled");
                }
            })
            .setOnCancelListener(new DialogInterface.OnCancelListener()
            {
                @Override
                public void onCancel(DialogInterface d)
                {
                    l.onFinished(false, null, "Installation cancelled");
                }
            })
            .show();
    }

    private void analyzeThenInstall(final File file, final String displayName, final boolean isZip, final Listener l)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                final Plan plan;
                try
                {
                    plan = isZip ? analyzeZip(file, displayName) : analyzeSingle(displayName);
                }
                catch(final Exception e)
                {
                    m_ui.post(new Runnable() { public void run() { message("Cannot read mod", String.valueOf(e.getMessage()), l); } });
                    return;
                }
                m_ui.post(new Runnable() { public void run() { resolveConflict(file, plan, l); } });
            }
        }, "ModInstaller-analyze").start();
    }

    private File baseFor(Kind k)
    {
        return new File(getRoot(), k == Kind.CONFIG ? CONFIGS_SUBDIR : EPISODES_SUBDIR);
    }

    private void resolveConflict(final File file, final Plan plan, final Listener l)
    {
        final File base = baseFor(plan.kind);
        final File dest = new File(base, plan.folderName);
        if(!dest.exists())
        {
            runInstall(file, plan, dest, false, l);
            return;
        }
        new AlertDialog.Builder(m_act)
            .setTitle("Already installed")
            .setMessage("\"" + plan.folderName + "\" already exists in " + base.getAbsolutePath() +
                        ".\nReplace it (existing files, including saves stored inside it, are deleted) or keep both?")
            .setPositiveButton("Replace", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w) { runInstall(file, plan, dest, true, l); }
            })
            .setNeutralButton("Keep both", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w)
                {
                    File alt = dest;
                    for(int i = 2; alt.exists() && i < 1000; i++)
                        alt = new File(base, plan.folderName + " (" + i + ")");
                    runInstall(file, plan, alt, false, l);
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w) { l.onFinished(false, null, "Installation cancelled"); }
            })
            .show();
    }

    private void runInstall(final File file, final Plan plan, final File dest, final boolean replace, final Listener l)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                File staging = null;
                try
                {
                    File base = dest.getParentFile();
                    if(!base.isDirectory() && !base.mkdirs())
                        throw new IOException("Cannot create " + base.getAbsolutePath());
                    // Staging dir lives next to the destination (same filesystem => cheap rename, nothing half-installed)
                    staging = new File(getRoot(), ".staging-" + System.nanoTime());
                    if(!staging.mkdirs())
                        throw new IOException("Cannot create staging folder");

                    extract(file, plan, staging);

                    if(replace && dest.exists())
                        deleteRecursive(dest);
                    if(dest.exists())
                        throw new IOException("Destination appeared during install: " + dest.getName());
                    if(!staging.renameTo(dest))
                        throw new IOException("Cannot move files into " + dest.getAbsolutePath());
                    staging = null;

                    final String msg = (plan.skippedBlocked > 0 ? plan.skippedBlocked + " executable file(s) were skipped. " : "") +
                                       "Installed as " + plan.kind.name().toLowerCase(Locale.ROOT);
                    m_ui.post(new Runnable() { public void run() { l.onFinished(true, dest, msg); } });
                }
                catch(final Exception e)
                {
                    m_ui.post(new Runnable() { public void run() { message("Install failed", String.valueOf(e.getMessage()), l); } });
                }
                finally
                {
                    if(staging != null)
                        deleteRecursive(staging);
                }
            }
        }, "ModInstaller-extract").start();
    }

    private void message(String title, String text, final Listener l)
    {
        new AlertDialog.Builder(m_act)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("OK", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface d, int w) { l.onFinished(false, null, "Not installed"); }
            })
            .setCancelable(false)
            .show();
    }

    // ================================================================= Analysis ====

    private static boolean isGameFile(String lowerName)
    {
        return lowerName.endsWith(".lvl") || lowerName.endsWith(".lvlx") ||
               lowerName.endsWith(".wld") || lowerName.endsWith(".wldx");
    }

    /** Native code / scripts that the OS could run; never written to disk. (Lua is data for the engine and is kept.) */
    private static boolean isBlocked(String lower)
    {
        String[] bad = {".so", ".dll", ".exe", ".apk", ".dex", ".jar", ".sh", ".bat", ".cmd", ".msi", ".dylib", ".com", ".scr"};
        for(String b : bad)
            if(lower.endsWith(b))
                return true;
        return false;
    }

    private static String sanitizeName(String s)
    {
        s = s.replaceAll("[^\\p{L}\\p{N}._ ()\\-\\[\\]]", "_").trim();
        while(s.startsWith("."))
            s = s.substring(1);
        if(s.length() > 80)
            s = s.substring(0, 80);
        return s.isEmpty() ? "mod" : s;
    }

    private static String stripExt(String name)
    {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    private Plan analyzeSingle(String displayName)
    {
        Plan p = new Plan();
        p.kind = Kind.EPISODE;
        p.folderName = sanitizeName(stripExt(displayName));
        Item it = new Item();
        it.zipName = null;
        it.relPath = sanitizeName(displayName);
        p.items.add(it);
        String lower = displayName.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".lvl") || lower.endsWith(".lvlx"))
            p.kind = Kind.LEVEL;
        return p;
    }

    /** Normalizes an entry name; returns null when it must be rejected silently (ignored), throws if hostile. */
    private static String normalizeEntry(String name) throws IOException
    {
        if(name.indexOf('\0') >= 0)
            throw new IOException("Archive contains an invalid file name");
        String n = name.replace('\\', '/');
        while(n.startsWith("./"))
            n = n.substring(2);
        if(n.startsWith("/") || (n.length() > 1 && n.charAt(1) == ':'))
            throw new IOException("Archive contains an absolute path: " + name);
        String[] parts = n.split("/");
        if(parts.length > MAX_PATH_DEPTH)
            throw new IOException("Archive path is too deep");
        for(String s : parts)
            if(s.equals(".."))
                throw new IOException("Archive contains a path-traversal entry: " + name);
        return n;
    }

    private Plan analyzeZip(File file, String displayName) throws IOException
    {
        ZipFile zf;
        try
        {
            zf = new ZipFile(file);
        }
        catch(Exception e)
        {
            throw new IOException("This is not a valid zip file (" + e.getMessage() + ")");
        }
        try
        {
            List<String> names = new ArrayList<>();
            long total = 0;
            int count = 0;
            Plan p = new Plan();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while(en.hasMoreElements())
            {
                ZipEntry e = en.nextElement();
                if(++count > MAX_ENTRIES)
                    throw new IOException("Archive has too many entries (limit " + MAX_ENTRIES + ")");
                String n = normalizeEntry(e.getName());
                if(e.isDirectory() || n.endsWith("/"))
                    continue;
                if(n.startsWith("__MACOSX/") || n.endsWith(".DS_Store") || n.endsWith("Thumbs.db"))
                    continue;
                long sz = e.getSize();
                if(sz > MAX_FILE_BYTES)
                    throw new IOException("A file in the archive is too large");
                if(sz > 0)
                    total += sz;
                if(total > MAX_TOTAL_BYTES)
                    throw new IOException("Archive is too large when extracted (limit " + (MAX_TOTAL_BYTES >> 20) + " MB)");
                if(isBlocked(n.toLowerCase(Locale.ROOT)))
                {
                    p.skippedBlocked++;
                    continue;
                }
                names.add(n);
            }
            p.declaredTotal = total;
            if(names.isEmpty())
                throw new IOException("The archive contains no installable files");

            // Single top folder => strip it (no double nesting)
            String top = null;
            boolean single = true;
            for(String n : names)
            {
                int s = n.indexOf('/');
                if(s < 0) { single = false; break; }
                String t = n.substring(0, s);
                if(top == null) top = t;
                else if(!top.equals(t)) { single = false; break; }
            }
            String prefix = single ? top + "/" : "";

            boolean hasWorld = false, hasLevel = false, hasConfig = false;
            for(String n : names)
            {
                String rel = n.substring(prefix.length());
                String l = rel.toLowerCase(Locale.ROOT);
                String base = l.substring(l.lastIndexOf('/') + 1);
                if(l.endsWith(".wld") || l.endsWith(".wldx")) hasWorld = true;
                else if(l.endsWith(".lvl") || l.endsWith(".lvlx")) hasLevel = true;
                if(base.equals("main.ini") || base.equals("lvl_blocks.ini") || base.equals("lvl_bgo.ini") ||
                   base.equals("lvl_bkgrd.ini") || base.equals("npc_config.ini"))
                    hasConfig = true;
                Item it = new Item();
                it.zipName = n;
                it.relPath = rel;
                p.items.add(it);
            }

            if(hasWorld) p.kind = Kind.EPISODE;
            else if(hasConfig && !hasLevel) p.kind = Kind.CONFIG;
            else if(hasLevel) p.kind = Kind.LEVEL;
            else p.kind = Kind.EPISODE; // unknown content: treat like an episode folder

            p.folderName = sanitizeName(single ? top : stripExt(displayName));
            return p;
        }
        finally
        {
            try { zf.close(); } catch(IOException ignored) {}
        }
    }

    // ================================================================= Extraction ====

    private void extract(File file, Plan plan, File staging) throws IOException
    {
        String stagingCanon = staging.getCanonicalPath() + File.separator;
        long[] total = {0};
        ZipFile zf = null;
        try
        {
            if(plan.items.size() == 1 && plan.items.get(0).zipName == null)
            {
                Item it = plan.items.get(0);
                copyChecked(new FileInputStream(file), safeTarget(staging, stagingCanon, it.relPath), total);
                return;
            }
            zf = new ZipFile(file);
            for(Item it : plan.items)
            {
                ZipEntry e = zf.getEntry(it.zipName);
                if(e == null)
                    throw new IOException("Archive changed during install");
                File target = safeTarget(staging, stagingCanon, it.relPath);
                // Entries are written as regular files only, so symlink entries in the archive can never create links.
                copyChecked(zf.getInputStream(e), target, total);
            }
        }
        finally
        {
            if(zf != null)
                try { zf.close(); } catch(IOException ignored) {}
        }
    }

    private static File safeTarget(File staging, String stagingCanon, String rel) throws IOException
    {
        File t = new File(staging, rel);
        String canon = t.getCanonicalPath();
        if(!canon.startsWith(stagingCanon))
            throw new IOException("Blocked unsafe path in archive: " + rel);
        File parent = t.getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create folder for " + rel);
        // parent chain was created by us inside staging; re-verify after creation
        if(!parent.getCanonicalPath().startsWith(stagingCanon.substring(0, stagingCanon.length() - 1)))
            throw new IOException("Blocked unsafe path in archive: " + rel);
        if(t.exists())
            throw new IOException("Archive contains duplicate entries: " + rel);
        return t;
    }

    /** Copies while counting real bytes (declared sizes in zips can lie). */
    private static void copyChecked(InputStream in, File out, long[] total) throws IOException
    {
        OutputStream os = null;
        try
        {
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            long fileBytes = 0;
            int n;
            while((n = in.read(buf)) > 0)
            {
                fileBytes += n;
                total[0] += n;
                if(fileBytes > MAX_FILE_BYTES || total[0] > MAX_TOTAL_BYTES)
                    throw new IOException("Archive expands beyond the size limit (possible zip bomb)");
                os.write(buf, 0, n);
            }
        }
        finally
        {
            try { in.close(); } catch(IOException ignored) {}
            if(os != null)
                try { os.close(); } catch(IOException ignored) {}
        }
    }

    /** Deletes a tree without following symlinks. */
    public static void deleteRecursive(File f)
    {
        try
        {
            boolean link = !f.getCanonicalPath().equals(new File(f.getParentFile().getCanonicalFile(), f.getName()).getPath());
            if(!link && f.isDirectory())
            {
                File[] kids = f.listFiles();
                if(kids != null)
                    for(File k : kids)
                        deleteRecursive(k);
            }
        }
        catch(IOException ignored) {}
        f.delete();
    }
}
