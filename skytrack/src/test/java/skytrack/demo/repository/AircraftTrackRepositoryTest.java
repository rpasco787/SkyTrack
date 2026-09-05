package skytrack.demo.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.model.AircraftState;
import skytrack.demo.model.AircraftTrack;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// Needs a Docker daemon (Testcontainers LocalStack). CI runs this tag in its own job:
//   ./mvnw verify -Dgroups=integration            (only these)
//   ./mvnw verify -DexcludedGroups=integration    (everything else)
@Tag("integration")
@Testcontainers
class AircraftTrackRepositoryTest {

    @SuppressWarnings("resource")
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamoDbClient;
    private static AircraftTrackRepository repository;

    @BeforeAll
    static void setUpTable() {
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
    }

    @AfterAll
    static void tearDown() {
        if (dynamoDbClient != null) dynamoDbClient.close();
    }

    @Test
    void shouldSaveAndFindTrack() {
        var track = AircraftTrack.initial("test-save-find");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setCallsign("UAL1234");
        track.setLatitude(41.9742);
        track.setLongitude(-87.9073);

        repository.save(track);
        Optional<AircraftTrack> found = repository.findByIcao24("test-save-find");

        assertThat(found).isPresent();
        assertThat(found.get().getCallsign()).isEqualTo("UAL1234");
        assertThat(found.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
        assertThat(found.get().getLatitude()).isEqualTo(41.9742);
    }

    @Test
    void shouldBatchReadOnlyTheAircraftThatExist() {
        // Against real DynamoDB, not a mock: findAllByIcao24 builds its own keys (partition icao24 +
        // sort "TRACK") and reads through resultsForTable, none of which a mocked client would
        // validate. A wrong sort key here returns an empty map and the handler would silently treat
        // every aircraft as new.
        var one = AircraftTrack.initial("batch-one");
        one.setAircraftState(AircraftState.EN_ROUTE);
        var two = AircraftTrack.initial("batch-two");
        repository.save(one);
        repository.save(two);

        var found = repository.findAllByIcao24(List.of("batch-one", "batch-two", "batch-absent"));

        assertThat(found).containsOnlyKeys("batch-one", "batch-two");
        assertThat(found.get("batch-one").getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldReturnAMutableMapFromTheBatchRead() {
        // The handler calls computeIfAbsent on this map to insert tracks for unseen aircraft.
        // Returning an immutable map would throw on the first new aircraft in a batch.
        repository.save(AircraftTrack.initial("batch-mutable"));

        var found = repository.findAllByIcao24(List.of("batch-mutable"));
        found.put("inserted-by-caller", AircraftTrack.initial("inserted-by-caller"));

        assertThat(found).containsKey("inserted-by-caller");
    }

    @Test
    void shouldReturnAnEmptyMapForNoKeysWithoutCallingDynamo() {
        assertThat(repository.findAllByIcao24(List.of())).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnknownIcao24() {
        Optional<AircraftTrack> found = repository.findByIcao24("nonexistent-icao24");
        assertThat(found).isEmpty();
    }

    @Test
    void shouldOverwriteExistingTrack() {
        var track = AircraftTrack.initial("test-overwrite");
        track.setAircraftState(AircraftState.EN_ROUTE);
        repository.save(track);

        track.setAircraftState(AircraftState.ON_GROUND);
        track.setNearestAirportIcao("KORD");
        repository.save(track);

        var found = repository.findByIcao24("test-overwrite");
        assertThat(found).isPresent();
        assertThat(found.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(found.get().getNearestAirportIcao()).isEqualTo("KORD");
    }

    @Test
    void shouldDeleteTrack() {
        var track = AircraftTrack.initial("test-delete");
        repository.save(track);
        assertThat(repository.findByIcao24("test-delete")).isPresent();

        repository.delete("test-delete");
        assertThat(repository.findByIcao24("test-delete")).isEmpty();
    }

    @Test
    void shouldAutoSetUpdatedAtAndTtlOnSave() {
        var track = AircraftTrack.initial("test-timestamps");
        long beforeSave = System.currentTimeMillis() / 1000;
        repository.save(track);

        var found = repository.findByIcao24("test-timestamps").orElseThrow();
        assertThat(found.getUpdatedAt()).isGreaterThanOrEqualTo(beforeSave);
        assertThat(found.getTtl()).isGreaterThan(found.getUpdatedAt());
    }

    @Test
    void shouldFindTrackByCallsign() {
        AircraftTrack t1 = AircraftTrack.initial("aaa111");
        t1.setCallsign("UAL100");
        repository.save(t1);

        AircraftTrack t2 = AircraftTrack.initial("bbb222");
        t2.setCallsign("DAL200");
        repository.save(t2);

        Optional<AircraftTrack> found = repository.findByCallsign("DAL200");
        assertThat(found).isPresent();
        assertThat(found.get().getIcao24()).isEqualTo("bbb222");
    }

    @Test
    void shouldReturnEmptyWhenCallsignNotFound() {
        assertThat(repository.findByCallsign("ZZZ999")).isEmpty();
    }
}
