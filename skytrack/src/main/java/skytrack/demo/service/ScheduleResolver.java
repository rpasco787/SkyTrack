package skytrack.demo.service;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.metrics.PipelineMetrics;
import skytrack.demo.model.FlightSchedule;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class ScheduleResolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleResolver.class);

    private final FlightScheduleApiClient apiClient;
    private final CallsignParser callsignParser;
    private final RouteAverageEstimator routeAverageEstimator;
    private final PipelineMetrics metrics;

    public ScheduleResolver(FlightScheduleApiClient apiClient,
                            CallsignParser callsignParser,
                            RouteAverageEstimator routeAverageEstimator,
                            PipelineMetrics metrics) {
        this.apiClient = apiClient;
        this.callsignParser = callsignParser;
        this.routeAverageEstimator = routeAverageEstimator;
        this.metrics = metrics;
    }

    public ResolvedArrival resolve(LandingEvent event) {
        var parsed = callsignParser.parse(event.callsign());
        if (parsed.isEmpty()) {
            log.info("Could not parse callsign '{}' for icao24={}", event.callsign(), event.icao24());
            return unresolved(event);
        }

        var callsign = parsed.get();
        String date = LocalDate.ofInstant(
                Instant.ofEpochSecond(event.arrivalTime()), ZoneOffset.UTC).toString();

        // Try AeroAPI
        try {
            var schedule = timedLookup(event.callsign(), date);
            if (schedule.isPresent()) {
                var sched = schedule.get();
                Long scheduledArrival = sched.scheduledArrival() != null
                        ? sched.scheduledArrival().getEpochSecond() : null;
                Long delay = scheduledArrival != null
                        ? event.arrivalTime() - scheduledArrival : null;

                routeAverageEstimator.record(sched);

                log.info("Resolved {} via AeroAPI: delay={}s at {}",
                        event.callsign(), delay, event.arrivalAirportIata());
                return new ResolvedArrival(
                        event.icao24(), event.callsign(),
                        callsign.iataCarrierCode(), callsign.flightNumber(),
                        event.arrivalAirportIcao(), event.arrivalAirportIata(),
                        event.arrivalTime(), scheduledArrival, delay, "AEROAPI");
            }
        } catch (Exception e) {
            log.warn("AeroAPI lookup failed for {}: {}", event.callsign(), e.getMessage());
        }

        // Try route average
        var avgDelay = routeAverageEstimator.estimateDelaySeconds(
                callsign.icaoCarrierCode(), event.arrivalAirportIata());
        if (avgDelay.isPresent()) {
            long estimated = (long) avgDelay.getAsDouble();
            log.info("Resolved {} via route average: estimated delay={}s at {}",
                    event.callsign(), estimated, event.arrivalAirportIata());
            return new ResolvedArrival(
                    event.icao24(), event.callsign(),
                    callsign.iataCarrierCode(), callsign.flightNumber(),
                    event.arrivalAirportIcao(), event.arrivalAirportIata(),
                    event.arrivalTime(), null, estimated, "ROUTE_AVERAGE");
        }

        log.info("Could not resolve schedule for {} at {}",
                event.callsign(), event.arrivalAirportIata());
        return unresolved(event);
    }

    /**
     * The one network call on the landing path, timed with an outcome tag. Exceptions propagate
     * so the caller's existing catch/fallback is unchanged; they are recorded as "error" first.
     */
    private Optional<FlightSchedule> timedLookup(String callsign, String date) {
        Timer.Sample sample = metrics.startTimer();
        try {
            Optional<FlightSchedule> schedule = apiClient.getFlightSchedule(callsign, date);
            metrics.recordScheduleResolution(sample,
                    schedule.isPresent() ? PipelineMetrics.OUTCOME_RESOLVED : PipelineMetrics.OUTCOME_EMPTY);
            return schedule;
        } catch (RuntimeException e) {
            metrics.recordScheduleResolution(sample, PipelineMetrics.OUTCOME_ERROR);
            throw e;
        }
    }

    private ResolvedArrival unresolved(LandingEvent event) {
        return new ResolvedArrival(
                event.icao24(), event.callsign(),
                null, null,
                event.arrivalAirportIcao(), event.arrivalAirportIata(),
                event.arrivalTime(), null, null, "UNRESOLVED");
    }
}
