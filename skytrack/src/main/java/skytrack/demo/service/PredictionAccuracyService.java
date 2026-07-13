package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.PredictionAccuracySummary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PredictionAccuracyService {

    public PredictionAccuracySummary summarize(String airportIata, List<PredictedDelayEvent> events) {
        List<PredictedDelayEvent> backtestable = events.stream()
                .filter(e -> e.actualDelaySeconds() != null)
                .toList();

        double mae = 0.0;
        Map<String, Map<String, Integer>> matrix = new HashMap<>();

        if (!backtestable.isEmpty()) {
            double totalError = 0.0;
            for (PredictedDelayEvent e : backtestable) {
                totalError += Math.abs(e.predictedDelaySeconds() - e.actualDelaySeconds());
                String predicted = e.predictedClassification().name();
                String actual = DelayClassification.fromDelaySeconds(e.actualDelaySeconds()).name();
                matrix.computeIfAbsent(predicted, k -> new HashMap<>())
                        .merge(actual, 1, Integer::sum);
            }
            mae = totalError / backtestable.size();
        }

        return new PredictionAccuracySummary(
                airportIata,
                events.size(),
                backtestable.size(),
                mae,
                matrix);
    }
}
