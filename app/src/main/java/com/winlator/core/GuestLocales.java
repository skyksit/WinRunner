package com.winlator.core;

import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Makes every UTF-8 locale a game asks for (dgplayer.ini {@code envVars=LC_ALL=ko_KR.UTF-8})
 * loadable by the rootfs glibc.
 *
 * <p>The rootfs ships compiled locale data for en_US, pt_BR and ru_RU only. For any other name
 * {@code setlocale(LC_ALL, "")} fails and the process stays in the C locale, whose
 * {@code nl_langinfo(CODESET)} is {@code ANSI_X3.4-1968}. Wine's {@code init_unix_codepage()} maps
 * that to code page 20127, which drops the high bit of every byte: a UTF-8 file name such as
 * {@code 아일랜드} on disk is read as {@code l\x15\x04…}, never matches, and a new file with a
 * non-ASCII name cannot be created either (c0000034). A Korean game that names its saves after
 * its title silently loses every save. The Windows locale itself was unaffected — the Winlator Wine
 * falls back to {@code getenv("LC_ALL")} when {@code setlocale} reports "C" — so the game looked
 * Korean while its file names were broken.
 *
 * <p>Wine reads only the codeset from glibc (it carries its own locale tables in
 * {@code locale.nls}), so the fix is an alias: {@code usr/lib/locale/ko_KR.utf8 -> en_US.utf8}.
 * {@code setlocale} then succeeds, still reports {@code ko_KR.UTF-8} (so Wine keeps ko-KR / ACP 949),
 * and the codeset is UTF-8. Verified with the rootfs {@code locale charmap} on device.
 *
 * <p>Non-UTF-8 codesets (e.g. {@code ja_JP.SJIS}) are left alone: aliasing them to UTF-8 data
 * would make the name and the codeset disagree.
 */
public final class GuestLocales {
    private static final String TAG = "GuestLocales";
    /** Always present in the rootfs; the default {@link LocaleHelper} picks it. */
    static final String TEMPLATE = "en_US.utf8";
    private static final String[] CATEGORY_VARS = {
        "LC_ALL", "LC_CTYPE", "LC_MESSAGES", "LC_COLLATE", "LC_NUMERIC", "LC_TIME", "LC_MONETARY", "LANG"
    };

    private GuestLocales() {}

    /** Creates a missing alias for every locale named by the locale variables in {@code envVars}. */
    public static void ensureInstalled(File rootDir, EnvVars envVars) {
        File localeDir = new File(rootDir, "usr/lib/locale");
        File template = new File(localeDir, TEMPLATE);
        if (!new File(template, "LC_CTYPE").isFile()) {
            Log.w(TAG, "template locale missing: "+template);
            return;
        }

        for (String var : CATEGORY_VARS) {
            String value = envVars.get(var);
            if (value == null || value.isEmpty()) continue;
            String dirName = aliasDirName(value);
            if (dirName == null) continue;

            File target = new File(localeDir, dirName);
            if (new File(target, "LC_CTYPE").isFile()) continue;

            // A dangling link (rootfs reinstalled under it) or a half-made directory: replace it.
            FileUtils.delete(target);
            FileUtils.symlink(TEMPLATE, target.getPath());
            if (new File(target, "LC_CTYPE").isFile()) Log.i(TAG, "aliased "+dirName+" -> "+TEMPLATE+" for "+var+"="+value);
            else Log.w(TAG, "could not alias "+dirName);
        }
    }

    /**
     * The directory glibc loads for {@code name}, or null when no alias should be made
     * (C/POSIX, a non-UTF-8 codeset, or a name glibc would not look up on disk).
     *
     * <p>glibc tries the name as given, then with the codeset normalised (alphanumerics only,
     * lower-cased: {@code UTF-8 -> utf8}). The normalised form is the one we create. A name with no
     * codeset is looked up as is and takes its codeset from the data, which here is UTF-8.
     */
    static String aliasDirName(String name) {
        name = name.trim();
        if (name.isEmpty() || name.equals("C") || name.equals("POSIX") || name.startsWith("C.")) return null;
        if (name.indexOf('/') >= 0 || name.startsWith(".")) return null;

        String modifier = "";
        int at = name.indexOf('@');
        if (at >= 0) {
            modifier = name.substring(at);
            name = name.substring(0, at);
        }

        String base = name;
        int dot = name.indexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            String codeset = normalizeCodeset(name.substring(dot+1));
            if (!codeset.equals("utf8")) return null;
            return base.isEmpty() ? null : base+".utf8"+modifier;
        }
        return base.isEmpty() ? null : base+modifier;
    }

    private static String normalizeCodeset(String codeset) {
        StringBuilder sb = new StringBuilder();
        for (char c : codeset.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 0x80) sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
