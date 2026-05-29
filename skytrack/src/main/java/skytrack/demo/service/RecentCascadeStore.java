package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.CascadeAlert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RecentCascadeStore {

    private static final int MAX_PER_AIRPORT = 50;

    private final Map<String, Deque<CascadeAlert>> byAirport = new ConcurrentHashMap<>();

    public void add(CascadeAlert alert) {
        if (alert == null || alert.arrivalAirportIata() == null) {
            return;
        }
        Deque<CascadeAlert> deque = byAirport.computeIfAbsent(
                alert.arrivalAirportIata(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(alert);
            while (deque.size() > MAX_PER_AIRPORT) {
                deque.removeLast();
            }
        }
    }

    public List<CascadeAlert> getRecent(String airportIata) {
        if (airportIata == null) {
            return List.of();
        }
        Deque<CascadeAlert> deque = byAirport.get(airportIata);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
