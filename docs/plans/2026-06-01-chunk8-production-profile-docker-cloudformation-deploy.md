# Chunk 8: Production Profile + Dockerfile + CloudFormation + One-Time AWS Free-Tier Deploy

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make SkyTrack genuinely deployable: a real-AWS Spring profile (AeroAPI-free, live OpenSky + live weather), a multi-stage Dockerfile, a CloudFormation template that provisions every backing resource plus a t3.micro EC2 host, an ECR-based deploy script, and a documented one-time deployment to the AWS Free Tier that yields a live public URL — then a teardown that guarantees the bill stays at **$0**.

**Architecture:** No application Java changes are needed for AWS connectivity — the existing `S3Config`/`DynamoDbConfig`/`SqsConfig` beans already switch to real AWS (default credential chain → EC2 IAM instance role) whenever their `endpoint` property is blank. So the `prod` profile simply *omits* the LocalStack endpoint overrides and disables AeroAPI. The image is a fat-jar on a slim JRE 25 base. CloudFormation provisions exactly what [`localstack/init-aws.sh`](../../localstack/init-aws.sh) creates locally — 2 SQS FIFO queues, 1 DynamoDB table (`icao24` HASH + `sortKey` RANGE, on-demand, TTL on `ttl`), 1 S3 bucket — **plus** an ECR repo, an IAM instance role scoped to those resources, a security group, and a t3.micro instance whose user-data pulls the image from ECR and runs it under `SPRING_PROFILES_ACTIVE=prod`.

**Tech Stack:** Spring Boot 4.0.2, Java 25, AWS SDK v2, Docker (multi-stage, `eclipse-temurin:25-jre-alpine`), Amazon ECR, AWS CloudFormation, EC2 (Amazon Linux 2023), IAM instance profile, Spring Boot Actuator (`/actuator/health`). Tests: JUnit 5 + AssertJ + Spring Boot `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`.

**Depends on:** Chunks 1–7 (OpenSky clients, SQS pipeline, AeroAPI + WireMock, DynamoDB + state machine, delay detection + disruption scoring, weather integration, S3 Parquet + REST API).

**Decisions locked for this chunk (2026-06-01):**
- **Flight data in prod:** `opensky.mode=live` with free OpenSky credentials supplied via environment variables. `data/recorded-opensky/` is empty, so replay isn't viable in prod, and live data makes "deployed to AWS" fully honest.
- **Weather in prod:** `weather.mode=live` against `aviationweather.gov` (public, no API key, free) — keeps the demo fully live at $0.
- **AeroAPI:** **disabled** in prod (`aeroapi.enabled=false`). Schedule resolution falls back to BTS + synthetic, so no paid FlightAware calls ever fire.
- **Image delivery:** private **ECR** repo (500 MB/month free tier); EC2 user-data pulls it.
- **Deploy scope:** the plan includes a real one-time deploy runbook **and** a teardown step.

---

## Reality Checks Baked Into This Plan

These are the gaps between the roadmap prose and the actual codebase. The plan follows the **code**, not the prose:

| Roadmap says | Reality | Plan does |
|---|---|---|
| Runtime "Eclipse Temurin JRE 17" | Project is **Java 25** | Base image `eclipse-temurin:25-jre-alpine`; revisit the <200 MB target (JRE 25 is larger) |
| DynamoDB table has GSI1 | [`init-aws.sh`](../../localstack/init-aws.sh) creates **no GSI**; `findByCallsign` is a table scan | CloudFormation table has **no GSI** (matches code) |
| Existing `application-prod.yml` sets `aeroapi.enabled: true` + real FlightAware URL | Contradicts the AeroAPI-free goal | Rewrite to `enabled: false` |
| `setup.sh` documenting resources | We use **CloudFormation** (declarative, idempotent, deletable) | `infra/skytrack.cfn.yml` + `deploy.sh` + `teardown.sh` |

---

# PART A — Production Spring Profile (AeroAPI-free, fully live)

## Task A1: Rewrite `application-prod.yml` for real AWS

**Files:**
- Modify: `skytrack/src/main/resources/application-prod.yml`

