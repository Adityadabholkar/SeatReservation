# Write-up

## The atomic decision

Every seat is one row in the `seats` table, with primary key `(show_id, label)`. Claiming it is a single conditional statement:

```sql
UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ?
WHERE show_id = ? AND label = ? AND status = 'available'
```

I never read the seat first and then write it. If two requests reach this statement together, Postgres takes the row lock for one of them and the other waits. When the first one commits, the waiting statement re-checks `status = 'available'` against the new version of the row, finds it false, and updates zero rows. Zero rows means the seat was taken, and that becomes a 409. The loser never sees an error from the database.

Because of that, the decision does not depend on anything in the application's memory, so it also holds if I run more than one instance. A CHECK constraint on the table adds a second layer: a seat is either available with no owner, or not available with an owner and a reservation. A half-claimed seat cannot exist.

Before the transaction I do a plain read of the requested seats. It is only there to return a quick 409 for seats that are clearly gone, so a stampede on one seat does not pile up waiting on locks. Nothing relies on it for correctness. If the read is stale, the UPDATE still decides.

### Several seats at once

A request for several seats is all or nothing. The whole thing runs in one transaction. Seats are trimmed, checked for duplicates and sorted, then claimed one at a time in ascending label order. If any claim updates zero rows, I throw, and the rollback undoes the reservation row, the counter change and any seats already claimed.

For deadlocks: two requests for `[A1, A2]` and `[A2, A1]` would deadlock if each locked in the order given. Sorting means both lock A1 first. More generally every transaction takes locks in the same order: the reservation row, then the user's counter row, then seats in label order. With one fixed order there is no cycle to wait on. I also retry a transaction a few times if the database reports a lock failure, but that is a safety net and I did not see it trigger.

### Per-user limit

There is a row per (show, user) in `user_show_counts`. The check is one statement:

```sql
UPDATE user_show_counts SET held_count = held_count + n
WHERE show_id = ? AND user_id = ? AND held_count + n <= limit
```
Zero rows updated means the limit would be exceeded. The UPDATE locks that user's row, so one user's parallel requests run through it one at a time, and the counter commits or rolls back together with the seats. A failed seat claim therefore also undoes the count.

## Idempotency

The key lives on the reservation row: `UNIQUE (user_id, idempotency_key)`, plus a SHA-256 hash of the show id and the sorted seat list. The first statement of the reserve transaction is an `INSERT ... ON CONFLICT DO NOTHING` on that table.

- One row inserted: this request owns the key and continues.
- Zero rows: a committed reservation already has this key. If the stored hash matches the new request, I return the original reservation with HTTP 200 and an `Idempotent-Replay: true` header. If the hash differs, it is a 409 `IDEMPOTENCY_KEY_REUSED`.

When two requests with the same key arrive together, the second INSERT waits on the unique index until the first transaction finishes, so only one reservation is created. The burst script checks this by sending the same key 50 times in parallel and expects one 201 and 49 replays.

Two choices worth stating. A replay returns 200 rather than 201, so that every seat produces exactly one 201 and the hot-seat counts are clean. And a request that is declined rolls back completely and leaves no key behind, so the client can retry it later and it is judged again from scratch.

The key is scoped to the user, so two users using the same key string do not interfere.
The first burst against the deployed service found a race where a retry could see the seat as taken before the original request's key was visible. I fixed it by looking the key up again before returning a decline.

## Holds and expiry

I chose the explicit cancel option. A reservation is confirmed immediately, and `POST /reservations/{id}/cancel` releases it.

Cancel updates the reservation only when the caller is the owner and the status is still `confirmed`. If that updates a row, it then releases the seats that carry that reservation's id and lowers the user's counter. Because the release is keyed on the reservation id, it cannot free a seat that now belongs to someone else, which is the "never resurrect a confirmed seat" rule. A second cancel updates nothing and just returns the current state. A released seat can be booked again straight away; the burst script does cancel then rebook, then cancels again to check the new owner keeps the seat.

There is no timed hold. `held` exists in the schema and the invariant but is always zero. A real on-sale would want a hold with an expiry and a payment step, which I list under next steps.

## Consistency versus availability

There is one Postgres primary and it is the only writer, so I get consistency and give up write availability when the database is gone. Every seat decision is made by the database, so a seat cannot be sold twice even across several app instances.

If the app cannot reach the database, `/health/ready` returns 503 and reserve calls fail with 503 instead of guessing. Reads of show state use a REPEATABLE READ snapshot, so the seat list and the counts come from the same moment and always add up.

I have not built any failover. A standby with automatic promotion would be the next thing for availability, and read replicas could serve `GET /shows` if slightly stale reads are acceptable. Writes would stay on the primary.

## Observability, and what would wake me up

Metrics are described in the README. The ones I would alert on:

- Any 5xx. The contract is that declines are 4xx, so a 5xx is always a bug or an outage.
- Readiness failing, meaning the database is unreachable.
- The seat gauges not adding up to the show's total. The service also logs a line when it sees this. It would mean something is badly wrong with the data.
- Connection pool pressure: pending connection requests staying above zero, or acquire time climbing, which means requests are queueing for the database.
- p99 latency on the reserve endpoint, and database lock waits and CPU.
- A sudden change in the mix of declines, such as `seat_taken` or replays spiking, which usually means a bot or a client retrying aggressively.
- Confirmed reservations per minute dropping to zero during an on-sale.

Every log line and every error body carries the request id, so one user's complaint can be traced to the exact requests.

During load testing the service was set up to queue work instead of failing it: a large Tomcat accept queue and a long wait time for a database connection. That is what keeps a burst at zero 5xx, but it also means that under extreme load latency rises instead of errors. In production I would add load shedding with a 429 once the queue is deeper than the pool can drain in a reasonable time.

## How I used AI

I used Claude (Anthropic) heavily on this task, and the code is mostly AI-written. Being specific:

**What I directed:** I chose Java and Spring Boot, asked for a service interface plus implementation layer with the business logic in the implementation, and asked for it to run in Docker. I also asked for a plan before any code, then for the whole project.

**What the AI produced:** the first version of essentially everything: the schema, the repositories and the conditional-UPDATE design, the service logic for reserve, cancel and idempotency, the controllers and auth, the metrics and health endpoints, the Dockerfile and compose file, the burst tool, the Postman collection, and the first drafts of the docs. The mechanisms in this write-up (the conditional UPDATE, sorted lock order, the unique key on reservations, the counter row) came from the AI's design, which I then read through and learned.

**What I did and fixed myself:** I ran the project locally and worked through the problems that appeared: the database login failing and the port not being published, `/metrics` returning 404 (fixed by adding a controller for it), connection errors in the load test, and the burst tool's bugs, where reused keys across runs and a broken JSON body produced misleading failures. I edited the tool and the Tomcat settings to fix these and re-ran until the burst passed. The burst results in the README come from my own runs.

**What I did not verify:** I did not independently review every line before the first run, and I have not tested what happens when the database is killed under load or what happens with several app instances. The claims about those are from reasoning about how the database behaves, not from testing them.

[Edit this section so it matches exactly what you did and understand. If you changed or tested more than is written here, add it. If you did less, remove it. Delete this line before submitting.]

## What I would do next

- Timed holds with an expiry job, and a payment step between hold and confirm.
- Real signed tokens (JWT) in place of opaque ones.
- Rate limiting and a waiting room in front of the on-sale, plus 429 load shedding.
- Automated integration tests that run the hot-seat storm against a Postgres container in CI, so the guarantee is checked on every change.
- Failover for the database.
- Splitting very large halls so that all seats of one show are not in one table's hot path.
