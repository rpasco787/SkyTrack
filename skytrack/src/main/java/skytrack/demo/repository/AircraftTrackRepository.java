package skytrack.demo.repository;

import org.springframework.stereotype.Repository;
import skytrack.demo.model.AircraftTrack;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;
import java.util.Optional;

@Repository
public class AircraftTrackRepository {

    private final DynamoDbTable<AircraftTrack> table;

    public AircraftTrackRepository(DynamoDbTable<AircraftTrack> table) {
        this.table = table;
    }

    public Optional<AircraftTrack> findByIcao24(String icao24) {
        AircraftTrack track = table.getItem(Key.builder()
                .partitionValue(icao24)
                .sortValue("TRACK")
                .build());
        return Optional.ofNullable(track);
    }

    public void save(AircraftTrack track) {
        long now = Instant.now().getEpochSecond();
        track.setUpdatedAt(now);
        track.setTtl(now + 86400);
        table.putItem(track);
    }

    public void delete(String icao24) {
        table.deleteItem(Key.builder()
                .partitionValue(icao24)
                .sortValue("TRACK")
                .build());
    }
}
