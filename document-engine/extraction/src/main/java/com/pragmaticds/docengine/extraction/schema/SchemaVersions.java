package com.pragmaticds.docengine.extraction.schema;

import java.util.Comparator;

/**
 * Ordering for schema version strings — {@code MAJOR.MINOR.PATCH}, compared segment by segment as
 * NUMBERS.
 *
 * <p>Extracted so there is exactly one of these. Lexical ordering is wrong in the one place it
 * matters: {@code "10.0.0" < "9.0.0"} as a string, so a second implementation would let an author
 * re-use a version already spent and silently change what an existing {@code extracted_field}
 * describes. Authoring decides admission with this, and the listing orders history with it; a copy
 * in the reader that drifted from the writer would report a history that never happened.
 *
 * <p>A missing segment reads as zero, so {@code 1.2} and {@code 1.2.0} are the same version. The
 * authoring grammar refuses anything but three segments, and this stays defined anyway rather than
 * throwing on rows a future grammar might admit.
 */
public final class SchemaVersions {

    private SchemaVersions() {}

    /** Ascending: the LAST element of a sorted list is the highest version. */
    public static final Comparator<String> ASCENDING =
            Comparator.comparing(SchemaVersions::segments, SchemaVersions::compareSegments);

    /** Descending — newest first, which is the order a version history is read in. */
    public static final Comparator<String> DESCENDING = ASCENDING.reversed();

    private static int[] segments(String version) {
        String[] parts = version.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = Integer.parseInt(parts[i]);
        }
        return numbers;
    }

    private static int compareSegments(int[] left, int[] right) {
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int compared =
                    Integer.compare(
                            i < left.length ? left[i] : 0, i < right.length ? right[i] : 0);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }
}