The base [`application.yml`](../../skytrack/src/main/resources/application.yml) already supplies queue names, the `skytrack.s3` block, state-machine, disruption, and weather target airports. The `prod` profile only needs to **override what differs** and, crucially, **leave every `endpoint` unset** so the AWS clients use the default credential chain (EC2 IAM role) against real regional endpoints.

### Step 1: Replace the file contents

**`skytrack/src/main/resources/application-prod.yml`:**

```yaml
# Production profile: real AWS (credentials come from the EC2 instance IAM role
# via the AWS SDK default provider chain). No `endpoint:` keys here on purpose —
# a blank endpoint is what makes S3Config/DynamoDbConfig/SqsConfig talk to real AWS.

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true

opensky:
  mode: live
  api-url: https://opensky-network.org
  client-id: ${OPENSKY_CLIENT_ID:}
  client-secret: ${OPENSKY_CLIENT_SECRET:}

sqs:
  region: ${AWS_REGION:us-east-1}
  # positions-queue-name / airport-events-queue-name inherited from application.yml

# AeroAPI stays OFF in production: schedule resolution falls back to BTS + synthetic,
# so no paid FlightAware calls are ever made. This keeps the deployment at $0.
aeroapi:
  enabled: false

skytrack:
  dynamodb:
    table-name: ${DYNAMODB_TABLE:skytrack-aircraft}
    region: ${AWS_REGION:us-east-1}
    # no endpoint -> real DynamoDB
  s3:
    bucket: ${S3_BUCKET:skytrack-history}
    region: ${AWS_REGION:us-east-1}
    prefix: delays
    flush-interval-seconds: 300
    # no endpoint -> real S3

weather:
  mode: live
  api-url: https://aviationweather.gov/api/data/metar
  poll-interval-minutes: 15
  cache-ttl-minutes: 30
```

