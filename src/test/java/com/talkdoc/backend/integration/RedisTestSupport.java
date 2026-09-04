package com.talkdoc.backend.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Provides a Redis for integration tests: a Testcontainers Redis when Docker is available,
 * otherwise a Redis already listening on localhost:6379 (e.g. `redis-server --daemonize yes`).
 * Tests are skipped when neither is available.
 */
final class RedisTestSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisTestSupport.class);
    private static GenericContainer<?> container;
    private static String host = "localhost";
    private static int port = 6379;
    private static Boolean available;

    private RedisTestSupport() {
    }

    static synchronized boolean isAvailable() {
        if (available != null) return available;
        if (dockerAvailable()) {
            try {
                container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
                container.start();
                host = container.getHost();
                port = container.getMappedPort(6379);
                log.info("Using Testcontainers Redis at {}:{}", host, port);
                available = true;
                return true;
            } catch (RuntimeException e) {
                log.warn("Testcontainers Redis failed to start: {}", e.getMessage());
            }
        }
        available = reachable("localhost", 6379);
        if (available) {
            log.info("Using local Redis at localhost:6379");
        } else {
            log.warn("No Redis available (Docker off and localhost:6379 closed); integration tests skipped");
        }
        return available;
    }

    static void register(DynamicPropertyRegistry registry) {
        isAvailable();
        registry.add("spring.data.redis.host", () -> host);
        registry.add("spring.data.redis.port", () -> port);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean reachable(String h, int p) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(h, p), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
