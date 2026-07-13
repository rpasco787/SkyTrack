package skytrack.demo.service;

import skytrack.demo.model.BtsFlightRecord;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class BtsRowParser {

    private final Function<String, Optional<ZoneId>> zoneLookup;

    public BtsRowParser(Function<String, Optional<ZoneId>> zoneLookup) {
        this.zoneLookup = zoneLookup;
    }

    public Optional<BtsFlightRecord> parse(String[] row, Map<String, Integer> idx) {
        String origin = at(row, idx, "ORIGIN");
        Optional<ZoneId> zone = zoneLookup.apply(origin);
        if (zone.isEmpty()) return Optional.empty();

        String flDate = at(row, idx, "FL_DATE");
        String crsDep = at(row, idx, "CRS_DEP_TIME");
        Long epoch = toEpoch(flDate, crsDep, zone.get());
        if (epoch == null) return Optional.empty();

        boolean cancelled = "1.00".equals(at(row, idx, "CANCELLED")) || "1".equals(at(row, idx, "CANCELLED"));
        Long delaySeconds = cancelled ? null : minutesToSeconds(at(row, idx, "DEP_DELAY"));

        return Optional.of(new BtsFlightRecord(
                at(row, idx, "OP_UNIQUE_CARRIER"),
                at(row, idx, "OP_CARRIER_FL_NUM"),
                at(row, idx, "TAIL_NUM"),
                origin,
                at(row, idx, "DEST"),
                epoch,
                delaySeconds,
                cancelled));
    }

    private static String at(String[] row, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        return (i == null || i >= row.length) ? "" : row[i].trim();
    }

    private static Long toEpoch(String flDate, String hhmm, ZoneId zone) {
        if (flDate.isBlank() || hhmm.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(flDate);
            int raw = Integer.parseInt(hhmm);
            if (raw == 2400) { date = date.plusDays(1); raw = 0; }  // BTS midnight encoding
            LocalTime time = LocalTime.of(raw / 100, raw % 100);
            return ZonedDateTime.of(date, time, zone).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    private static Long minutesToSeconds(String mins) {
        if (mins == null || mins.isBlank()) return null;
        try {
            return (long) (Double.parseDouble(mins) * 60);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
