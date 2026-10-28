package dev.fieldnotes.legacy;

import java.text.NumberFormat;
import java.util.Locale;

/** Pure Java code remains usable by Kotlin and independently testable. */
public final class LegacyFormatter {
    private LegacyFormatter() {}
    public static String count(int count) { return count(count, Locale.getDefault()); }
    public static String count(int count, Locale locale) {
        if (count < 0) throw new IllegalArgumentException("Count cannot be negative");
        return NumberFormat.getIntegerInstance(locale).format(count);
    }
}
