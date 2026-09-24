package com.cloudwms.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application context against a Testcontainers MySQL. Every integration test uses this same
 * configuration so Spring caches one context and all of them share a single database container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = { "wms.auth.supervisor-passcode=" + TestCredentials.SUPERVISOR_PASSCODE,
		"wms.auth.agent-token=" + TestCredentials.AGENT_TOKEN })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestCredentials.class })
public @interface IntegrationTest {

}
