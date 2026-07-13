package skytrack.demo.model;

import java.util.Map;

public record PredictionAccuracySummary(
        String airportIata,
        int totalPredictions,
        int backtestableCount,
        double meanAbsoluteErrorSeconds,
        Map<String, Map<String, Integer>> confusionMatrix) {}
