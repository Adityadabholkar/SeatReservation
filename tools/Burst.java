import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * One-command on-sale stampede. Run:  java tools/Burst.java <BASE_URL> [ADMIN_TOKEN]
 * Tunables (env): BURST_REQUESTS=20000 BURST_SEATS=5000 BURST_CONCURRENCY=1500 HOT_SEATS=5 HOT_CONTENDERS=500
 * Exit code 0 = every correctness check passed, 1 = something failed.
 */
public class Burst {
    static final AtomicBoolean shown = new AtomicBoolean(false);
    static HttpClient http;
    static String base;
    static String adminToken;
    static Semaphore permits;
    static final ConcurrentHashMap<String, LongAdder> errorKinds = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, LongAdder> outcomes = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, AtomicInteger> confirmedBySeat = new ConcurrentHashMap<>();
    static final AtomicLong confirmed201 = new AtomicLong();
    static final AtomicLong totalRequests = new AtomicLong();
    static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    static final String RUN = Long.toString(System.currentTimeMillis(), 36);

    record Resp(int status, String body, String replayHeader) {}
    record State(long total, long available, long held, long confirmed) {}

    static int envInt(String k, int d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : Integer.parseInt(v.trim());
    }

    public static void main(String[] args) throws Exception {
        base = (args.length > 0 ? args[0] : "http://localhost:8080").replaceAll("/+$", "");
        adminToken = args.length > 1 ? args[1] : System.getenv().getOrDefault("ADMIN_TOKEN", "admin-secret");
        int requests = envInt("BURST_REQUESTS", 20000);
        int seatCount = envInt("BURST_SEATS", 5000);
        int concurrency = envInt("BURST_CONCURRENCY", 400);
        int hotSeats = envInt("HOT_SEATS", 5);
        int hotContenders = envInt("HOT_CONTENDERS", 500);
        permits = new Semaphore(concurrency);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30)).build();

        System.out.println("== Seat reservation burst against " + base);
        System.out.printf("   requests=%d seats=%d concurrency=%d hotSeats=%d hotContenders=%d%n",
                requests, seatCount, concurrency, hotSeats, hotContenders);

        Resp ready = call("GET", "/health/ready", null, null, null, false);
        System.out.println("   readiness: HTTP " + ready.status());
        if (ready.status() != 200) { System.out.println("service not ready, aborting"); System.exit(2); }

        Map<String, Double> metricsBefore = scrape();

        String big = createShow("burst-big-" + System.currentTimeMillis(), seatCount, 25000L, 4);
        String small = createShow("burst-small-" + System.currentTimeMillis(), 100, 25000L, 4);
        System.out.println("   big show   = " + big + "\n   small show = " + small);

        // invariant poller (runs through every phase)
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong polls = new AtomicLong();
        Thread poller = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                for (String s : List.of(big, small)) {
                    State st = state(s, false);
                    polls.incrementAndGet();
                    if (st == null) { failures.add("poller: could not read show state"); continue; }
                    if (st.available + st.held + st.confirmed != st.total)
                        failures.add("INVARIANT BROKEN DURING LOAD " + s + " " + st);
                }
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            }
        });

        // ---- Phase A: hot-seat storm -------------------------------------------------
        System.out.println("\n-- Phase A: hot-seat storm (" + hotContenders + " users x " + hotSeats + " hot seats, simultaneous)");
        long t0 = System.nanoTime();
        List<Runnable> a = new ArrayList<>();
        for (int h = 1; h <= hotSeats; h++) {
            for (int u = 0; u < hotContenders; u++) {
                String user = "hot-" + h + "-" + u, seat = seat(h), key = "hot-" + h + "-" + u;
                a.add(() -> reserve(big, user, List.of(seat), key));
            }
        }
        runAll(a);
        report("Phase A", t0, a.size());
        for (int h = 1; h <= hotSeats; h++) {
            int w = confirmedBySeat.getOrDefault(big + "|" + seat(h), new AtomicInteger()).get();
            check(w == 1, "hot seat " + seat(h) + " has exactly one winner (got " + w + ")");
        }

        // ---- Phase B: full stampede ---------------------------------------------------
        System.out.println("\n-- Phase B: on-sale stampede (" + requests + " users, skewed to hot seats, ~10% duplicate-key retries)");
        t0 = System.nanoTime();
        Random rnd = new Random(42);
        List<Runnable> b = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String user = "user-" + i, key = "stampede-" + i;
            String seat = rnd.nextInt(100) < 80 ? seat(1 + rnd.nextInt(20)) : seat(1 + rnd.nextInt(seatCount));
            b.add(() -> reserve(big, user, List.of(seat), key));
            if (rnd.nextInt(100) < 10) b.add(() -> reserve(big, user, List.of(seat), key)); // same-key retry
        }
        runAll(b);
        report("Phase B", t0, b.size());

        // ---- Phase C: per-user limit under concurrency --------------------------------
        System.out.println("\n-- Phase C: one user fires 10 parallel reserves (limit = 4)");
        t0 = System.nanoTime();
        List<Runnable> c = new ArrayList<>();
        for (int i = 1; i <= 10; i++) { int n = i; c.add(() -> reserve(small, "limit-user", List.of(seat(n)), "limit-" + n)); }
        runAll(c);
        State sc = state(small, false);
        check(sc != null && sc.confirmed == 4, "limit-user ended with exactly 4 seats (show confirmed=" + (sc == null ? -1 : sc.confirmed) + ")");

        // ---- Phase D: idempotency ----------------------------------------------------
        System.out.println("\n-- Phase D: same idempotency key x50 parallel, then same key with different seats");
        Set<String> ids = ConcurrentHashMap.newKeySet();
        AtomicInteger created = new AtomicInteger(), replays = new AtomicInteger();
        List<Runnable> d = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            d.add(() -> {
                Resp r = reserve(small, "idem-user", List.of(seat(50)), "idem-key-1");
                if (r.status == 201) created.incrementAndGet();
                if (r.status == 200) replays.incrementAndGet();
                String id = field(r.body, "reservation_id");
                if (!id.isEmpty()) ids.add(id);
            });
        }
        runAll(d);
        check(created.get() == 1 && replays.get() == 49, "exactly one 201 and 49 replays (201s=" + created + ", 200s=" + replays + ")");
        check(ids.size() == 1, "all replays returned the same reservation_id (distinct=" + ids.size() + ")");
        Resp diff = reserve(small, "idem-user", List.of(seat(51)), "idem-key-1");
        check(diff.status == 409 && field(diff.body, "error").equals("IDEMPOTENCY_KEY_REUSED"),
                "same key + different seats -> 409 IDEMPOTENCY_KEY_REUSED (got " + diff.status + ")");

        // ---- Phase E: identity & cancel ----------------------------------------------
        System.out.println("\n-- Phase E: spoofed identity, foreign cancel, cancel + rebook");
        Resp alice = reserve(small, "alice", List.of(seat(60)), "alice-1");
        String aliceRes = field(alice.body, "reservation_id");
        Resp foreign = call("POST", "/reservations/" + aliceRes + "/cancel", "mallory", null, null, true);
        check(foreign.status == 403 || foreign.status == 404, "mallory cannot cancel alice's reservation (HTTP " + foreign.status + ")");
        Resp spoof = call("POST", "/shows/" + small + "/reserve", "mallory",
                "{\"seats\":[\"" + seat(61) + "\"],\"user_id\":\"alice\",\"idempotency_key\":\"" + RUN + "-spoof-1\"}", null, true);
        tally(spoof, small, List.of(seat(61)));
        check(spoof.status == 201 && field(spoof.body, "user_id").equals(RUN + "-mallory"), "spoofed body user_id is ignored; acts as token user 'mallory'");
        Resp cancel1 = call("POST", "/reservations/" + aliceRes + "/cancel", "alice", null, null, true);
        check(cancel1.status == 200 && field(cancel1.body, "status").equals("cancelled"), "alice cancels her own reservation");
        AtomicInteger sc60 = confirmedBySeat.get(small + "|" + seat(60));
        if (sc60 != null) sc60.decrementAndGet();
        errorKinds.forEach((k, v) -> System.out.println("   client error: " + k + "  x" + v.sum()));
        Resp bob = reserve(small, "bob", List.of(seat(60)), "bob-1");
        check(bob.status == 201, "released seat is re-bookable by bob (HTTP " + bob.status + ")");
        Resp cancel2 = call("POST", "/reservations/" + aliceRes + "/cancel", "alice", null, null, true);
        check(cancel2.status == 200, "second cancel is harmless (HTTP " + cancel2.status + ")");
        String seatsJson = call("GET", "/shows/" + small, null, null, null, false).body;
        Matcher m = Pattern.compile("\"seat\":\"" + seat(60) + "\",\"status\":\"(\\w+)\"").matcher(seatsJson);
        check(m.find() && m.group(1).equals("confirmed"), "cancel did not resurrect/steal bob's seat (still confirmed)");

        stop.set(true);
        poller.join();

        // ---- Results -----------------------------------------------------------------
        System.out.println("\n================ OUTCOME DISTRIBUTION (all phases) ================");
        new TreeMap<>(outcomes).forEach((k, v) -> System.out.printf("  %-40s %d%n", k, v.sum()));
        System.out.printf("  %-40s %d%n", "TOTAL requests", totalRequests.get());

        long fiveXX = outcomes.entrySet().stream().filter(e -> e.getKey().startsWith("5xx")).mapToLong(e -> e.getValue().sum()).sum();
        long clientErr = outcomes.entrySet().stream().filter(e -> e.getKey().startsWith("client-error")).mapToLong(e -> e.getValue().sum()).sum();
        check(fiveXX == 0, "zero 5xx responses (got " + fiveXX + ")");
        check(clientErr == 0, "zero client-side failures / timeouts (got " + clientErr + ")");

        System.out.println("\n================ RECONCILIATION ================");
        long expectedConfirmed = 0;
        for (String show : List.of(big, small)) {
            State s = state(show, false);
            long expected = confirmedBySeat.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(show + "|")).mapToLong(e -> e.getValue().get()).sum();
            expectedConfirmed += expected;
            System.out.printf("  show %s%n    total=%d available=%d held=%d confirmed=%d  (sum=%d)  client-observed winners=%d%n",
                    show, s.total, s.available, s.held, s.confirmed, s.available + s.held + s.confirmed, expected);
            check(s.available + s.held + s.confirmed == s.total, "available+held+confirmed == total_seats");
            check(s.confirmed == expected, "confirmed seats == number of 201s the clients saw (" + expected + ")");
        }
        long doubleSold = confirmedBySeat.values().stream().filter(v -> v.get() > 1).count();
        check(doubleSold == 0, "no seat was confirmed to two users (double-sold seats: " + doubleSold + ")");
        System.out.println("  invariant polls during load: " + polls.get());

        System.out.println("\n================ METRICS CROSS-CHECK ================");
        Map<String, Double> after = scrape();
        if (after.isEmpty()) {
            System.out.println("  /metrics not reachable - skipped");
        } else {
            double dConf = delta(after, metricsBefore, "reservations_confirmed_total");
            double dTaken = delta(after, metricsBefore, "reservations_declined_total|seat_taken");
            double dLimit = delta(after, metricsBefore, "reservations_declined_total|per_user_limit");
            double dReplay = delta(after, metricsBefore, "reservations_declined_total|idempotent_replay");
            System.out.printf("  confirmed_total +%.0f | declined seat_taken +%.0f | per_user_limit +%.0f | idempotent_replay +%.0f%n", dConf, dTaken, dLimit, dReplay);
            check((long) dConf == confirmed201.get(), "metric reservations_confirmed_total delta == client 201s (" + confirmed201.get() + ")");
            check((long) dTaken == count("409 seat_taken"), "metric declined{seat_taken} delta == client 409 SEAT_TAKEN (" + count("409 seat_taken") + ")");
            check((long) dLimit == count("409 per_user_limit_exceeded"), "metric declined{per_user_limit} delta == client 409 PER_USER_LIMIT (" + count("409 per_user_limit_exceeded") + ")");
            check((long) dReplay == count("200 idempotent-replay"), "metric declined{idempotent_replay} delta == client 200 replays (" + count("200 idempotent-replay") + ")");
            for (String show : List.of(big, small)) {
                double g = gauge(after, "seats_available", show);
                State s = state(show, false);
                System.out.printf("  gauge seats_available{show_id=%s} = %.0f  (API available = %d)%n", show, g, s.available);
                check((long) g == s.available, "gauge seats_available matches API for " + show);
            }
        }

        System.out.println("\n================ VERDICT ================");
        if (failures.isEmpty()) {
            System.out.println("  ALL CHECKS PASSED");
        } else {
            System.out.println("  " + failures.size() + " CHECK(S) FAILED:");
            new LinkedHashSet<>(failures).forEach(f -> System.out.println("   - " + f));
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    static String seat(int i) { return String.format("S%04d", i); }

    static long count(String label) {
        LongAdder a = outcomes.get(label);
        return a == null ? 0 : a.sum();
    }

    static void check(boolean ok, String msg) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + msg);
        if (!ok) failures.add(msg);
    }

    static void report(String phase, long t0, int n) {
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("   %s: %d requests in %.1fs (%.0f req/s)%n", phase, n, s, n / Math.max(s, 0.001));
    }

    static void runAll(List<Runnable> tasks) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Runnable t : tasks) {
                ex.submit(() -> { try { gate.await(); } catch (InterruptedException e) { return; } t.run(); });
            }
            gate.countDown();
        }
    }

    static String createShow(String name, int n, long price, int limit) {
        StringBuilder sb = new StringBuilder("{\"name\":\"" + name + "\",\"price_paise\":" + price
                + ",\"per_user_limit\":" + limit + ",\"seats\":[");
        for (int i = 1; i <= n; i++) { if (i > 1) sb.append(','); sb.append('"').append(seat(i)).append('"'); }
        sb.append("]}");
        Resp r = call("POST", "/shows", adminToken, sb.toString(), null, false);
        if (r.status != 201) { System.out.println("create show failed: HTTP " + r.status + " " + r.body); System.exit(2); }
        return field(r.body, "id");
    }

    static Resp reserve(String show, String user, List<String> seats, String key) {
        StringBuilder sb = new StringBuilder("{\"seats\":[");
        for (int i = 0; i < seats.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(seats.get(i)).append('"'); }
        sb.append("],\"idempotency_key\":\"").append(RUN + "-" + key).append("\"}");
        Resp r = call("POST", "/shows/" + show + "/reserve", user, sb.toString(), null, true);
        tally(r, show, seats);
        return r;
    }

    static void tally(Resp r, String show, List<String> seats) {
        totalRequests.incrementAndGet();
        String label;
        if (r.status == 201) {
            label = "201 confirmed";
            confirmed201.incrementAndGet();
            for (String s : seats) confirmedBySeat.computeIfAbsent(show + "|" + s, k -> new AtomicInteger()).incrementAndGet();
        } else if (r.status == 200) label = "200 idempotent-replay";
        else if (r.status == 409) label = "409 " + field(r.body, "error").toLowerCase();
        else if (r.status >= 500) label = "5xx (" + r.status + ")";
        else if (r.status <= 0) label = "client-error (no response)";
        else {
            label = r.status + " other";
            if (shown.compareAndSet(false, true)) System.out.println("   first unexpected response: HTTP " + r.status + " " + r.body);
        }
        outcomes.computeIfAbsent(label, k -> new LongAdder()).increment();
    }

    static Resp call(String method, String path, String token, String json, Map<String, String> headers, boolean limited) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120));
        if (token != null) b.header("Authorization", "Bearer " + (token.equals(adminToken) ? token : RUN + "-" + token));
        b.header("Content-Type", "application/json");
        if (headers != null) headers.forEach(b::header);
        b.method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
        try {
            if (limited) permits.acquire();
            try {
                HttpResponse<String> r;
                for (int attempt = 0; ; attempt++) {
                    try {
                        r = http.send(b.build(), BodyHandlers.ofString());
                        break;
                    } catch (java.net.ConnectException ce) {
                        if (attempt >= 4) throw ce;
                        Thread.sleep(50L * (attempt + 1));
                    }
                }
                return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Idempotent-Replay").orElse(""));
            } finally {
                if (limited) permits.release();
            }
        } catch (Exception e) {
            String kind = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            if (kind.length() > 140) kind = kind.substring(0, 140);
            errorKinds.computeIfAbsent(kind, k -> new LongAdder()).increment();
            return new Resp(0, String.valueOf(e), "");
        }
    }

    static State state(String show, boolean limited) {
        Resp r = call("GET", "/shows/" + show + "?seats=false", null, null, null, limited);
        if (r.status != 200) return null;
        return new State(num(r.body, "total_seats"), num(r.body, "available"), num(r.body, "held"), num(r.body, "confirmed"));
    }

    static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "";
    }

    static long num(String body, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)").matcher(body == null ? "" : body);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Parses Prometheus text into "name" or "name|reason" -> value (summed across other labels). */
    static Map<String, Double> scrape() {
        Resp r = call("GET", "/metrics", null, null, null, false);
        Map<String, Double> out = new HashMap<>();
        if (r.status != 200) return out;
        Pattern line = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*\\})?\\s+([-+0-9.eE]+|NaN)\\s*$");
        for (String l : r.body.split("\n")) {
            if (l.startsWith("#")) continue;
            Matcher m = line.matcher(l);
            if (!m.matches() || m.group(3).equals("NaN")) continue;
            double v = Double.parseDouble(m.group(3));
            String name = m.group(1), labels = m.group(2) == null ? "" : m.group(2);
            if (name.equals("reservations_declined_total")) {
                Matcher rm = Pattern.compile("reason=\"([^\"]+)\"").matcher(labels);
                if (rm.find()) out.merge(name + "|" + rm.group(1), v, Double::sum);
            } else if (name.equals("reservations_confirmed_total")) {
                out.merge(name, v, Double::sum);
            } else if (name.equals("seats_available")) {
                Matcher sm = Pattern.compile("show_id=\"([^\"]+)\"").matcher(labels);
                if (sm.find()) out.put(name + "|" + sm.group(1), v);
            }
        }
        return out;
    }

    static double delta(Map<String, Double> after, Map<String, Double> before, String key) {
        return after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0);
    }

    static double gauge(Map<String, Double> after, String name, String show) {
        return after.getOrDefault(name + "|" + show, -1.0);
    }
}
