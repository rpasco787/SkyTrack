package skytrack.demo.parquet;

import org.springframework.stereotype.Service;
import skytrack.demo.model.PredictedDelayEvent;

import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class HistoricalPredictionWriter {

    private final ConcurrentLinkedQueue<PredictedDelayEvent> buffer = new ConcurrentLinkedQueue<>();

    public void buffer(PredictedDelayEvent event) {
        buffer.add(event);
    }
}
