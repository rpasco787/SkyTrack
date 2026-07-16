package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.CascadeHop;
import skytrack.demo.model.ResolvedArrival;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Multi-hop cascade detector. On a qualifying late arrival, walks the aircraft's BTS tail
 * rotation and propagates delay leg-by-leg in delay-space:
 *
 *   depDelay[n]     = max(0, carriedArrivalDelay − scheduledSlack[n])
 *   arrivalDelay[n] = depDelay[n] × (1 − enRouteRecovery)
 *
 * The chain terminates when a hop's predicted departure delay falls below the delay threshold
 * (slack absorbed it), the rotation ends, a leg is cancelled, or maxHops is reached.
 */
@Service
@EnableConfigurationProperties(DisruptionScoreProperties.class)
public class CascadeChainDetector {

    private static final Logger log = LoggerFactory.getLogger(CascadeChainDetector.class);

    private final CallsignParser callsignParser;
    private final BtsScheduleRepository repo;
    private final TurnaroundEstimator turnaroundEstimator;
    private final DisruptionScoreProperties props;
    private final Clock clock;

    public CascadeChainDetector(CallsignParser callsignParser,
                                BtsScheduleRepository repo,
                                TurnaroundEstimator turnaroundEstimator,
                                DisruptionScoreProperties props,
                                Clock clock) {
        this.callsignParser = callsignParser;
        this.repo = repo;
        this.turnaroundEstimator = turnaroundEstimator;
        this.props = props;
        this.clock = clock;
    }

    public Optional<CascadeChain> detect(ResolvedArrival arrival) {
        if (arrival.delaySeconds() == null) return Optional.empty();
        long arrivalDelay = arrival.delaySeconds();
        if (arrivalDelay < props.cascadeThresholdMinutes() * 60L) return Optional.empty();

        var parsed = callsignParser.parse(arrival.callsign());
        if (parsed.isEmpty()) return Optional.empty();

        Optional<BtsFlightRecord> inbound = repo.findInboundLeg(
                parsed.get().iataCarrierCode(), parsed.get().flightNumber(),
                arrival.arrivalAirportIata(), arrival.actualArrivalTime());
        if (inbound.isEmpty()) return Optional.empty();

        BtsFlightRecord in = inbound.get();
        String tail = in.tailNumber();
        if (tail == null || tail.isBlank() || in.scheduledArrEpoch() == null) return Optional.empty();

        long minTurnaround = turnaroundEstimator.minTurnaroundSeconds(null);
        long thresholdSeconds = props.delayThresholdMinutes() * 60L;
        double recovery = props.enRouteRecoveryFactor();

        List<CascadeHop> hops = new ArrayList<>();
        long carried = arrivalDelay;
        long prevSchedArr = in.scheduledArrEpoch();
        String from = arrival.arrivalAirportIata();
        long walkAfter = in.scheduledArrEpoch();

        for (int hop = 0; hop < props.cascadeMaxHops(); hop++) {
            Optional<BtsFlightRecord> nextOpt = repo.findNextDeparture(tail, from, walkAfter);
            if (nextOpt.isEmpty()) break;
            BtsFlightRecord next = nextOpt.get();
            if (next.cancelled() || next.scheduledArrEpoch() == null) break;

            long slack = next.scheduledDepEpoch() - prevSchedArr - minTurnaround;
            long depDelay = Math.max(0, carried - slack);
            if (depDelay < thresholdSeconds) break;

            hops.add(new CascadeHop(
                    next.carrierIata(), next.flightNumber(), tail,
                    next.origin(), next.dest(), next.scheduledDepEpoch(),
                    depDelay, next.actualDepDelaySeconds()));

            carried = Math.round(depDelay * (1 - recovery));
            prevSchedArr = next.scheduledArrEpoch();
            from = next.dest();
            walkAfter = next.scheduledArrEpoch();
        }

        if (hops.isEmpty()) return Optional.empty();

        CascadeChain chain = CascadeChain.of(
                arrival.callsign(), arrival.arrivalAirportIata(), arrivalDelay, hops, clock.instant());

        log.info("Cascade chain: {} at {} arrDelay={}min -> {} downstream legs, total predicted={}min",
                arrival.callsign(), arrival.arrivalAirportIata(), arrivalDelay / 60,
                chain.flightsAffected(), chain.totalPredictedDelaySeconds() / 60);

        return Optional.of(chain);
    }
}
