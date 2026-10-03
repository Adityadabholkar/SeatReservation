# Seat Reservation Service

A small Spring Boot service that sells assigned seats for a show. The goal is simple to state and annoying to get right: never sell the same seat twice, never let a user go over their seat limit, and never create two reservations for one retried request, even when a lot of people hit the same seat at once.

Stack: Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, Micrometer (Prometheus format), Docker.

Live URL: `https://seat-reservation-dl0r.onrender.com`

## Running it

You need Docker.

```
docker compose up --build
```

The app comes up on http://localhost:8080 with its own Postgres. Check `http://localhost:8080/health/ready`; it should say UP.

To run from an IDE instead, start only the database with `docker compose up -d db` (publish port 5433 for it in the compose file) and set `DB_URL=jdbc:postgresql://localhost:5433/seats`, `DB_USER=seats`, `DB_PASSWORD=seats`.

Settings, all environment variables:

| Variable | Default | Notes |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local postgres | Or set `DATABASE_URL` in `postgres://user:pass@host:port/db` form (what Render gives you) |
| `ADMIN_TOKEN` | `admin-secret` | Change it anywhere that is not your laptop |
| `DB_POOL_SIZE` | 20 | Keep it small on free-tier databases |
| `LOG_REQUESTS` | true | One JSON log line per request |
| `PORT` | 8080 | |

## Auth

Send `Authorization: Bearer <token>`.

- `POST /shows` needs the admin token.
- Reserve and cancel take any other token as a user. The token itself is the user id (letters, digits and `_ . : @ -`, up to 64 characters). So `Bearer user-42` is user `user-42`.
- The user id is never read from the request body. If a body contains `user_id`, it is ignored. The admin token cannot be used to reserve.

The tokens are not signed. That keeps testing simple, and replacing it with real JWT checking would only touch `UserAuthInterceptor`. I'd call that out as a shortcut, not a design.

## Endpoints

Create a show (admin):
```
POST /shows
{ "name": "friday-night", "seats": ["A1","A2","A3"], "price_paise": 25000, "per_user_limit": 4 }
```
`per_user_limit` is optional and defaults to 4. Returns 201 with the show id and every seat `available`.

Reserve:
```
POST /shows/{id}/reserve
Idempotency-Key: abc-123          (or "idempotency_key" in the body)
{ "seats": ["A12", "A13"] }
```
- 201: a new reservation, `status: confirmed`.
- 200 with header `Idempotent-Replay: true`: the same key and body was already processed, so this returns the original reservation.
- 409 with one of `SEAT_TAKEN`, `PER_USER_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED`.
- 400 for bad input or a seat that does not exist, 404 for an unknown show.

Cancel:
```
POST /reservations/{id}/cancel
```
Owner only (403 otherwise). Cancelling twice is harmless and returns 200.

Show state:
```
GET /shows/{id}            per-seat status list plus counts
GET /shows/{id}?seats=false   counts only
```
The response has `total_seats`, `available`, `held`, `confirmed` (also grouped under `counts`) and, unless disabled, a `seats` list of `{seat, status}`.

Decisions you should know about:

- A request for several seats is all or nothing. Asking for `["A12","A13"]` when A13 is taken fails with 409 and changes nothing.
- A reservation is confirmed immediately. There are no timed holds, so `held` is always 0 right now. It stays in the schema and in the invariant for when holds are added.
- A retry of a successful request returns 200, not 201, so each seat produces exactly one 201.
- If a request has no idempotency key it is treated as a new request each time.
- Money is stored as `BIGINT` paise. `amount_paise` is price times number of seats.

## Health, metrics, logs

- `GET /health/live`: process is up, does not touch the database.
- `GET /health/ready`: runs a real query and returns 503 if the database cannot be reached.
- `GET /metrics`: Prometheus text format.
  - `reservations_confirmed_total`
  - `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_key_reused"}`
  - `reservations_cancelled_total`
  - `seats_available`, `seats_held`, `seats_confirmed`, each labelled with `show_id`. These read the database at scrape time, so they always agree with the API.
  - the usual JVM, Tomcat, Hikari pool and request latency metrics
