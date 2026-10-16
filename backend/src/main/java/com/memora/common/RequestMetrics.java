package com.memora.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * In-process ring buffer of recent API request timings, used to show real measured latency on the
 * dashboard. Samples carry the tenant so each tenant only sees its own traffic. Not persisted:
 * numbers reset when the backend restarts.
 */
@Component
public class RequestMetrics {

    private static final int CAPACITY = 5000;

    private record Sample(long atMillis, UUID tenantId, String route, int status, double millis) {
    }

    private final Sample[] ring = new Sample[CAPACITY];
    private int next;
    private long total;

    public synchronized void record(UUID tenantId, String route, int status, double millis) {
        ring[next] = new Sample(System.currentTimeMillis(), tenantId, route, status, millis);
        next = (next + 1) % CAPACITY;
        total++;
    }

    public Summary summary(UUID tenantId) {
        List<Sample> samples = new ArrayList<>();
        synchronized (this) {
            for (Sample s : ring) {
                // Lab runs are long batch jobs by design; keep them out of interactive API latency.
                if (s != null && tenantId.equals(s.tenantId()) && !s.route().startsWith("POST /api/v1/lab")) {
                    samples.add(s);
                }
            }
        }
        double[] all = samples.stream().mapToDouble(Sample::millis).sorted().toArray();
        Map<String, List<Double>> byRoute = new HashMap<>();
        samples.forEach(s -> byRoute.computeIfAbsent(s.route(), k -> new ArrayList<>()).add(s.millis()));
        List<RouteStat> routes = byRoute.entrySet().stream()
                .map(e -> {
                    double[] v = e.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();
                    return new RouteStat(e.getKey(), v.length, percentile(v, 50), percentile(v, 95));
                })
                .sorted(Comparator.comparingLong(RouteStat::count).reversed())
                .limit(10)
                .toList();
        long serverErrors = samples.stream().filter(s -> s.status() >= 500).count();
        long conflicts = samples.stream().filter(s -> s.status() == 409).count();
        long clientErrors = samples.stream().filter(s -> s.status() >= 400 && s.status() < 500).count();
        return new Summary(samples.size(), percentile(all, 50), percentile(all, 95), percentile(all, 99),
                all.length == 0 ? 0 : all[all.length - 1], serverErrors, clientErrors, conflicts, routes);
    }

    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return round(sorted[Math.max(0, Math.min(sorted.length - 1, index))]);
    }

    public static double percentileOf(List<Double> values, double p) {
        double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(arr);
        return percentile(arr, p);
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public record RouteStat(String route, long count, double p50Ms, double p95Ms) {
    }

    public record Summary(long sampleCount, double p50Ms, double p95Ms, double p99Ms, double maxMs, long serverErrors,
                          long clientErrors, long conflicts, List<RouteStat> topRoutes) {
    }
}
