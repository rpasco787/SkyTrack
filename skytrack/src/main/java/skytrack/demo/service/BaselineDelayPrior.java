package skytrack.demo.service;

import skytrack.demo.model.BtsFlightRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The habitual, non-cascade component of departure delay: what a given carrier's flights out of a
 * given airport at a given hour of day do on an ordinary day, before any inbound aircraft is late.
 *
 * <p>This exists because {@code DelayPredictor} on its own is a feasibility check — it only emits a
 * non-zero prediction when the turnaround is physically impossible, so every flight with adequate
 * slack is predicted zero. Aircraft do not depart at the earliest feasible moment; they depart late
 * for crew, boarding, gate and ATC reasons no turnaround model can express. This prior carries that
 * term, and composes additively with the turnaround pressure term.</p>
 *
 * <p>Fitted only on legs where {@code LATE_AIRCRAFT_DELAY} is absent or zero. BTS decomposes delay
 * into carrier / NAS / weather / security / late-aircraft causes, and late-aircraft is precisely
 * what the turnaround term already models — fitting the prior on those legs too would double-count
 * it. Note the carve is imperfect in one direction: BTS only populates the cause columns when
 * arrival delay reaches 15 minutes, so a leg that pushed back late on a late inbound but made the
 * time up en route reports no cause at all and is kept. That biases the prior slightly high rather
 * than leaving a hole in it.</p>
 *
 * <p>Medians, not means. The constant minimising absolute error is the conditional median, MAE is
 * the headline metric, and medians shrug off the multi-hour outliers in the tail. Values stay
 * signed: most flights push back a few minutes early, and clamping that away would bias every
 * prediction upward.</p>
 */
public final class BaselineDelayPrior {

    /**
     * A bucket must hold at least this many legs before it is trusted over a coarser one. Thin
     * buckets are dominated by the noise of a handful of flights.
     */
    public static final int MIN_SAMPLES = 30;

    private final AirportTimeZoneResolver timeZones;
    private final Map<String, Long> byCarrierOriginHour;
    private final Map<String, Long> byCarrierOrigin;
    private final Map<String, Long> byOriginHour;
    private final Map<String, Long> byCarrier;
    private final long global;

    private BaselineDelayPrior(AirportTimeZoneResolver timeZones,
                               Map<String, Long> byCarrierOriginHour,
                               Map<String, Long> byCarrierOrigin,
                               Map<String, Long> byOriginHour,
                               Map<String, Long> byCarrier,
                               long global) {
        this.timeZones = timeZones;
        this.byCarrierOriginHour = byCarrierOriginHour;
        this.byCarrierOrigin = byCarrierOrigin;
        this.byOriginHour = byOriginHour;
        this.byCarrier = byCarrier;
        this.global = global;
    }

    /**
     * Fits the prior over every leg in {@code repo}. Callers are responsible for handing in a
     * repository restricted to the training window — fitting on the day being evaluated leaks the
     * answer into the prediction.
     */
    public static BaselineDelayPrior from(BtsScheduleRepository repo, AirportTimeZoneResolver tz) {
        Map<String, List<Long>> carrierOriginHour = new HashMap<>();
        Map<String, List<Long>> carrierOrigin = new HashMap<>();
        Map<String, List<Long>> originHour = new HashMap<>();
        Map<String, List<Long>> carrier = new HashMap<>();
        List<Long> all = new ArrayList<>();

        for (BtsFlightRecord r : repo.all()) {
            if (r.cancelled()) continue;
            Long depDelay = r.actualDepDelaySeconds();
            if (depDelay == null) continue;
            Long lateAircraft = r.lateAircraftDelaySeconds();
            if (lateAircraft != null && lateAircraft > 0) continue;

            Integer hour = localHour(tz, r.origin(), r.scheduledDepEpoch());
            if (hour != null) {
                carrierOriginHour.computeIfAbsent(
                        key(r.carrierIata(), r.origin(), hour), k -> new ArrayList<>()).add(depDelay);
                originHour.computeIfAbsent(
                        key(r.origin(), hour), k -> new ArrayList<>()).add(depDelay);
            }
            carrierOrigin.computeIfAbsent(
                    key(r.carrierIata(), r.origin()), k -> new ArrayList<>()).add(depDelay);
            carrier.computeIfAbsent(r.carrierIata(), k -> new ArrayList<>()).add(depDelay);
            all.add(depDelay);
        }

        return new BaselineDelayPrior(tz,
                mediansOfDenseBuckets(carrierOriginHour),
                mediansOfDenseBuckets(carrierOrigin),
                mediansOfDenseBuckets(originHour),
                mediansOfDenseBuckets(carrier),
                all.isEmpty() ? 0L : median(all));
    }

    /**
     * Expected departure delay in seconds for a leg leaving {@code originIata} on
     * {@code carrierIata} at {@code scheduledDepEpoch}, before any turnaround pressure.
     *
     * <p>Backs off through {@code (carrier, origin, hour) -> (carrier, origin) -> (origin, hour)
     * -> (carrier) -> global}, taking the first level with enough samples. Carrier-at-airport
     * habits are the strongest signal, so they outrank the airport-wide hourly pattern; the
     * carrier-only level catches airports the training window barely saw.</p>
     */
    public long priorSeconds(String carrierIata, String originIata, long scheduledDepEpoch) {
        Integer hour = localHour(timeZones, originIata, scheduledDepEpoch);
        if (hour != null) {
            Long exact = byCarrierOriginHour.get(key(carrierIata, originIata, hour));
            if (exact != null) return exact;
        }
        Long carrierAtOrigin = byCarrierOrigin.get(key(carrierIata, originIata));
        if (carrierAtOrigin != null) return carrierAtOrigin;
        if (hour != null) {
            Long atOriginHour = byOriginHour.get(key(originIata, hour));
            if (atOriginHour != null) return atOriginHour;
        }
        Long forCarrier = byCarrier.get(carrierIata);
        if (forCarrier != null) return forCarrier;
        return global;
    }

    /**
     * Hour of day at the origin airport. Every hour-keyed bucket also fixes the origin, so this
     * never mixes time zones — but fitting and lookup must agree on the convention, which is why
     * both go through here rather than letting callers pass an hour in.
     */
    private static Integer localHour(AirportTimeZoneResolver tz, String iata, long epochSeconds) {
        Optional<ZoneId> zone = tz.zoneFor(iata);
        return zone.map(z -> Instant.ofEpochSecond(epochSeconds).atZone(z).getHour()).orElse(null);
    }

    private static Map<String, Long> mediansOfDenseBuckets(Map<String, List<Long>> buckets) {
        Map<String, Long> result = new HashMap<>();
        buckets.forEach((k, values) -> {
            if (values.size() >= MIN_SAMPLES) result.put(k, median(values));
        });
        return result;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static String key(Object... parts) {
        var sb = new StringBuilder();
        for (Object part : parts) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(part);
        }
        return sb.toString();
    }
}
