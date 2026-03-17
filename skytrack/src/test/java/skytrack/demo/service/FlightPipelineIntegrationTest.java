package skytrack.demo.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlightPipelineIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamoDbClient;
    private static AircraftTrackRepository repository;
    private static StatefulFlightPositionHandler handler;

    @BeforeAll
    static void setUp() throws Exception {
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        // Create DynamoDB table
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName("skytrack-aircraft")
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("icao24").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sortKey").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("icao24").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sortKey").keyType(KeyType.RANGE).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
        DynamoDbTable<AircraftTrack> table = enhancedClient.table(
                "skytrack-aircraft", TableSchema.fromBean(AircraftTrack.class));
        repository = new AircraftTrackRepository(table);

        // Build the handler with real components
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();

        var props = new StateMachineProperties(150.0, 50.0, 5.0, 300);
        var stateMachine = new AircraftStateMachine(airportLookup, props);
        var callsignParser = new CallsignParser();
        var routeAverageEstimator = new RouteAverageEstimator();

        // Stub AeroAPI client — returns a schedule for UAL1234
        FlightScheduleApiClient apiClient = new FlightScheduleApiClient() {
            @Override
            public Optional<FlightSchedule> getFlightSchedule(String callsign, String date) {
                if ("UAL1234".equals(callsign)) {
                    return Optional.of(new FlightSchedule(
                            "UAL1234", "UA1234", "UAL", "LAX", "ORD",
                            Instant.parse("2026-03-15T14:00:00Z"),
                            Instant.parse("2026-03-15T18:00:00Z"),
                            Instant.parse("2026-03-15T14:10:00Z"),
                            null, null, null, "B738"));
                }
                return Optional.empty();
            }

            @Override
            public List<FlightSchedule> getDailyFlights(String date) {
                return List.of();
            }
        };

        var scheduleResolver = new ScheduleResolver(apiClient, callsignParser, routeAverageEstimator);
        handler = new StatefulFlightPositionHandler(repository, stateMachine, scheduleResolver);
    }

    @AfterAll
    static void tearDown() {
        if (dynamoDbClient != null) dynamoDbClient.close();
    }

    @Test
    void shouldTrackFlightFromEnRouteToLanding() {
        String icao24 = "integ-test-1";
        long t = Instant.parse("2026-03-15T17:50:00Z").getEpochSecond();

        // 1. Airborne, far from ORD
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track1 = repository.findByIcao24(icao24);
        assertThat(track1).isPresent();
        assertThat(track1.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);

        // 2. Descending near ORD (within 50km approach radius)
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 42.1, -87.8, 3000.0, 250.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track2 = repository.findByIcao24(icao24);
        assertThat(track2).isPresent();
        assertThat(track2.get().getAircraftState()).isEqualTo(AircraftState.APPROACHING);

        // 3. Landed at ORD (onGround=true, within 5km)
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        var track3 = repository.findByIcao24(icao24);
        assertThat(track3).isPresent();
        assertThat(track3.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(track3.get().getNearestAirportIcao()).isEqualTo("KORD");
    }

    @Test
    void shouldTransitionToDepartedWhenTakingOff() {
        String icao24 = "integ-test-2";
        long t = Instant.parse("2026-03-15T18:00:00Z").getEpochSecond();

        // Start on ground at ORD
        handler.handle(List.of(new FlightPosition(
                icao24, "DAL567", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        var track1 = repository.findByIcao24(icao24);
        assertThat(track1.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);

        // Take off
        t += 120;
        handler.handle(List.of(new FlightPosition(
                icao24, "DAL567", 41.98, -87.91, 500.0, 150.0, 270.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track2 = repository.findByIcao24(icao24);
        assertThat(track2.get().getAircraftState()).isEqualTo(AircraftState.DEPARTED);
    }

    @Test
    void shouldHandleUnknownCallsignGracefully() {
        String icao24 = "integ-test-3";
        long t = Instant.parse("2026-03-15T19:00:00Z").getEpochSecond();

        // Airborne with unknown callsign
        handler.handle(List.of(new FlightPosition(
                icao24, "ZZZ999", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        // Should still track state, just can't resolve schedule
        var track = repository.findByIcao24(icao24);
        assertThat(track).isPresent();
        assertThat(track.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }
}
