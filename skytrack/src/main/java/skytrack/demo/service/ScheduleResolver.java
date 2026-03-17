package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class ScheduleResolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleResolver.class);

    private final FlightScheduleApiClient apiClient;
    private final CallsignParser callsignParser;
    private final RouteAverageEstimator routeAverageEstimator;

    public ScheduleResolver(FlightScheduleApiClient apiClient,
                            CallsignParser callsignParser,
                            RouteAverageEstimator routeAverageEstimator) {
        this.apiClient = apiClient;
        this.callsignParser = callsignParser;
        this.routeAverageEstimator = routeAverageEstimator;
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
            var schedule = apiClient.getFlightSchedule(event.callsign(), date);
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

    private ResolvedArrival unresolved(LandingEvent event) {
        return new ResolvedArrival(
                event.icao24(), event.callsign(),
                null, null,
                event.arrivalAirportIcao(), event.arrivalAirportIata(),
                event.arrivalTime(), null, null, "UNRESOLVED");
    }
}
