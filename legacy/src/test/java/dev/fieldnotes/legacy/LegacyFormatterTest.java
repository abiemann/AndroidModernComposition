package dev.fieldnotes.legacy;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.assertEquals;

public class LegacyFormatterTest {
    @Test public void countsRespectLocale() {
        assertEquals("1,234", LegacyFormatter.count(1234, Locale.US));
        assertEquals("1.234", LegacyFormatter.count(1234, Locale.GERMANY));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsNegativeCounts() { LegacyFormatter.count(-1); }
}
