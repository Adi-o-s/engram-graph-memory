package com.memora.orchestration.executor;

/**
 * How an executor works, shown in the UI so nobody mistakes configuration for intelligence.
 *
 * @param deterministic same inputs and memory produce the same outputs
 * @param external      the work is done outside Memora (a person, a script, an AI agent using its API key)
 * @param available     false for executors that are documented but not shipped (e.g. a model-backed one)
 */
public record ExecutorDescriptor(String id, String title, String description, boolean deterministic, boolean external,
                                 boolean available, String unavailableReason) {
}
