package skytrack.demo.repository;

import org.springframework.stereotype.Repository;
import skytrack.demo.model.AircraftTrack;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class AircraftTrackRepository {

    private final DynamoDbTable<AircraftTrack> table;
    private final DynamoDbEnhancedClient enhancedClient;

    public AircraftTrackRepository(DynamoDbTable<AircraftTrack> table,
                                   DynamoDbEnhancedClient enhancedClient) {
        this.table = table;
        this.enhancedClient = enhancedClient;
    }

    /**
     * Batch-read tracks by icao24.
     *
     * @return a <strong>mutable</strong> map containing only the aircraft that exist. Callers rely
     *         on mutability to insert {@code AircraftTrack.initial(...)} for unseen aircraft and to
     *         keep one instance per aircraft for the whole batch — {@code AircraftStateMachine}
     *         mutates in place, so re-reading mid-batch would discard unsaved mutations.
     */
    public Map<String, AircraftTrack> findAllByIcao24(Collection<String> icao24s) {
        if (icao24s.isEmpty()) {
            return new HashMap<>();
        }

        ReadBatch.Builder<AircraftTrack> readBatch = ReadBatch.builder(AircraftTrack.class)
                .mappedTableResource(table);
        // BatchGetItem caps at 100 keys; a receive batch is at most 10, so no chunking is needed.
        for (String icao24 : Set.copyOf(icao24s)) {
            readBatch.addGetItem(Key.builder()
                    .partitionValue(icao24)
                    .sortValue("TRACK")
                    .build());
        }

        Map<String, AircraftTrack> tracks = new HashMap<>();
        enhancedClient.batchGetItem(BatchGetItemEnhancedRequest.builder()
                        .readBatches(readBatch.build())
                        .build())
                .resultsForTable(table)
                .forEach(track -> tracks.put(track.getIcao24(), track));
        return tracks;
    }

    public Optional<AircraftTrack> findByCallsign(String callsign) {
        Expression filter = Expression.builder()
                .expression("callsign = :cs")
                .putExpressionValue(":cs", AttributeValue.builder().s(callsign).build())
                .build();
        return table.scan(ScanEnhancedRequest.builder()
                        .filterExpression(filter)
                        .build())
                .items().stream()
                .findFirst();
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