- Logs are JSON, one line per request, with `requestId`, method, path, status, duration and user id. A caller can send `X-Request-Id`; otherwise one is generated. It is returned in the response header and in error bodies.

The readiness check and the seat gauges use a separate two-connection pool, so they keep answering when the main pool is busy with a burst.

## The burst script

```
java tools/Burst.java http://localhost:8080
java tools/Burst.java https://<your-service>.onrender.com <ADMIN_TOKEN>
```
Needs JDK 21 or newer. `./burst.sh` and `make burst` do the same thing, and `burst.sh` falls back to running in Docker if there is no JDK (on Windows use Git Bash or WSL, or just run the `java` command).

It creates two fresh shows and runs:

1. A hot-seat storm: 500 different users each try the same seat, for 5 seats at once.
2. A stampede: many users, 80% of them aimed at the first 20 seats, about 10% sending a duplicate request with the same key.
3. One user firing 10 parallel reserves against a limit of 4.
4. The same idempotency key sent 50 times in parallel, then once more with different seats.
5. A spoofed `user_id`, a cancel by the wrong user, a cancel by the owner, a rebook of the released seat, and a second cancel.

While that runs it keeps reading the show state and checks `available + held + confirmed == total_seats`. At the end it prints the outcome counts, compares what the clients saw against the show state, compares the metrics against the API, and exits non-zero if any check failed.

Settings (environment variables): `BURST_REQUESTS` (default 20000), `BURST_SEATS` (5000), `BURST_CONCURRENCY` (400), `HOT_SEATS` (5), `HOT_CONTENDERS` (500). Every run uses fresh user names and idempotency keys, so it can be run repeatedly against the same database. On a free-tier host start with something like `BURST_REQUESTS=3000 BURST_CONCURRENCY=100`.

Result of a local run (5000 requests, concurrency 300, app and Postgres in Docker on my machine):

```
201 confirmed                 973
200 idempotent-replay         138
409 seat_taken               6964
409 per_user_limit_exceeded     6
409 idempotency_key_reused      1
5xx                             0
client errors                   0
double-sold seats               0
available + held + confirmed == total on both shows
metrics counters and gauges matched the API
ALL CHECKS PASSED
```


Result against the deployed service (free tier, 3000 requests, concurrency 100). The free instance is slow, about 25-40 requests a second, so this is a smaller run than the local one:

```
201 confirmed                 620
200 idempotent-replay         111
409 seat_taken               5144
409 per_user_limit_exceeded     6
409 idempotency_key_reused      1
5xx                             0
client errors                   0
double-sold seats               0
available + held + confirmed == total on both shows
metrics counters and gauges matched the API
ALL CHECKS PASSED
```

There is also a Postman collection, `seat-reservation.postman_collection.json`, that checks each rule one request at a time. It cannot prove anything about races, which is what the burst script is for.

## Deploying (Render)

1. Push the repo to GitHub.
2. In Render choose New, then Blueprint, and pick the repo. `render.yaml` creates a Docker web service and a free Postgres database.
3. Copy the generated `ADMIN_TOKEN` from the service's Environment tab.
4. Logs are on the service's Logs tab and metrics are at `/metrics`.

The free tier sleeps when idle, so the first request after a pause can take up to a minute. The free database has a small connection limit, which is why the blueprint sets `DB_POOL_SIZE=10`.

## Code layout

```
controller/   HTTP only, no business logic
service/      interfaces
service/impl/ the business rules (ReservationServiceImpl is the important one)
repository/   SQL via JdbcTemplate; the conditional UPDATEs live here
config/ web/  datasources, auth interceptors, request-id filter, error handler
metrics/      counters and database-backed gauges
db/migration  Flyway schema
tools/        Burst.java
```

I used JdbcTemplate with plain SQL instead of JPA on purpose. The guarantees depend on exact statements (a conditional UPDATE, an INSERT with ON CONFLICT), and I wanted those visible instead of hidden behind an ORM.
