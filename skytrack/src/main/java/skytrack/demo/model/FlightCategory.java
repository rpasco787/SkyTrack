package skytrack.demo.model;

public enum FlightCategory {
    VFR,
    MVFR,
    IFR,
    LIFR,
    UNKNOWN;

    public static FlightCategory from(Double visibilityStatuteMiles, Integer ceilingFeet) {
        if (visibilityStatuteMiles == null || ceilingFeet == null) {
            return UNKNOWN;
        }
        FlightCategory byVis = byVisibility(visibilityStatuteMiles);
        FlightCategory byCeiling = byCeiling(ceilingFeet);
        return byVis.ordinal() > byCeiling.ordinal() ? byVis : byCeiling;
    }

    private static FlightCategory byVisibility(double vis) {
        if (vis < 1.0) return LIFR;
        if (vis < 3.0) return IFR;
        if (vis < 5.0) return MVFR;
        return VFR;
    }

    private static FlightCategory byCeiling(int ceiling) {
        if (ceiling < 500) return LIFR;
        if (ceiling < 1000) return IFR;
        if (ceiling < 3000) return MVFR;
        return VFR;
    }
}
