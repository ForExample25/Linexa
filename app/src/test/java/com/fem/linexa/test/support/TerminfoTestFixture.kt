package com.fem.linexa.test.support

import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.util.Base64

/**
 * §1.75 test fixture — real ncurses terminfo bytes for the host-side tmux
 * fix.
 *
 * The `x/xterm-256color` bytes are the actual artifact generated on this
 * host (`infocmp -x xterm-256color | tic -x`, ncurses extended section,
 * magic 0x1E 0x02 — generated 2026-08-06) and base64-pinned here, so the
 * JVM tests exercise the REAL vendored artifact shape and a regression
 * catches any accidental change to the key entry the launch env's
 * TERM=xterm-256color needs. The remaining three entries are minimal
 * magic-valid stubs — the installer validates magic + non-empty, not full
 * entry content, so the stub is structurally sufficient and keeps the
 * fixture small.
 */
object TerminfoTestFixture {

    /** Valid entries for every [BootstrapManager.TERMINFO_ENTRIES] key. */
    val validEntries: Map<String, ByteArray> by lazy {
        BootstrapManager.TERMINFO_ENTRIES.associateWith { entry ->
            if (entry == "x/xterm-256color") realXterm256color else magicValidStub
        }
    }

    /** Base64-pinned real `xterm-256color` ncurses binary (magic 1E 02). */
    val realXterm256color: ByteArray by lazy { Base64.getDecoder().decode(REAL_XTERM256_B64) }

    private val magicValidStub: ByteArray = byteArrayOf(0x1E, 0x02, 0x00, 0x01, 0x02, 0x03)

