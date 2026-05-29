package skytrack.demo.model;

public record ScheduleCoverage(
        long total,
        long verified,
        long estimated,
        long unresolved,
        double verifiedRate
) {}
