import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/*
 * A shared, scrubbable clock. offsetMinutes = 0 is "now"; the Astro time slider
 * shifts it so the day/night terminator (and the SUN/MOON panel) move through
 * time. Read by DayNightLayer and the Astro panel.
 */
final class Clock {
    private Clock() { }
    static int offsetMinutes = 0;                 // + = future, - = past

    static ZonedDateTime nowUtc() {
        return ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(offsetMinutes);
    }

    static String label() {
        if (offsetMinutes == 0) return "NOW";
        int h = Math.abs(offsetMinutes) / 60, m = Math.abs(offsetMinutes) % 60;
        return (offsetMinutes < 0 ? "-" : "+") + (h > 0 ? h + "h" : "") + m + "m";
    }
}