    private const val REAL_XTERM256_B64 =
        "HgIlACYADwCdAVoGeHRlcm0tMjU2Y29sb3J8eHRlcm0gd2l0aCAyNTYgY29sb3JzAAABAAABAAAAAQAAAAABAQAAAAAAAAABAAABAAEBAAAAAAAAAAABAFAAAAAIAAAAGAAAAP////////////////////////////////////////////////////8AAQAAAAABAAAABAAGAAgAGQAeACYAKgAuAP//OQBKAEwAUABXAP//WQBmAP//agBuAHgAfAD/////gACEAIkAjgD//6AApQCqAP//rwC0ALkAvgDHAMsA0gD//+QA6QDvAPUA////////BwH///////8ZAf//HQH///////8fAf//JAH//////////ygBLAEyATYBOgE+AUQBSgFQAVYBXAFgAf//ZQH//2kBbgFzAXcBfgH//4UBiQGRAf////////////////////////////+ZAaIBqwH//64BtwHAAckB0gHbAeQB7QH2Af8B////////CAIMAhECFgIqAjMC/////0UCSAJTAlYCWAJbArgC//+7Av//////////vQLBAsUCyQLNAv/////RAv//BgP/////CgMQA/////////////8WA/////////////8aAx4D//////////////////////////////////////////////////////////////////8iA/////8pA///////////MAM3Az4D/////0UD//9MA////////1MD/////////////1oDYANmA20DdAN7A4IDigOSA5oDogOqA7IDugPCA8kD0APXA94D5gPuA/YD/gMGBA4EFgQeBCUELAQzBDoEQgRKBFIEWgRiBGoEcgR6BIEEiASPBJYEngSmBK4EtgS+BMYEzgTWBN0E5ATrBPAE///////////////////////////////////////////////////////////3BAIFBwUaBR4FJwUuBf////////////////////////////+MBf///////////////////////5EF/////////////////////////////////////////////////////5cFqAX/////////////////////////////ugX///////++Bf0F//////////////////89Bv///////////////////////////////////////////////////////////////////////////////////////////////////////////////1QGVwYbW1oABwANABtbJWklcDElZDslcDIlZHIAG1szZwAbW0gbWzJKABtbSwAbW0oAG1slaSVwMSVkRwAbWyVpJXAxJWQ7JXAyJWRIAAoAG1tIABtbPzI1bAAIABtbPzEybBtbPzI1aAAbW0MAG1tBABtbPzEyOzI1aAAbW1AAG1tNABsoMAAbWzVtABtbMW0AG1s/MTA0OWgbWzIyOzA7MHQAG1sybQAbWzRoABtbOG0AG1s3bQAbWzdtABtbNG0AG1slcDElZFgAGyhCABsoQhtbbQAbWz8xMDQ5bBtbMjM7MDswdAAbWzRsABtbMjdtABtbMjRtABtbPzVoJDwxMDAvPhtbPzVsABtbIXAbWz8zOzRsG1s0bBs+ABtbTAB/ABtbM34AG09CABtPUAAbWzIxfgAbT1EAG09SABtPUwAbWzE1fgAbWzE3fgAbWzE4fgAbWzE5fgAbWzIwfgAbT0gAG1syfgAbT0QAG1s2fgAbWzV+ABtPQwAbWzE7MkIAG1sxOzJBABtPQQAbWz8xbBs+ABtbPzFoGz0AG1s/MTAzNGwAG1s/MTAzNGgAG0UAG1slcDElZFAAG1slcDElZE0AG1slcDElZEIAG1slcDElZEAAG1slcDElZFMAG1slcDElZEwAG1slcDElZEQAG1slcDElZEMAG1slcDElZFQAG1slcDElZEEAG1tpABtbNGkAG1s1aQAlcDElYxtbJXAyJXsxfSUtJWRiABtjG10xMDQHABtbIXAbWz8zOzRsG1s0bBs+ABs4ABtbJWklcDElZGQAGzcACgAbTQAlPyVwOSV0GygwJWUbKEIlOxtbMCU/JXA2JXQ7MSU7JT8lcDUldDsyJTslPyVwMiV0OzQlOyU/JXAxJXAzJXwldDs3JTslPyVwNCV0OzUlOyU/JXA3JXQ7OCU7bQAbSAAJABtPdwAbT3kAG091ABtPcQAbT3MAYGBhYWZmZ2dpaWpqa2tsbG1tbm5vb3BwcXFycnNzdHR1dXZ2d3d4eHl5enp7e3x8fX1+fgAbW1oAG1s/N2gAG1s/N2wAG09FABtPRgAbT00AG1szOzJ+ABtbMTsyRgAbWzE7MkgAG1sy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dE0lZW0lOwAbUD5cfFhUZXJtXChbMS05XVswLTldK1wpG1xcAEFYAFhGAFhUAEJEAEJFAENyAENzAEUzAE1zAFBFAFBTAFJWAFNlAFNzAFhNAFhSAGZkAGZlAGtEQzMAa0RDNABrREM1AGtEQzYAa0RDNwBrRE4Aa0ROMwBrRE40AGtETjUAa0RONgBrRE43AGtFTkQzAGtFTkQ0AGtFTkQ1AGtFTkQ2AGtFTkQ3AGtIT00zAGtIT000AGtIT001AGtIT002AGtIT003AGtJQzMAa0lDNABrSUM1AGtJQzYAa0lDNwBrTEZUMwBrTEZUNABrTEZUNQBrTEZUNgBrTEZUNwBrTlhUMwBrTlhUNABrTlhUNQBrTlhUNgBrTlhUNwBrUFJWMwBrUFJWNABrUFJWNQBrUFJWNgBrUFJWNwBrUklUMwBrUklUNABrUklUNQBrUklUNgBrUklUNwBrVVAAa1VQMwBrVVA0AGtVUDUAa1VQNgBrVVA3AGthMgBrYjEAa2IzAGtjMgBrcDUAa3BBREQAa3BDTUEAa3BESVYAa3BET1QAa3BNVUwAa3BTVUIAa3BaUk8Aa3hJTgBreE9VVABybXh4AHJ2AHNteHgAeG0AeHIA"
}
