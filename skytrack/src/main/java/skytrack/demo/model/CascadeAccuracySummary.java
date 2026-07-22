package skytrack.demo.model;

public record CascadeAccuracySummary(
        String airportIata,
        int totalChains,
        int totalHops,
        int backtestableHops,
        double hopLevelMaeSeconds,
        double avgChainLength,
        int truePositives,
        int falsePositives,
        double precision) {}
