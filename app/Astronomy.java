import java.time.ZonedDateTime;

/*
 * Solar position, ported line-for-line from Terrascope's astronomy.py
 * (Meeus, accurate to ~0.01 deg). Used by DayNightLayer for the terminator.
 */
final class Astronomy {
    private Astronomy() { }

    /** {declination (rad), equation of time (minutes)} for a UTC instant. */
    static double[] solarPosition(ZonedDateTime utc) {
        double jd = julianDay(utc);
        double T = (jd - 2451545.0) / 36525.0;
        double L0 = Math.toRadians(mod360(280.46646 + T * (36000.76983 + T * 0.0003032)));
        double M = Math.toRadians(357.52911 + T * (35999.05029 - 0.0001537 * T));
        double e = 0.016708634 - T * (0.000042037 + 0.0000001267 * T);
        double C = Math.sin(M) * (1.914602 - T * (0.004817 + 0.000014 * T))
                 + Math.sin(2 * M) * (0.019993 - 0.000101 * T)
                 + Math.sin(3 * M) * 0.000289;
        double omega = Math.toRadians(125.04 - 1934.136 * T);
        double apparent = Math.toRadians(Math.toDegrees(L0) + C - 0.00569 - 0.00478 * Math.sin(omega));
        double obliquity = Math.toRadians(23 + (26 + (21.448 - T * (46.815 + T * (0.00059 - T * 0.001813))) / 60) / 60
                 + 0.00256 * Math.cos(omega));
        double declination = Math.asin(Math.sin(obliquity) * Math.sin(apparent));
        double y = Math.pow(Math.tan(obliquity / 2), 2);
        double eqTime = 4 * Math.toDegrees(
            y * Math.sin(2 * L0) - 2 * e * Math.sin(M)
            + 4 * e * y * Math.sin(M) * Math.cos(2 * L0)
            - 0.5 * y * y * Math.sin(4 * L0) - 1.25 * e * e * Math.sin(2 * M));
        return new double[]{declination, eqTime};
    }

    // Sun elevation at a point (degrees). astronomy.py sun_elevation.
    static double sunElevation(double lat, double lon, ZonedDateTime utc, double dec, double eqTime) {
        double minutes = utc.getHour() * 60 + utc.getMinute() + utc.getSecond() / 60.0;
        double solarMin = (((minutes + eqTime + 4 * lon) % 1440) + 1440) % 1440;
        double H = Math.toRadians(solarMin / 4 - 180);
        double phi = Math.toRadians(lat);
        double c = Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(H);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, c))));
    }

    static String sunPhase(double elevation) {
        if (elevation >= 0) return "Day";
        if (elevation >= -6) return "Civil twilight";
        if (elevation >= -12) return "Nautical twilight";
        if (elevation >= -18) return "Astronomical twilight";
        return "Night";
    }

    // Local solar time at a longitude, as minutes after midnight.
    static double solarMinutes(double lon, ZonedDateTime utc, double eqTime) {
        double minutes = utc.getHour() * 60 + utc.getMinute() + utc.getSecond() / 60.0;
        return (((minutes + eqTime + 4 * lon) % 1440) + 1440) % 1440;
    }

    // Subsolar point {lon, lat} where the sun is overhead.
    static double[] subsolar(ZonedDateTime utc, double dec, double eqTime) {
        double minutes = utc.getHour() * 60 + utc.getMinute() + utc.getSecond() / 60.0;
        double lon = ((-(((minutes + eqTime) / 4 - 180)) + 180) % 360 + 360) % 360 - 180;
        return new double[]{lon, Math.toDegrees(dec)};
    }

    // Sunrise/sunset as minutes after 00:00 UTC, or null for polar day/night.
    // Returns {rise, set} or null; use dayLengthMinutes for the span.
    static double[] sunriseSunset(double lat, double lon, double dec, double eqTime) {
        double phi = Math.toRadians(lat);
        double cosH = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * Math.sin(dec))
            / (Math.cos(phi) * Math.cos(dec) == 0 ? 1e-9 : Math.cos(phi) * Math.cos(dec));
        if (cosH <= -1 || cosH >= 1) return null;            // polar day / night
        double halfDay = Math.toDegrees(Math.acos(cosH));
        double noon = 720 - 4 * lon - eqTime;
        return new double[]{((noon - 4 * halfDay) % 1440 + 1440) % 1440,
                            ((noon + 4 * halfDay) % 1440 + 1440) % 1440};
    }

    static String hhmm(double minutes) {
        int m = ((int) Math.round(minutes) % 1440 + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    static String hemisphere(double v, char pos, char neg) {
        return String.format("%.1f\u00b0%c", Math.abs(v), v >= 0 ? pos : neg);
    }

    // Moon phase info. astronomy.py moon_info.
    private static final double SYNODIC = 29.530588853;
    private static final ZonedDateTime MOON_EPOCH =
        ZonedDateTime.of(2000, 1, 6, 18, 14, 0, 0, java.time.ZoneOffset.UTC);
    private static final String[] MOON_NAMES = {
        "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
        "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"};
    record Moon(String name, double illumination, double age, double toNew, double toFull) { }
    static Moon moon(ZonedDateTime utc) {
        double days = (utc.toInstant().toEpochMilli() - MOON_EPOCH.toInstant().toEpochMilli()) / 86_400_000.0;
        double age = ((days % SYNODIC) + SYNODIC) % SYNODIC;
        double frac = age / SYNODIC;
        return new Moon(MOON_NAMES[(int) (frac * 8 + 0.5) % 8],
            (1 - Math.cos(2 * Math.PI * frac)) / 2, age, SYNODIC - age,
            ((SYNODIC / 2 - age) % SYNODIC + SYNODIC) % SYNODIC);
    }

    static double julianDay(ZonedDateTime t) {
        int year = t.getYear(), month = t.getMonthValue();
        double day = t.getDayOfMonth() + (t.getHour() + t.getMinute() / 60.0 + t.getSecond() / 3600.0) / 24.0;
        if (month <= 2) { year -= 1; month += 12; }
        int a = year / 100, b = 2 - a + a / 4;
        return (long) (365.25 * (year + 4716)) + (long) (30.6001 * (month + 1)) + day + b - 1524.5;
    }

    private static double mod360(double v) { return ((v % 360) + 360) % 360; }
}
