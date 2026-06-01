package com.example.ibmmq.report;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parses an IBM MQ MQMD {@code PutDate} + {@code PutTime} pair into a {@link LocalDateTime} that holds the
 * UTC wall-clock — with NO dependency on the JVM default time zone (issue #19, AC3).
 *
 * <p><b>Source format (bytecode/IBM-doc verified — see
 * {@code research-output/phase-f-mqmd-field-recovery.md}):</b></p>
 * <ul>
 *   <li>{@code PutDate} = {@code "YYYYMMDD"} (8 chars), e.g. {@code "20260531"}.</li>
 *   <li>{@code PutTime} = {@code "HHMMSSTH"} (8 chars), where the last two digits are HUNDREDTHS of a
 *       second, e.g. {@code "13300050"} = 13:30:00.50 (500 ms).</li>
 *   <li>Both fields are <b>GMT/UTC</b> as emitted by the queue manager.</li>
 * </ul>
 *
 * <p><b>Why no JVM-default-zone leakage:</b> we parse the concatenated {@code YYYYMMDDHHmmssSS} string with
 * a zone-free {@link DateTimeFormatter} into a {@link LocalDateTime}. A {@code LocalDateTime} carries no
 * zone, so no implicit {@code ZoneId.systemDefault()} is ever consulted — the value is the UTC wall-clock
 * verbatim. Callers that need an {@link java.time.Instant} use {@link #toInstantUtc(LocalDateTime)}, which
 * applies an EXPLICIT {@link ZoneOffset#UTC}. This keeps the conversion replica-clock-independent at scale
 * (no per-pod timezone skew in end-to-end latency math).</p>
 *
 * <p><b>Null/blank safety:</b> {@link #parse(String, String)} returns {@code null} when either input is
 * {@code null}/blank or malformed, and NEVER throws. This matters because the report consumer calls the
 * MQMD extractor on every report — including reports whose {@code PutDate}/{@code PutTime} were not
 * read-enabled (unit tests with bare Mockito mocks return {@code null} for those properties). A throw here
 * would abort the already-acked report path.</p>
 */
public final class MqmdTimestamps {

    // Zone-free pattern: YYYY MM DD HH mm ss + 2 digits of hundredths (mapped to SS = fraction-of-second,
    // 2 digits). No zone field => the formatter never consults ZoneId.systemDefault().
    private static final DateTimeFormatter PUT_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSS");

    private MqmdTimestamps() {
        // Utility class — no instances.
    }

    /**
     * Parses an MQMD {@code PutDate} ({@code YYYYMMDD}) + {@code PutTime} ({@code HHMMSSTH}) pair into a
     * UTC-wall-clock {@link LocalDateTime}.
     *
     * @param putDate the MQMD {@code PutDate} (8 chars {@code YYYYMMDD}); {@code null}/blank tolerated.
     * @param putTime the MQMD {@code PutTime} (8 chars {@code HHMMSSTH}, last two = hundredths); {@code null}/
     *                blank tolerated.
     * @return the UTC wall-clock as a {@link LocalDateTime}, or {@code null} when either input is
     *         {@code null}/blank or cannot be parsed. Never throws.
     */
    public static LocalDateTime parse(String putDate, String putTime) {
        if (putDate == null || putTime == null) {
            return null;
        }
        String date = putDate.trim();
        String time = putTime.trim();
        if (date.isEmpty() || time.isEmpty()) {
            return null;
        }
        // Defensive: the QMgr emits exactly 8+8 chars; reject anything else rather than misparse.
        if (date.length() != 8 || time.length() != 8) {
            return null;
        }
        try {
            return LocalDateTime.parse(date + time, PUT_DATE_TIME);
        } catch (DateTimeParseException e) {
            // Malformed (e.g. non-numeric) — treat as "no timestamp" rather than aborting the report path.
            return null;
        }
    }

    /**
     * Converts a UTC-wall-clock {@link LocalDateTime} (as produced by {@link #parse}) into an
     * {@link java.time.Instant} using an EXPLICIT {@link ZoneOffset#UTC} — never the JVM default zone.
     *
     * @param utcWallClock a UTC wall-clock {@link LocalDateTime}; {@code null} tolerated.
     * @return the corresponding {@link java.time.Instant}, or {@code null} when the input is {@code null}.
     */
    public static java.time.Instant toInstantUtc(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
