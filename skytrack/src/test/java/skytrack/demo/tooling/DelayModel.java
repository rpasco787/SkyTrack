package skytrack.demo.tooling;

import java.util.random.RandomGenerator;

/** Deterministic synthetic delay sampling. Bands are cumulative over a uniform r in [0,1). */
public final class DelayModel {

    public enum Band { ON_TIME, MINOR, MAJOR, SEVERE }

    private DelayModel() {}

    /** Normal: 55/25/12/8. Hotspot: 25/25/30/20 (skewed toward Major/Severe). */
    public static Band chooseBand(double r, boolean hotspot) {
        if (hotspot) {
            if (r < 0.25) return Band.ON_TIME;
            if (r < 0.50) return Band.MINOR;
            if (r < 0.80) return Band.MAJOR;
            return Band.SEVERE;
        }
        if (r < 0.55) return Band.ON_TIME;
        if (r < 0.80) return Band.MINOR;
        if (r < 0.92) return Band.MAJOR;
        return Band.SEVERE;
    }

    /** Uniform delay (seconds) within the band. ON_TIME spans -5..+14 min. */
    public static long sampleDelaySeconds(Band band, RandomGenerator rng) {
        int lo, hi; // minutes, inclusive
        switch (band) {
            case ON_TIME -> { lo = -5;  hi = 14;  }
            case MINOR   -> { lo = 15;  hi = 44;  }
            case MAJOR   -> { lo = 45;  hi = 119; }
            case SEVERE  -> { lo = 120; hi = 240; }
            default -> throw new IllegalStateException();
        }
        int minutes = lo + rng.nextInt(hi - lo + 1);
        return minutes * 60L;
    }
}
