package skytrack.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamoDbBean
public class AircraftTrack {

    private String icao24;
    private String sortKey;
    private String state;
    private String callsign;
    private Double latitude;
    private Double longitude;
    private Double baroAltitude;
    private Long lastSeen;
    private String nearestAirportIcao;
    private Long stateEnteredAt;
    private Long updatedAt;
    private Long ttl;

    @DynamoDbPartitionKey
    public String getIcao24() { return icao24; }

    @DynamoDbSortKey
    public String getSortKey() { return sortKey; }

    public AircraftState getAircraftState() {
        return state != null ? AircraftState.valueOf(state) : AircraftState.UNKNOWN;
    }

    public void setAircraftState(AircraftState aircraftState) {
        this.state = aircraftState.name();
    }

    public static AircraftTrack initial(String icao24) {
        long now = Instant.now().getEpochSecond();
        return AircraftTrack.builder()
                .icao24(icao24)
                .sortKey("TRACK")
                .state(AircraftState.UNKNOWN.name())
                .stateEnteredAt(now)
                .updatedAt(now)
                .ttl(now + 86400)
                .build();
    }
}
