package skytrack.demo.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import skytrack.demo.config.PredictionProperties;

import java.util.Map;

/**
 * Supplies two different statistics of the same turnaround distribution, because two callers ask
 * two different questions.
 *
 * <p>{@link #minTurnaroundSeconds} is the physical <em>floor</em> — how fast the aircraft could
 * conceivably be turned. That is what slack should be measured against: buffer only truly runs out
 * at the floor.</p>
 *
 * <p>{@link #expectedTurnaroundSeconds} is the <em>typical</em> turnaround. That is what a
 * prediction needs, since answering "when will this aircraft be ready?" with the best case
 * produces systematic under-prediction.</p>
 *
 * <p>Both tables are keyed {@code "CARRIER|AIRPORT"} with a bare-carrier fallback, and both fall
 * back to the configured {@code minTurnaroundMinutes} when the carrier was never fitted.</p>
 */
@Service
public class TurnaroundEstimator {

    private final PredictionProperties props;
    private final Map<String, Long> minTurnarounds;
    private final Map<String, Long> expectedTurnarounds;

    /** Serves both lookups from one table, for callers with only a single fitted set. */
    public TurnaroundEstimator(PredictionProperties props, Map<String, Long> carrierTurnarounds) {
        this(props, carrierTurnarounds, carrierTurnarounds);
    }

    @Autowired
    public TurnaroundEstimator(PredictionProperties props,
                               @Qualifier("carrierTurnarounds") Map<String, Long> minTurnarounds,
                               @Qualifier("expectedTurnarounds") Map<String, Long> expectedTurnarounds) {
        this.props = props;
        this.minTurnarounds = minTurnarounds;
        this.expectedTurnarounds = expectedTurnarounds;
    }

    /**
     * Returns the minimum turnaround time in seconds for the given carrier IATA code.
     * If a carrier-specific override exists, it is used; otherwise falls back to the configured
     * default (minTurnaroundMinutes).
     */
    public long minTurnaroundSeconds(String carrierIata) {
        return minTurnaroundSeconds(carrierIata, null);
    }

    /**
     * As {@link #minTurnaroundSeconds(String)}, but prefers the figure fitted for this carrier at
     * this specific airport. How fast a carrier can turn an aircraft is as much a property of the
     * station as of the airline — gate count, ground crew and taxi distance all differ — so a hub
     * figure should not be applied to an outstation.
     *
     * <p>Backs off {@code (carrier, airport) -> (carrier) -> configured default}.</p>
     */
    public long minTurnaroundSeconds(String carrierIata, String airportIata) {
        return lookup(minTurnarounds, carrierIata, airportIata);
    }

    /**
     * The turnaround a prediction should assume: the typical time this carrier takes at this
     * station when it is under pressure, not the fastest it has ever managed.
     */
    public long expectedTurnaroundSeconds(String carrierIata, String airportIata) {
        return lookup(expectedTurnarounds, carrierIata, airportIata);
    }

    private long lookup(Map<String, Long> table, String carrierIata, String airportIata) {
        if (carrierIata != null) {
            if (airportIata != null) {
                Long atAirport = table.get(BtsScheduleRepository.turnaroundKey(carrierIata, airportIata));
                if (atAirport != null) return atAirport;
            }
            Long forCarrier = table.get(carrierIata);
            if (forCarrier != null) return forCarrier;
        }
        return props.minTurnaroundMinutes() * 60L;
    }
}
