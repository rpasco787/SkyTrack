package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.CascadeChain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RecentCascadeStore {

    private static final int MAX_PER_AIRPORT = 50;

    private final Map<String, Deque<CascadeChain>> byAirport = new ConcurrentHashMap<>();

    public void add(CascadeChain chain) {
        if (chain == null || chain.originAirportIata() == null) {
            return;
        }
        Deque<CascadeChain> deque = byAirport.computeIfAbsent(
                chain.originAirportIata(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(chain);
            while (deque.size() > MAX_PER_AIRPORT) {
                deque.removeLast();
            }
        }
    }

    public List<CascadeChain> getRecent(String airportIata) {
        if (airportIata == null) {
            return List.of();
        }
        Deque<CascadeChain> deque = byAirport.get(airportIata);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