> **Why no static credentials anywhere:** when `endpoint` is blank, [`S3Config.java:22`](../../skytrack/src/main/java/skytrack/demo/config/S3Config.java#L22), [`DynamoDbConfig.java:26`](../../skytrack/src/main/java/skytrack/demo/config/DynamoDbConfig.java#L26), and [`SqsConfig.java:25`](../../skytrack/src/main/java/skytrack/demo/config/SqsConfig.java#L25) skip the `test/test` static-credentials branch and fall back to the SDK default provider chain, which on EC2 resolves the instance-profile role. That is the whole reason we leave endpoints unset.

### Step 2: Commit

```bash
git add skytrack/src/main/resources/application-prod.yml
git commit -m "config: production profile targets real AWS, AeroAPI disabled, live OpenSky+weather"
```

---

## Task A2: Profile-binding test (prove `prod` is deploy-safe without touching AWS)

We cannot boot the full `prod` context offline — `SqsConfig` resolves queue URLs at bean-creation time, which would hit real AWS. Instead, bind the property records from the merged `application.yml` + `application-prod.yml` under the `prod` profile and assert the safety-critical invariants.

**Files:**
- Test: `skytrack/src/test/java/skytrack/demo/config/ProdProfileBindingTest.java`

### Step 1: Write the failing test

**`ProdProfileBindingTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the property records under the active "prod" profile straight from the
 * YAML files (no AWS beans created, so no network calls). Guards the invariants
 * that keep a production deploy safe and at $0.
 */
class ProdProfileBindingTest {

    @Configuration
    @EnableConfigurationProperties({
            S3Properties.class,
            DynamoDbProperties.class,
            SqsProperties.class,
            AeroApiProperties.class,
            OpenSkyProperties.class,
            WeatherProperties.class
    })
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=prod")
            .withUserConfiguration(Props.class);

    @Test
    void awsEndpointsAreBlankSoClientsUseRealAws() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(S3Properties.class).endpoint()).isNullOrEmpty();
            assertThat(ctx.getBean(DynamoDbProperties.class).endpoint()).isNullOrEmpty();
            assertThat(ctx.getBean(SqsProperties.class).endpoint()).isNullOrEmpty();
        });
    }

    @Test
    void aeroApiIsDisabledInProd() {
        runner.run(ctx ->
                assertThat(ctx.getBean(AeroApiProperties.class).enabled()).isFalse());
    }

    @Test
    void openSkyIsLiveInProd() {
        runner.run(ctx ->
                assertThat(ctx.getBean(OpenSkyProperties.class).mode()).isEqualTo("live"));
    }

    @Test
    void weatherIsLiveInProd() {
        runner.run(ctx ->
                assertThat(ctx.getBean(WeatherProperties.class).mode()).isEqualTo("live"));
    }

    @Test
    void queueNamesInheritedFromBaseProfile() {
        runner.run(ctx -> {
            SqsProperties sqs = ctx.getBean(SqsProperties.class);
            assertThat(sqs.positionsQueueName()).isEqualTo("skytrack-positions.fifo");
            assertThat(sqs.airportEventsQueueName()).isEqualTo("skytrack-airport-events.fifo");
        });
    }
}
```

### Step 2: Run it to verify it passes

Run: `cd skytrack && mvn test -Dtest=ProdProfileBindingTest -q`
Expected: PASS, 5 tests. If `awsEndpointsAreBlankSoClientsUseRealAws` fails, an `endpoint:` key leaked into the prod YAML — remove it.

### Step 3: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/config/ProdProfileBindingTest.java
git commit -m "test: lock prod-profile invariants (blank AWS endpoints, AeroAPI off, live data)"
```

---

# PART B — Dockerfile (multi-stage, slim runtime)

## Task B1: `.dockerignore`

Keep the build context small so `docker build` is fast and the data CSVs (12 MB of airports data) never get sent to the daemon.

**Files:**
- Create: `.dockerignore` (repo root)

### Step 1: Create the file

**`.dockerignore`:**

```gitignore
# Build output
**/target/
# VCS / IDE
.git/
.gitignore
.vscode/
.idea/
# Local infra & data (not needed inside the image)
docker-compose.yml
localstack/
wiremock/
data/
docs/
*.md
# OS noise
**/.DS_Store
```

### Step 2: Commit

```bash
git add .dockerignore
git commit -m "build: add .dockerignore to slim the Docker build context"
```

---

## Task B2: Multi-stage Dockerfile

**Files:**
- Create: `Dockerfile` (repo root)

The Maven module lives in `skytrack/`. The build context is the repo root. Tests are **skipped in the image build** — they need LocalStack/network and run in CI/locally, not during `docker build`.

### Step 1: Create the Dockerfile

**`Dockerfile`:**

```dockerfile
# syntax=docker/dockerfile:1

# --- Build stage: compile the fat jar with the project's Maven wrapper ---
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy wrapper + pom first so dependency resolution is cached across source changes.
COPY skytrack/.mvn/ skytrack/.mvn/
COPY skytrack/mvnw skytrack/pom.xml skytrack/
WORKDIR /workspace/skytrack
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline

# Now copy sources and build (skip tests: they require LocalStack/network).
COPY skytrack/src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests

# --- Runtime stage: slim JRE, non-root, healthcheck-friendly ---
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN addgroup -S skytrack && adduser -S skytrack -G skytrack
USER skytrack

# Single fat jar from the build stage (exclude the *-plain.jar Boot also emits).
COPY --from=build --chown=skytrack:skytrack /workspace/skytrack/target/*-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Actuator liveness probe; relies on management.endpoints exposure in the prod profile.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

> **If `maven:3.9-eclipse-temurin-25` does not resolve:** check Docker Hub for the current Temurin-25 Maven tag (e.g. `maven:3-eclipse-temurin-25`) and pin that. The build in Step 2 fails fast if the tag is wrong.

> **Why `*-SNAPSHOT.jar` and not `app.jar` glob:** Spring Boot's `package` produces both the executable `demo-0.0.1-SNAPSHOT.jar` and a thin `demo-0.0.1-SNAPSHOT-plain.jar`. The `*-SNAPSHOT.jar` pattern with the `-plain` excluded by ordering — verify in Step 2 that the copied jar is the executable one (it's the larger of the two).

### Step 2: Build the image and verify it succeeds

Run: `docker build -t skytrack:local .`
Expected: build completes; final line `naming to docker.io/library/skytrack:local`.

> If both jars match the glob and the wrong one is copied, the container will fail at Step B3 with `no main manifest attribute`. Fix by making the COPY explicit: `COPY --from=build .../target/demo-0.0.1-SNAPSHOT.jar /app/app.jar`.

### Step 3: Check the image size

Run: `docker images skytrack:local --format '{{.Size}}'`
Expected: a value to record. JRE 25 alpine + fat jar typically lands ~220–280 MB. **Note:** the roadmap's <200 MB target assumed JRE 17; record the actual number and treat sub-200 MB as a stretch goal (achievable later via `jlink`, deferred — see Out of Scope).

### Step 4: Commit

```bash
git add Dockerfile
git commit -m "build: multi-stage Dockerfile on JRE 25 alpine with actuator healthcheck"
```

---

## Task B3: Container smoke test (boots without AWS)

Prove the image *runs* and serves a liveness signal even with no AWS reachable. We run the **default profile** here (not `prod`), because `prod` eagerly resolves SQS queue URLs against real AWS at startup; the default profile is replay/disabled and boots offline. This validates the image mechanics (jar, JRE, port, actuator), not AWS wiring — AWS wiring is validated live in Part D.

**Files:** none (manual verification task).

### Step 1: Run the container

```bash
docker run --rm -d --name skytrack-smoke -p 8080:8080 skytrack:local
```

### Step 2: Wait for health, then probe

```bash
# give it up to ~60s to come up
for i in $(seq 1 20); do
  sleep 3
  curl -fs http://localhost:8080/actuator/health && break
done
```

Expected: `{"status":"UP"}` (or a JSON object whose top-level `status` is `UP`).

### Step 3: Tear down

```bash
docker stop skytrack-smoke
```

> If health never goes UP, inspect logs: `docker logs skytrack-smoke`. A `no main manifest attribute` error means the wrong jar was copied (see Task B2 Step 2).

### Step 4: Commit (only if you adjusted the Dockerfile during smoke testing)

```bash
git add Dockerfile && git commit -m "build: fix Dockerfile per smoke test"
```

---

# PART C — Infrastructure as Code (CloudFormation)

## Task C1: CloudFormation template

One declarative template that mirrors [`init-aws.sh`](../../localstack/init-aws.sh) and adds the compute + identity needed for a real deploy.

**Files:**
- Create: `infra/skytrack.cfn.yml`

### Step 1: Write the template

**`infra/skytrack.cfn.yml`:**

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Description: >
  SkyTrack Phase 1 — full backing infrastructure: 2 SQS FIFO queues, 1 DynamoDB
  table (no GSI, matches the application's scan-based access), 1 S3 history bucket,
  1 ECR repo, an IAM instance role scoped to those resources, a security group,
  and a t3.micro EC2 host that pulls the image from ECR and runs the prod profile.
  Designed to stay within the AWS Free Tier.

Parameters:
  KeyPairName:
    Type: String
    Default: ''
    Description: Optional EC2 key pair for SSH. Leave blank to disable SSH access.
  SshCidr:
    Type: String
    Default: 0.0.0.0/0
    Description: CIDR allowed to reach SSH (only used if KeyPairName is set).
  InstanceType:
    Type: String
    Default: t3.micro
    AllowedValues: [t2.micro, t3.micro]
    Description: Free-tier-eligible instance type.
  ImageTag:
    Type: String
    Default: latest
    Description: ECR image tag the instance should run.
  OpenSkyClientId:
    Type: String
    Default: ''
    NoEcho: true
    Description: OpenSky OAuth2 client id (free account).
  OpenSkyClientSecret:
    Type: String
    Default: ''
    NoEcho: true
    Description: OpenSky OAuth2 client secret (free account).
  LatestAmiId:
    Type: AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>
    Default: /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64
    Description: Resolved automatically to the latest Amazon Linux 2023 AMI.

Conditions:
  HasKeyPair: !Not [!Equals [!Ref KeyPairName, '']]

Resources:

  # --- Messaging: 2 FIFO queues (mirror init-aws.sh) ---
  PositionsQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: skytrack-positions.fifo
      FifoQueue: true
      ContentBasedDeduplication: true
      VisibilityTimeout: 30

  AirportEventsQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: skytrack-airport-events.fifo
      FifoQueue: true
      ContentBasedDeduplication: true
      VisibilityTimeout: 30

  # --- State store: DynamoDB (icao24 HASH + sortKey RANGE, on-demand, TTL=ttl, no GSI) ---
  AircraftTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: skytrack-aircraft
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - AttributeName: icao24
          AttributeType: S
        - AttributeName: sortKey
          AttributeType: S
      KeySchema:
        - AttributeName: icao24
          KeyType: HASH
        - AttributeName: sortKey
          KeyType: RANGE
      TimeToLiveSpecification:
        AttributeName: ttl
        Enabled: true

  # --- Historical Parquet storage ---
  HistoryBucket:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: !Sub 'skytrack-history-${AWS::AccountId}'
      PublicAccessBlockConfiguration:
        BlockPublicAcls: true
        BlockPublicPolicy: true
        IgnorePublicAcls: true
        RestrictPublicBuckets: true

  # --- Container registry ---
  EcrRepo:
    Type: AWS::ECR::Repository
    Properties:
      RepositoryName: skytrack
      ImageScanningConfiguration:
        ScanOnPush: true
      LifecyclePolicy:
        LifecyclePolicyText: |
          {"rules":[{"rulePriority":1,"description":"keep last 5 images",
          "selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":5},
          "action":{"type":"expire"}}]}

  # --- IAM: instance role scoped to exactly the resources above ---
  InstanceRole:
    Type: AWS::IAM::Role
    Properties:
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal: { Service: ec2.amazonaws.com }
            Action: sts:AssumeRole
      Policies:
        - PolicyName: skytrack-app
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Sid: Sqs
                Effect: Allow
                Action:
                  - sqs:SendMessage
                  - sqs:ReceiveMessage
                  - sqs:DeleteMessage
                  - sqs:GetQueueUrl
                  - sqs:GetQueueAttributes
                Resource:
                  - !GetAtt PositionsQueue.Arn
                  - !GetAtt AirportEventsQueue.Arn
              - Sid: Dynamo
                Effect: Allow
                Action:
                  - dynamodb:GetItem
                  - dynamodb:PutItem
                  - dynamodb:UpdateItem
                  - dynamodb:DeleteItem
                  - dynamodb:Query
                  - dynamodb:Scan
                  - dynamodb:BatchWriteItem
                Resource: !GetAtt AircraftTable.Arn
              - Sid: S3
                Effect: Allow
                Action:
                  - s3:GetObject
                  - s3:PutObject
                  - s3:ListBucket
                Resource:
                  - !GetAtt HistoryBucket.Arn
                  - !Sub '${HistoryBucket.Arn}/*'
              - Sid: EcrPull
                Effect: Allow
                Action:
                  - ecr:GetDownloadUrlForLayer
                  - ecr:BatchGetImage
                  - ecr:BatchCheckLayerAvailability
                Resource: !GetAtt EcrRepo.Arn
              - Sid: EcrAuth
                Effect: Allow
                Action: ecr:GetAuthorizationToken
                Resource: '*'

  InstanceProfile:
    Type: AWS::IAM::InstanceProfile
    Properties:
      Roles: [!Ref InstanceRole]

  # --- Network: allow inbound 80 (app), optional 22 (SSH) ---
  AppSecurityGroup:
    Type: AWS::EC2::SecurityGroup
    Properties:
      GroupDescription: SkyTrack app access
      SecurityGroupIngress:
        - IpProtocol: tcp
          FromPort: 80
          ToPort: 80
          CidrIp: 0.0.0.0/0
          Description: HTTP to the app

  SshIngress:
    Type: AWS::EC2::SecurityGroupIngress
    Condition: HasKeyPair
    Properties:
      GroupId: !Ref AppSecurityGroup
      IpProtocol: tcp
      FromPort: 22
      ToPort: 22
      CidrIp: !Ref SshCidr
      Description: SSH

  # --- Compute: t3.micro that pulls the image and runs the prod profile ---
  AppInstance:
    Type: AWS::EC2::Instance
    Properties:
      ImageId: !Ref LatestAmiId
      InstanceType: !Ref InstanceType
      IamInstanceProfile: !Ref InstanceProfile
      SecurityGroupIds: [!Ref AppSecurityGroup]
      KeyName: !If [HasKeyPair, !Ref KeyPairName, !Ref 'AWS::NoValue']
      Tags:
        - Key: Name
          Value: skytrack-app
      UserData:
        Fn::Base64: !Sub |
          #!/bin/bash
          set -euxo pipefail
          dnf install -y docker
          systemctl enable --now docker
          REGION=${AWS::Region}
          ACCOUNT=${AWS::AccountId}
          REPO="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/skytrack:${ImageTag}"
          aws ecr get-login-password --region "$REGION" \
            | docker login --username AWS --password-stdin "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
          # The image may be pushed slightly after the stack is created; retry the pull.
          for i in $(seq 1 40); do
            if docker pull "$REPO"; then break; fi
            echo "image not ready yet (attempt $i), sleeping..."; sleep 15
          done
          docker run -d --restart unless-stopped --name skytrack -p 80:8080 \
            -e SPRING_PROFILES_ACTIVE=prod \
            -e AWS_REGION="$REGION" \
            -e DYNAMODB_TABLE=skytrack-aircraft \
            -e S3_BUCKET="skytrack-history-$ACCOUNT" \
            -e OPENSKY_CLIENT_ID='${OpenSkyClientId}' \
            -e OPENSKY_CLIENT_SECRET='${OpenSkyClientSecret}' \
            "$REPO"

Outputs:
  AppUrl:
    Description: Public URL of the SkyTrack app
    Value: !Sub 'http://${AppInstance.PublicDnsName}'
  HealthUrl:
    Description: Actuator health endpoint
    Value: !Sub 'http://${AppInstance.PublicDnsName}/actuator/health'
  SampleEndpoint:
    Description: Example airport status endpoint
    Value: !Sub 'http://${AppInstance.PublicDnsName}/airports/ORD/status'
  EcrRepositoryUri:
    Description: Push the image here before/just after stack creation
    Value: !GetAtt EcrRepo.RepositoryUri
  InstancePublicIp:
    Value: !GetAtt AppInstance.PublicIp
```

> **Security note (acceptable for a throwaway demo):** OpenSky credentials are passed as `NoEcho` stack parameters and rendered into EC2 user-data, where they're visible to anyone with instance access. Fine for a $0 demo; for anything durable, move them to SSM Parameter Store / Secrets Manager (noted in Out of Scope).

### Step 2: Validate the template

Run: `aws cloudformation validate-template --template-body file://infra/skytrack.cfn.yml`
Expected: prints the parsed parameters with no error. (Requires AWS CLI configured; if not yet configured, do it in Task D0.)

> Optional stronger check if you have it: `cfn-lint infra/skytrack.cfn.yml` — expect no `E` (error) findings. Install via `pip install cfn-lint`.

### Step 3: Commit

```bash
git add infra/skytrack.cfn.yml
git commit -m "infra: CloudFormation for SQS/DynamoDB/S3/ECR/IAM/EC2 matching the app's real schema"
```

---

# PART D — Deploy scripts & one-time deployment

## Task D0: Prerequisites checklist (manual, no commit)

Confirm before deploying:
- [ ] AWS account is **< 12 months old** (Free Tier eligible) — otherwise EC2/other charges may apply.
- [ ] `aws sts get-caller-identity` returns your account (CLI configured).
- [ ] `docker version` works locally.
- [ ] A free OpenSky account exists; you have `clientId` / `clientSecret`.
- [ ] Region chosen (default `us-east-1`).

## Task D1: `deploy.sh`

**Files:**
- Create: `infra/deploy.sh`

Because the ECR repo is created *by* the stack, the deploy is ordered as: create the stack (instance user-data retries the image pull) → build & push the image → instance picks it up on a subsequent retry.

### Step 1: Write the script

**`infra/deploy.sh`:**

```bash
#!/usr/bin/env bash
set -euo pipefail

# --- Config (override via env) ---
STACK="${STACK:-skytrack}"
REGION="${AWS_REGION:-us-east-1}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
: "${OPENSKY_CLIENT_ID:?set OPENSKY_CLIENT_ID}"
: "${OPENSKY_CLIENT_SECRET:?set OPENSKY_CLIENT_SECRET}"
KEY_PAIR="${KEY_PAIR:-}"   # optional

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
ECR_URI="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/skytrack"

echo ">> 1/4 Creating/updating CloudFormation stack '$STACK'..."
aws cloudformation deploy \
  --region "$REGION" \
  --stack-name "$STACK" \
  --template-file "$ROOT/infra/skytrack.cfn.yml" \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    ImageTag="$IMAGE_TAG" \
    OpenSkyClientId="$OPENSKY_CLIENT_ID" \
    OpenSkyClientSecret="$OPENSKY_CLIENT_SECRET" \
    KeyPairName="$KEY_PAIR"

echo ">> 2/4 Logging in to ECR..."
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"

echo ">> 3/4 Building and pushing image ($ECR_URI:$IMAGE_TAG)..."
docker build -t "skytrack:$IMAGE_TAG" "$ROOT"
docker tag "skytrack:$IMAGE_TAG" "$ECR_URI:$IMAGE_TAG"
docker push "$ECR_URI:$IMAGE_TAG"

echo ">> 4/4 Stack outputs:"
aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
  --query 'Stacks[0].Outputs' --output table

echo "Done. The instance retry-pulls the image; health may take a few minutes."
```

### Step 2: Make it executable & commit

```bash
chmod +x infra/deploy.sh
git add infra/deploy.sh
git commit -m "infra: deploy.sh — create stack, build+push image to ECR, print outputs"
```

## Task D2: `teardown.sh`

**Files:**
- Create: `infra/teardown.sh`

S3 buckets and ECR repos with content block stack deletion, so empty them first. This script is what guarantees the **$0** outcome.

### Step 1: Write the script

**`infra/teardown.sh`:**

```bash
#!/usr/bin/env bash
set -euo pipefail
STACK="${STACK:-skytrack}"
REGION="${AWS_REGION:-us-east-1}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"

echo ">> Emptying S3 bucket (ignore errors if already gone)..."
aws s3 rm "s3://skytrack-history-$ACCOUNT" --recursive --region "$REGION" || true

echo ">> Deleting ECR images (ignore errors if already gone)..."
aws ecr batch-delete-image --region "$REGION" --repository-name skytrack \
  --image-ids "$(aws ecr list-images --region "$REGION" --repository-name skytrack \
    --query 'imageIds' --output json)" 2>/dev/null || true

echo ">> Deleting stack '$STACK'..."
aws cloudformation delete-stack --stack-name "$STACK" --region "$REGION"
aws cloudformation wait stack-delete-complete --stack-name "$STACK" --region "$REGION"
echo "Stack deleted. Verify in the console that no resources remain. Bill: \$0."
```

### Step 2: Make it executable & commit

```bash
chmod +x infra/teardown.sh
git add infra/teardown.sh
git commit -m "infra: teardown.sh — empty S3/ECR then delete stack to guarantee \$0"
```

## Task D3: One-time live deploy + smoke test + capture URL (manual)

**Files:** none (runbook execution).

### Step 1: Deploy

```bash
export AWS_REGION=us-east-1
export OPENSKY_CLIENT_ID=...      # from your free OpenSky account
export OPENSKY_CLIENT_SECRET=...
./infra/deploy.sh
```

Expected: stack reaches `CREATE_COMPLETE`; outputs table prints `AppUrl`, `HealthUrl`, `SampleEndpoint`.

### Step 2: Wait for the app, then smoke-test the live URL

```bash
HEALTH=$(aws cloudformation describe-stacks --stack-name skytrack \
  --query "Stacks[0].Outputs[?OutputKey=='HealthUrl'].OutputValue" --output text)
for i in $(seq 1 30); do
  sleep 20
  echo "attempt $i: $(curl -fs "$HEALTH" || echo 'not up yet')"
  curl -fs "$HEALTH" | grep -q '"status":"UP"' && { echo "LIVE"; break; }
done
```

Expected within ~5–8 min (instance boot + Docker install + image pull + Spring start): `{"status":"UP"}`. Then hit the sample endpoint:

```bash
SAMPLE=$(aws cloudformation describe-stacks --stack-name skytrack \
  --query "Stacks[0].Outputs[?OutputKey=='SampleEndpoint'].OutputValue" --output text)
curl -s "$SAMPLE" | head
```

Expected: JSON from `/airports/ORD/status`. (Disruption fields may be sparse until live OpenSky data accumulates — that's expected on a fresh boot.)

> **If health never comes up:** SSH in (if you set a key pair) or use EC2 Instance Connect, then `sudo docker logs skytrack` and `sudo cat /var/log/cloud-init-output.log`. Most common causes: image not pushed yet (wait for the retry loop), or OpenSky creds rejected (app still boots; OpenSky polling logs warnings).

### Step 3: Record the live URL for the resume

Save the `AppUrl` (e.g. `http://ec2-...compute.amazonaws.com`) — this is what makes **"deployed to AWS EC2"** honest. Take a screenshot of the live `/airports/ORD/status` response.

### Step 4 (optional but recommended): Tear down to lock in $0

```bash
./infra/teardown.sh
```

> Leaving a t3.micro running 24/7 is Free-Tier-covered for 12 months (750 h/mo), but tearing down after capturing the URL/screenshot removes all doubt. Re-deploy anytime with `./infra/deploy.sh`.

---

# PART E — Documentation & wrap-up

## Task E1: Deployment runbook

**Files:**
- Create: `docs/DEPLOYMENT.md`

### Step 1: Write the runbook

Capture: prerequisites, the `deploy.sh` / `teardown.sh` usage, the env vars, the cost model (what's Free Tier and the one non-free risk = exceeding 750 EC2 hours or running past 12 months), and the recorded live URL. Cross-link this plan.

### Step 2: Update the roadmap checkboxes

In `docs/SkyTrack_Phase1_Roadmapv1.md`, mark **6.6** (prod profile), **6.7** (Dockerfile), **6.8** (IaC) complete, and note that the deploy was executed (or is ready) per this plan.

### Step 3: Commit

```bash
git add docs/DEPLOYMENT.md docs/SkyTrack_Phase1_Roadmapv1.md
git commit -m "docs: deployment runbook + roadmap 6.6-6.8 complete"
```

## Task E2: Full regression

### Step 1: Run the whole suite

Run: `cd skytrack && mvn clean test`
Expected: BUILD SUCCESS, including the new `ProdProfileBindingTest`. (Integration tests needing Docker/LocalStack run as before.)

### Step 2: Final commit (if any fixups)

```bash
git add -A && git commit -m "chore: chunk 8 final regression fixups"
```

---

## Chunk 8 Deliverables

| Deliverable | Tasks | Priority |
|---|---|---|
| AeroAPI-free real-AWS `application-prod.yml` | A1 | Critical |
| Prod-profile invariant test (offline) | A2 | High |
| `.dockerignore` + multi-stage Dockerfile (JRE 25) | B1–B2 | Critical |
| Container smoke test | B3 | High |
| CloudFormation: SQS/DynamoDB/S3/ECR/IAM/SG/EC2 | C1 | Critical |
| `deploy.sh` (stack + ECR push) | D1 | Critical |
| `teardown.sh` ($0 guarantee) | D2 | Critical |
| Live one-time deploy + smoke test + captured URL | D3 | High |
| Deployment runbook + roadmap updates | E1 | Medium |
| Full regression green | E2 | Critical |

> ✅ **Checkpoint:** `mvn clean test` is green; `docker build` produces a runnable image; `aws cloudformation validate-template` passes; `./infra/deploy.sh` brings up a public `AppUrl` whose `/actuator/health` reports `UP` and whose `/airports/ORD/status` returns JSON — all on live OpenSky + live weather with AeroAPI disabled. `./infra/teardown.sh` returns the account to **$0**.

## Out of Scope (deferred)

- **`jlink` custom runtime** to push the image under 200 MB (JRE 25 alpine + fat jar exceeds the old JRE-17-era target).
- **Secrets Manager / SSM Parameter Store** for OpenSky creds (currently `NoEcho` params → user-data).
- **HTTPS / domain / ALB** (demo is plain HTTP on the instance).
- **CI/CD pipeline** (GitHub Actions build-and-push to ECR).
- **Lambda pollers + API Gateway** (roadmap's aspirational serverless split — the monolith on EC2 covers Phase 1).
- **DynamoDB GSI for `findByCallsign`** (still a scan; tracked from Chunk 7).
- **Auto Scaling / multi-AZ** (single t3.micro is intentional for Free Tier).
```
