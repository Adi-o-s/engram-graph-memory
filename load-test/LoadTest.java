import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP load test for a running Memora backend. Single file, JDK only:
 *
 *   java load-test/LoadTest.java [baseUrl] [virtualUsers] [requestsPerUser]
 *
 * Defaults: http://localhost:8080, 32 users, 50 requests each. Each virtual user signs in once and
 * then runs a mixed workload against the real API: full-text memory search, context building,
 * appending a message (with rule-based extraction) and writing a memory. It prints measured
 * per-operation counts, errors and latency percentiles. Nothing is estimated.
 *
 * Environment: MEMORA_LOADTEST_EMAIL (default alice@acme.test), MEMORA_LOADTEST_PASSWORD (default memora-demo).
 */
public class LoadTest {

    record Sample(String op, int status, double ms) {
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int users = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int perUser = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        String email = System.getenv().getOrDefault("MEMORA_LOADTEST_EMAIL", "alice@acme.test");
        String password = System.getenv().getOrDefault("MEMORA_LOADTEST_PASSWORD", "memora-demo");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(Math.max(4, users))).build();
        String token = field(send(http, "POST", base + "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").body(), "token");
        if (token == null) {
            throw new IllegalStateException("Login failed for " + email + "; is the backend running with demo data?");
        }
        String conversation = field(send(http, "POST", base + "/api/v1/conversations", token,
                "{\"title\":\"Load test " + UUID.randomUUID().toString().substring(0, 8) + "\"}").body(), "id");

        String[] queries = {"database", "pull requests", "migrations", "deploy", "testing", "framework"};
        String[] messages = {"We use Redis for caching.", "I prefer squash merges.", "Remember to rotate the staging keys.",
                "When a deploy fails, roll back first.", "Yesterday the build took too long.", "The team decided to ship on Fridays."};
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> errorsByStatus = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        ExecutorService pool = Executors.newFixedThreadPool(users);

        for (int u = 0; u < users; u++) {
            int user = u;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perUser; n++) {
                        int i = n;
                        int pick = ThreadLocalRandom.current().nextInt(100);
                        String q = queries[(user + i) % queries.length];
                        if (pick < 50) {
                            timed(samples, errorsByStatus, "search", () -> send(http, "GET",
                                    base + "/api/v1/memories?q=" + q.replace(" ", "%20") + "&limit=10", token, null));
                        } else if (pick < 70) {
                            timed(samples, errorsByStatus, "context", () -> send(http, "POST", base + "/api/v1/context", token,
                                    "{\"query\":\"" + q + "\",\"maxChars\":2000}"));
                        } else if (pick < 90) {
                            String text = messages[(user + i) % messages.length];
                            timed(samples, errorsByStatus, "append+extract", () -> send(http, "POST",
                                    base + "/api/v1/conversations/" + conversation + "/messages", token,
                                    "{\"content\":\"" + text + "\",\"extract\":true}"));
                        } else {
                            timed(samples, errorsByStatus, "memory write", () -> send(http, "POST", base + "/api/v1/memories", token,
                                    "{\"type\":\"EPISODIC\",\"content\":\"Load test event " + user + "-" + i + "\",\"tags\":[\"loadtest\"]}"));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        double seconds = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();

        System.out.printf(Locale.ROOT, "Memora load test against %s%n", base);
        System.out.printf(Locale.ROOT, "virtual users %d, requests per user %d, total %d, wall time %.2f s, throughput %.1f req/s%n",
                users, perUser, samples.size(), seconds, samples.size() / seconds);
        System.out.printf(Locale.ROOT, "%-16s %7s %7s %9s %9s %9s %9s%n", "operation", "count", "errors", "p50 ms", "p95 ms", "p99 ms", "max ms");
        List<String> ops = samples.stream().map(Sample::op).distinct().sorted().toList();
        for (String op : ops) {
            print(op, samples.stream().filter(s -> s.op().equals(op)).toList());
        }
        print("ALL", new ArrayList<>(samples));
        if (!errorsByStatus.isEmpty()) {
            System.out.println("errors by status: " + errorsByStatus);
        }
        System.exit(errorsByStatus.isEmpty() ? 0 : 1);
    }

    interface Call {
        HttpResponse<String> run() throws Exception;
    }

    static void timed(List<Sample> samples, Map<String, AtomicInteger> errors, String op, Call call) {
        long t = System.nanoTime();
        int status;
        try {
            status = call.run().statusCode();
        } catch (Exception e) {
            status = -1;
        }
        double ms = (System.nanoTime() - t) / 1e6;
        samples.add(new Sample(op, status, ms));
        if (status < 200 || status >= 300) {
            errors.computeIfAbsent(String.valueOf(status), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    static void print(String op, List<Sample> list) {
        double[] ms = list.stream().mapToDouble(Sample::ms).sorted().toArray();
        long errors = list.stream().filter(s -> s.status() < 200 || s.status() >= 300).count();
        System.out.printf(Locale.ROOT, "%-16s %7d %7d %9.1f %9.1f %9.1f %9.1f%n", op, list.size(), errors,
                pct(ms, 50), pct(ms, 95), pct(ms, 99), ms.length == 0 ? 0 : ms[ms.length - 1]);
    }

    static double pct(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    static HttpResponse<String> send(HttpClient http, String method, String url, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
