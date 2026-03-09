# Recording and Replaying OpenSky Data

This guide covers how to capture live OpenSky API responses to disk and replay them locally for development and testing.

---

## Recording

The recorder polls `GET /api/states/all` every 30 seconds for 185 minutes (370 polls total) and saves each response as a JSON file named by Unix epoch second.

### 1. (Optional) Set credentials for a higher rate limit

Anonymous access enforces a 10-second minimum between requests. Authenticated access drops that to 5 seconds.

```bash
export OPENSKY_USERNAME=your_username
export OPENSKY_PASSWORD=your_password
```

If you skip this step the recorder still works — it just logs that it's using anonymous access.

### 2. Run the recorder

From the `skytrack/` directory:

```bash
cd skytrack
mvn exec:java -Dexec.mainClass="skytrack.demo.client.OpenSkyRecorder"
```

By default, files are written to `data/recorded-opensky/` (relative to wherever you run the command). To use a different directory pass it as an argument:

```bash
mvn exec:java -Dexec.mainClass="skytrack.demo.client.OpenSkyRecorder" \
  -Dexec.args="../data/recorded-opensky"
```

### 3. Verify output

Each successful poll produces a file like:

```
data/recorded-opensky/
  1741478400.json
  1741478430.json
  1741478460.json
  ...
```

The console logs progress as each file is saved:

```
Poll 1/370 saved: 1741478400.json
Poll 2/370 saved: 1741478430.json
```

---

## Replaying

The replay client reads the recorded files in sorted (chronological) order, serving one file per `fetchPositions()` call made by `FlightPollingService`.

### 1. Point the app at your recorded files

In `application-local.yml` (already the default for local development):

```yaml
opensky:
  mode: replay
  replay-dir: ./data/recorded-opensky/
```

Adjust `replay-dir` if your files are in a different location.

### 2. Start the application

```bash
cd skytrack
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The app will log how many files were loaded:

```
Replay client loaded 370 files from ./data/recorded-opensky/
```

Each polling interval it processes the next file in sequence:

```
Replayed file 1/370: 1741478400.json — 8341 positions
Replayed file 2/370: 1741478430.json — 8297 positions
```

When all files are exhausted it logs `Replay complete — no more files` and returns empty results.

---

## Switching to live data

To use the live OpenSky API instead of recorded files, set the mode in your profile's config:

```yaml
opensky:
  mode: live
```

Or use the `prod` profile (`application-prod.yml`) which is configured for live access.
