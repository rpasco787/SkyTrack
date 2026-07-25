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
                .min(Comparator.comparingLong(r -> Math.abs(r.scheduledDepEpoch() - nearArrivalEpoch)));
    }

    public Optional<BtsFlightRecord> findNextDeparture(String tailNumber, String fromAirportIata,
                                                       long afterEpoch) {
        return byTail.getOrDefault(tailNumber, List.of()).stream()
                .filter(r -> r.origin().equals(fromAirportIata) && r.scheduledDepEpoch() > afterEpoch)
                .min(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch));
    }

    public Map<String, Long> medianTurnaroundSecondsByCarrier() {
        Map<String, List<Long>> gapsByCarrier = new HashMap<>();
        for (List<BtsFlightRecord> legs : byTail.values()) {
            List<BtsFlightRecord> sorted = legs.stream()
                    .filter(r -> r.scheduledArrEpoch() != null)
                    .sorted(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch))
                    .toList();
            for (int i = 1; i < sorted.size(); i++) {
                BtsFlightRecord prev = sorted.get(i - 1);
                BtsFlightRecord curr = sorted.get(i);
                if (!prev.dest().equals(curr.origin())) continue;
                long gap = curr.scheduledDepEpoch() - prev.scheduledArrEpoch();
                if (gap < 300 || gap > 14_400) continue;
                gapsByCarrier.computeIfAbsent(curr.carrierIata(), k -> new ArrayList<>()).add(gap);
            }
        }
        Map<String, Long> result = new HashMap<>();
        gapsByCarrier.forEach((carrier, gaps) -> {
            List<Long> sortedGaps = gaps.stream().sorted().toList();
            result.put(carrier, sortedGaps.get(sortedGaps.size() / 2));
        });
        return result;
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

    public static BtsScheduleRepository empty() {
        return new BtsScheduleRepository(List.of());
    }

    static String[] splitCsvLine(String line) {
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
