package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GuestLocalesTest {
    /** The name every Korean dgplayer.ini uses; glibc loads it from the normalised directory. */
    @Test
    public void utf8LocaleMapsToNormalisedDirectory() {
        assertEquals("ko_KR.utf8", GuestLocales.aliasDirName("ko_KR.UTF-8"));
        assertEquals("ja_JP.utf8", GuestLocales.aliasDirName("ja_JP.utf8"));
        assertEquals("zh_TW.utf8", GuestLocales.aliasDirName(" zh_TW.Utf-8 "));
    }

    @Test
    public void modifierIsKept() {
        assertEquals("sr_RS.utf8@latin", GuestLocales.aliasDirName("sr_RS.UTF-8@latin"));
    }

    @Test
    public void nameWithoutCodesetIsLookedUpAsIs() {
        assertEquals("ko_KR", GuestLocales.aliasDirName("ko_KR"));
    }

    /** Aliasing to UTF-8 data would make the name and the codeset disagree. */
    @Test
    public void nonUtf8CodesetIsLeftAlone() {
        assertNull(GuestLocales.aliasDirName("ja_JP.SJIS"));
        assertNull(GuestLocales.aliasDirName("ko_KR.EUC-KR"));
    }

    @Test
    public void builtinAndUnsafeNamesAreIgnored() {
        assertNull(GuestLocales.aliasDirName("C"));
        assertNull(GuestLocales.aliasDirName("C.UTF-8"));
        assertNull(GuestLocales.aliasDirName("POSIX"));
        assertNull(GuestLocales.aliasDirName(""));
        assertNull(GuestLocales.aliasDirName("../../etc"));
        assertNull(GuestLocales.aliasDirName(".utf8"));
    }
}
