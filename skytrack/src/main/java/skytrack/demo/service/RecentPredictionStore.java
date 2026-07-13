package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.PredictedDelayEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RecentPredictionStore {

    private static final int MAX_PER_AIRPORT = 50;

    private final Map<String, Deque<PredictedDelayEvent>> byAirport = new ConcurrentHashMap<>();

    public void add(PredictedDelayEvent event) {
        if (event == null || event.departureAirportIata() == null) return;
        Deque<PredictedDelayEvent> deque = byAirport.computeIfAbsent(
                event.departureAirportIata(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(event);
            while (deque.size() > MAX_PER_AIRPORT) deque.removeLast();
        }
    }

    public List<PredictedDelayEvent> getRecent(String airportIata) {
        if (airportIata == null) return List.of();
        Deque<PredictedDelayEvent> deque = byAirport.get(airportIata);
        if (deque == null) return List.of();
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
