package com.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class RateLimiterApplicationTests {

	@Container
	@ServiceConnection
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
			.withExposedPorts(6379);

	@Test
	void contextLoads() {
	}

}
