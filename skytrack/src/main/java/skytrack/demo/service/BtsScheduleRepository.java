package skytrack.demo.service;

import skytrack.demo.model.BtsFlightRecord;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class BtsScheduleRepository {

    private final List<BtsFlightRecord> all;
    private final Map<String, List<BtsFlightRecord>> byTail;
    private final Map<String, List<BtsFlightRecord>> byInbound;

    // Package-private: used by tests and by the CSV-loading @Component constructor (Task 4).
    BtsScheduleRepository(List<BtsFlightRecord> records) {
        this.all = List.copyOf(records);
        this.byTail = all.stream()
                .filter(r -> r.tailNumber() != null && !r.tailNumber().isBlank())
                .collect(Collectors.groupingBy(BtsFlightRecord::tailNumber));
        this.byInbound = all.stream()
                .collect(Collectors.groupingBy(BtsScheduleRepository::inboundKey));
    }

    private static String inboundKey(BtsFlightRecord r) {
        return r.carrierIata() + "|" + r.flightNumber() + "|" + r.dest();
    }

    public Optional<BtsFlightRecord> findInboundLeg(String carrierIata, String flightNumber,
                                                    String destIata, long nearArrivalEpoch) {
        return byInbound.getOrDefault(carrierIata + "|" + flightNumber + "|" + destIata, List.of())
                .stream()
                .min(Comparator.comparingLong(
                        r -> Math.abs(arrivalOrDeparture(r) - nearArrivalEpoch)));
    }

    /**
     * Candidate inbound legs are ranked against the observed landing time, so they must be
     * compared on scheduled <em>arrival</em>. Comparing on scheduled departure biases the match
     * by one flight duration. Legs with no scheduled arrival fall back to departure.
     */
    private static long arrivalOrDeparture(BtsFlightRecord r) {
        return r.scheduledArrEpoch() != null ? r.scheduledArrEpoch() : r.scheduledDepEpoch();
    }

    public Optional<BtsFlightRecord> findNextDeparture(String tailNumber, String fromAirportIata,
                                                       long afterEpoch) {
        return findNextDeparture(tailNumber, fromAirportIata, afterEpoch, Long.MAX_VALUE);
    }

    /**
     * As {@link #findNextDeparture(String, String, long)}, but ignores departures more than
     * {@code maxLookaheadSeconds} after the cutoff. Without a bound, an aircraft that lands in
     * the evening and overnights matches its next-morning departure, whose delay has no causal
     * link to the arrival that started the walk.
     */
    public Optional<BtsFlightRecord> findNextDeparture(String tailNumber, String fromAirportIata,
                                                       long afterEpoch, long maxLookaheadSeconds) {
        long cutoff = maxLookaheadSeconds >= Long.MAX_VALUE - afterEpoch
                ? Long.MAX_VALUE
                : afterEpoch + maxLookaheadSeconds;
        return byTail.getOrDefault(tailNumber, List.of()).stream()
                .filter(r -> r.origin().equals(fromAirportIata)
                        && r.scheduledDepEpoch() > afterEpoch
                        && r.scheduledDepEpoch() <= cutoff)
                .min(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch));
    }

    /**
     * The leg this aircraft flew immediately before {@code beforeScheduledDepEpoch} — the inbound
     * rotation that could have caused a late-aircraft delay on the leg departing then.
     *
     * <p>Used to ask whether a delayed leg's <em>cause</em> was something the replay could
     * plausibly have seen. Without that, a recall denominator counts legs whose causing arrival
     * happened outside the observation window entirely, and measures data collection rather than
     * the detector.</p>
     */
    public Optional<BtsFlightRecord> findPreviousLeg(String tailNumber, long beforeScheduledDepEpoch) {
        if (tailNumber == null) return Optional.empty();
        return byTail.getOrDefault(tailNumber, List.of()).stream()
                .filter(r -> r.scheduledDepEpoch() < beforeScheduledDepEpoch)
                .max(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch));
    }

    /** Ground times outside this band are data errors or overnights, not turnarounds. */
    private static final long MIN_PLAUSIBLE_TURNAROUND_SECONDS = 300;
    private static final long MAX_PLAUSIBLE_TURNAROUND_SECONDS = 14_400;

    /** An inbound at least this late puts the turnaround under genuine time pressure. */
    private static final long PRESSURED_ARRIVAL_DELAY_SECONDS = 900;

    private static final int MIN_TURNAROUND_SAMPLES = 30;

    /**
     * @deprecated measures <em>scheduled</em> ground time, which callers then subtract from the
     * scheduled gap to compute slack — subtracting the quantity from itself and leaving slack
     * definitionally near zero. Use {@link #pressuredTurnaroundP15ByCarrierAirport()}.
     */
    @Deprecated
    public Map<String, Long> medianTurnaroundSecondsByCarrier() {
        Map<String, List<Long>> gapsByCarrier = new HashMap<>();
        forEachRotation((prev, curr) -> {
            long gap = curr.scheduledDepEpoch() - prev.scheduledArrEpoch();
            if (gap < MIN_PLAUSIBLE_TURNAROUND_SECONDS || gap > MAX_PLAUSIBLE_TURNAROUND_SECONDS) return;
            gapsByCarrier.computeIfAbsent(curr.carrierIata(), k -> new ArrayList<>()).add(gap);
        });
        Map<String, Long> result = new HashMap<>();
        gapsByCarrier.forEach((carrier, gaps) -> {
            List<Long> sortedGaps = gaps.stream().sorted().toList();
            result.put(carrier, sortedGaps.get(sortedGaps.size() / 2));
        });
        return result;
    }

    /**
     * The physical floor on turning an aircraft around: the 15th percentile of <em>actual</em>
     * ground time, measured only on rotations where the inbound landed more than 15 minutes late.
     *
     * <p>Scheduled ground time is the wrong quantity for a minimum. It is a commercial decision
     * padded with slack, and using it as {@code minTurnaround} makes the slack term collapse. What
     * a delay model needs is how fast a crew <em>can</em> turn the aircraft when it has to, which
     * only late inbounds reveal — on a punctual rotation the aircraft simply waits for its slot,
     * so the observed ground time measures the schedule rather than the operation.</p>
     *
     * <p>Returned map carries two key forms: {@code "CARRIER|AIRPORT"} and bare {@code "CARRIER"},
     * both requiring 30 samples. Callers should prefer the more specific one and fall back;
     * {@link TurnaroundEstimator} does this. Build keys with {@link #turnaroundKey}.</p>
     */
    public Map<String, Long> pressuredTurnaroundP15ByCarrierAirport() {
        return pressuredTurnaroundByCarrierAirport(0.15);
    }

    /**
     * The <em>typical</em> turnaround under time pressure, from the same population as
     * {@link #pressuredTurnaroundP15ByCarrierAirport()}.
     *
     * <p>Slack and prediction need different statistics of the same distribution. Slack asks how
     * much buffer exists before a rotation breaks, so it wants the floor. Prediction asks when the
     * aircraft will actually be ready to push back, and a floor answers that question with the
     * best case every time — which shows up directly as under-prediction.</p>
     */
    public Map<String, Long> pressuredTurnaroundP50ByCarrierAirport() {
        return pressuredTurnaroundByCarrierAirport(0.50);
    }

    /**
     * @param quantile position in the sorted turnaround distribution, 0.0 (fastest observed) to
     *                 1.0 (slowest).
     */
    public Map<String, Long> pressuredTurnaroundByCarrierAirport(double quantile) {
        Map<String, List<Long>> byCarrierAirport = new HashMap<>();
        Map<String, List<Long>> byCarrier = new HashMap<>();
        forEachRotation((prev, curr) -> {
            if (prev.arrDelaySeconds() == null
                    || prev.arrDelaySeconds() <= PRESSURED_ARRIVAL_DELAY_SECONDS) return;
            if (curr.cancelled() || curr.actualDepDelaySeconds() == null) return;
            long turnaround = (curr.scheduledDepEpoch() + curr.actualDepDelaySeconds())
                            - (prev.scheduledArrEpoch() + prev.arrDelaySeconds());
            if (turnaround < MIN_PLAUSIBLE_TURNAROUND_SECONDS
                    || turnaround > MAX_PLAUSIBLE_TURNAROUND_SECONDS) return;
            byCarrierAirport.computeIfAbsent(
                    turnaroundKey(curr.carrierIata(), curr.origin()), k -> new ArrayList<>())
                    .add(turnaround);
            byCarrier.computeIfAbsent(curr.carrierIata(), k -> new ArrayList<>()).add(turnaround);
        });

        Map<String, Long> result = new HashMap<>();
        putQuantileOfDenseBuckets(result, byCarrierAirport, quantile);
        putQuantileOfDenseBuckets(result, byCarrier, quantile);
        return result;
    }

    /** The single definition of the composite turnaround key, shared with {@link TurnaroundEstimator}. */
    public static String turnaroundKey(String carrierIata, String airportIata) {
        return carrierIata + "|" + airportIata;
    }

    private static void putQuantileOfDenseBuckets(Map<String, Long> target,
                                                  Map<String, List<Long>> buckets, double quantile) {
        buckets.forEach((key, values) -> {
            if (values.size() < MIN_TURNAROUND_SAMPLES) return;
            List<Long> sorted = values.stream().sorted().toList();
            target.put(key, sorted.get(Math.min((int) (sorted.size() * quantile), sorted.size() - 1)));
        });
    }

    /**
     * Visits every consecutive pair of legs flown by the same tail that forms a real rotation —
     * the aircraft departs the airport it just arrived at. Legs with no scheduled arrival are
     * dropped from the sequence entirely, since they cannot anchor the ground time.
     */
    private void forEachRotation(java.util.function.BiConsumer<BtsFlightRecord, BtsFlightRecord> visitor) {
        for (List<BtsFlightRecord> legs : byTail.values()) {
            List<BtsFlightRecord> sorted = legs.stream()
                    .filter(r -> r.scheduledArrEpoch() != null)
                    .sorted(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch))
                    .toList();
            for (int i = 1; i < sorted.size(); i++) {
                BtsFlightRecord prev = sorted.get(i - 1);
                BtsFlightRecord curr = sorted.get(i);
                if (!prev.dest().equals(curr.origin())) continue;
                visitor.accept(prev, curr);
            }
        }
    }

    public Map<String, Double> medianRecoveryFactorByRoute() {
        Map<String, List<Double>> factorsByRoute = new HashMap<>();
        for (BtsFlightRecord r : all) {
            if (r.lateAircraftDelaySeconds() == null || r.lateAircraftDelaySeconds() <= 0) continue;
            if (r.actualDepDelaySeconds() == null || r.actualDepDelaySeconds() <= 0) continue;
            if (r.arrDelaySeconds() == null) continue;
            double recovery = (double)(r.actualDepDelaySeconds() - r.arrDelaySeconds())
                              / r.actualDepDelaySeconds();
            recovery = Math.max(0.0, recovery);
            factorsByRoute.computeIfAbsent(r.origin() + "-" + r.dest(), k -> new ArrayList<>())
                          .add(recovery);
        }
        Map<String, Double> result = new HashMap<>();
        factorsByRoute.forEach((route, factors) -> {
            List<Double> sorted = factors.stream().sorted().toList();
            result.put(route, sorted.get(sorted.size() / 2));
        });
        return result;
    }

    public int size() { return all.size(); }

    public List<BtsFlightRecord> all() { return all; }

    public static BtsScheduleRepository empty() {
        return new BtsScheduleRepository(List.of());
    }

    public static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(String[]::new);
    }

    public static BtsScheduleRepository fromCsv(String path, AirportTimeZoneResolver tz) {
        return fromCsv(path, tz, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static BtsScheduleRepository fromCsv(String path, AirportTimeZoneResolver tz,
                                                 long fromEpochInclusive, long toEpochExclusive) {
        var parser = new BtsRowParser(tz::zoneFor);
        var records = new ArrayList<BtsFlightRecord>();
        try (var reader = new BufferedReader(new FileReader(path, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return empty();
            String[] cols = splitCsvLine(header);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                idx.put(cols[i].replace("\"", "").trim(), i);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                parser.parse(splitCsvLine(line), idx)
                        .filter(r -> r.scheduledDepEpoch() >= fromEpochInclusive
                                && r.scheduledDepEpoch() < toEpochExclusive)
                        .ifPresent(records::add);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load BTS CSV: " + path, e);
        }
        return new BtsScheduleRepository(records);
    }
}
