package skytrack.demo.model;

public enum DelayClassification {
    ON_TIME,
    MINOR,
    MODERATE,
    MAJOR,
    SEVERE,
    UNKNOWN;

    public static DelayClassification fromDelaySeconds(Long delaySeconds) {
        if (delaySeconds == null) return UNKNOWN;
        long minutes = delaySeconds / 60;
        if (minutes <= 0) return ON_TIME;
        if (minutes <= 15) return MINOR;
        if (minutes <= 45) return MODERATE;
        if (minutes <= 120) return MAJOR;
        return SEVERE;
    }

    public boolean isDelayed() {
        return this == MODERATE || this == MAJOR || this == SEVERE;
    }
}
