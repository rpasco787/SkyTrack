package skytrack.demo.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.metrics.PipelineMetrics;
import skytrack.demo.service.RecentCascadeStore;
import skytrack.demo.service.ScheduleCoverageTracker;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;
import skytrack.demo.sqs.SqsAirportEventProducer;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    private static DisruptionScoreService disruptionScoreService;
    private static SqsAirportEventProducer mockEventProducer;

    @BeforeAll
    static void setUp() throws Exception {
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

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
        repository = new AircraftTrackRepository(table, enhancedClient);

        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();

        var smProps = new StateMachineProperties(150.0, 50.0, 5.0, 300, 120);
        var stateMachine = new AircraftStateMachine(airportLookup, smProps);
        var callsignParser = new CallsignParser();
        var routeAverageEstimator = new RouteAverageEstimator();

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

        var scheduleResolver = new ScheduleResolver(apiClient, callsignParser, routeAverageEstimator,
                new PipelineMetrics(new SimpleMeterRegistry()));

        // Delay pipeline
        var delayComputer = new DelayComputer();
        var disruptionProps = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        disruptionScoreService = new DisruptionScoreService(disruptionProps);
        mockEventProducer = mock(SqsAirportEventProducer.class);
        var cascadeChainDetector = new CascadeChainDetector(
                new CallsignParser(), BtsScheduleRepository.empty(),
                new TurnaroundEstimator(new skytrack.demo.config.PredictionProperties(false, "x", 45, 15, 360), Map.of()),
                disruptionProps, new skytrack.demo.config.PredictionProperties(false, "x", 45, 15, 360), java.time.Clock.systemUTC(), Map.of());
        var weatherCache = mock(WeatherCache.class);
        org.mockito.Mockito.when(weatherCache.get(any())).thenReturn(Optional.empty());
        var delayEventProcessor = new DelayEventProcessor(
                delayComputer, disruptionScoreService, mockEventProducer, cascadeChainDetector, weatherCache,
                mock(skytrack.demo.parquet.HistoricalDelayWriter.class),
                mock(ScheduleCoverageTracker.class),
                mock(RecentCascadeStore.class),
                mock(DelayPredictionService.class));

        handler = new StatefulFlightPositionHandler(
                repository, stateMachine, scheduleResolver, delayEventProcessor, smProps,
                new PipelineMetrics(new SimpleMeterRegistry()));
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

        // Verify delay event was published to SQS
        verify(mockEventProducer).send(any(DelayEvent.class));
    }

    @Test
    void shouldTransitionToDepartedWhenTakingOff() {
        String icao24 = "integ-test-2";
        long t = Instant.parse("2026-03-15T18:00:00Z").getEpochSecond();

        handler.handle(List.of(new FlightPosition(
                icao24, "DAL567", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        var track1 = repository.findByIcao24(icao24);
        assertThat(track1.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);

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

        handler.handle(List.of(new FlightPosition(
                icao24, "ZZZ999", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track = repository.findByIcao24(icao24);
        assertThat(track).isPresent();
        assertThat(track.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldUpdateDisruptionScoreAfterLanding() {
        String icao24 = "integ-test-4";
        long t = Instant.parse("2026-03-15T20:00:00Z").getEpochSecond();

        // Fly and land at ORD
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 42.1, -87.8, 3000.0, 250.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        // Verify disruption score was updated for ORD
        var score = disruptionScoreService.computeScore("ORD");
        assertThat(score.totalFlightsInWindow()).isGreaterThanOrEqualTo(1);
    }
}
