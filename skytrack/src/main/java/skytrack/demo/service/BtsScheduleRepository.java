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

    // Package-private: used by tests and by the CSV-loading @Component constructor (Task 4).
    BtsScheduleRepository(List<BtsFlightRecord> records) {
        this.all = List.copyOf(records);
        this.byTail = all.stream()
                .filter(r -> r.tailNumber() != null && !r.tailNumber().isBlank())
                .collect(Collectors.groupingBy(BtsFlightRecord::tailNumber));
    }

    public Optional<BtsFlightRecord> findInboundLeg(String carrierIata, String flightNumber,
                                                    String destIata, long nearArrivalEpoch) {
        return all.stream()
                .filter(r -> r.carrierIata().equals(carrierIata)
                        && r.flightNumber().equals(flightNumber)
                        && r.dest().equals(destIata))
                .min(Comparator.comparingLong(r -> Math.abs(r.scheduledDepEpoch() - nearArrivalEpoch)));
    }

    public Optional<BtsFlightRecord> findNextDeparture(String tailNumber, String fromAirportIata,
                                                       long afterEpoch) {
        return byTail.getOrDefault(tailNumber, List.of()).stream()
                .filter(r -> r.origin().equals(fromAirportIata) && r.scheduledDepEpoch() > afterEpoch)
                .min(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch));
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
                parser.parse(splitCsvLine(line), idx).ifPresent(records::add);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load BTS CSV: " + path, e);
        }
        return new BtsScheduleRepository(records);
    }
}
