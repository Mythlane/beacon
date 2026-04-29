package com.mythlane.beacon.agentext;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExtensionTest {

    private static final AttributeKey<String> TELEMETRY_DISTRO_NAME =
        AttributeKey.stringKey("telemetry.distro.name");
    private static final AttributeKey<String> TELEMETRY_DISTRO_VERSION =
        AttributeKey.stringKey("telemetry.distro.version");
    private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT =
        AttributeKey.stringKey("deployment.environment");
    private static final AttributeKey<String> SERVICE_NAME =
        AttributeKey.stringKey("service.name");

    @Test
    void augmentAddsTelemetryDistroAttributesToEmptyResource() {
        Resource result = BeaconResourceProvider.augment(Resource.empty(), null);

        assertThat(result.getAttribute(TELEMETRY_DISTRO_NAME)).isEqualTo("beacon");
        assertThat(result.getAttribute(TELEMETRY_DISTRO_VERSION)).isEqualTo("0.1.0-alpha");
        assertThat(result.getAttribute(DEPLOYMENT_ENVIRONMENT)).isNull();
    }

    @Test
    void augmentHonorsDeploymentEnvironmentEnvVarWhenPresent() {
        Resource result = BeaconResourceProvider.augment(Resource.empty(), "prod");

        assertThat(result.getAttribute(DEPLOYMENT_ENVIRONMENT)).isEqualTo("prod");
        assertThat(result.getAttribute(TELEMETRY_DISTRO_NAME)).isEqualTo("beacon");
    }

    @Test
    void augmentPreservesPreExistingResourceAttributes() {
        Resource input = Resource.create(
            Attributes.builder().put(SERVICE_NAME, "foo").build());

        Resource result = BeaconResourceProvider.augment(input, null);

        assertThat(result.getAttribute(SERVICE_NAME)).isEqualTo("foo");
        assertThat(result.getAttribute(TELEMETRY_DISTRO_NAME)).isEqualTo("beacon");
        assertThat(result.getAttribute(TELEMETRY_DISTRO_VERSION)).isEqualTo("0.1.0-alpha");
    }

    @Test
    void spiServiceFileIsOnClasspathAndPointsToBeaconExtension() throws Exception {
        String resourceName =
            "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourceName);
        assertThat(url).as("SPI service file must be on classpath").isNotNull();

        String content;
        try (InputStream in = url.openStream();
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(in, StandardCharsets.UTF_8))) {
            content = reader.lines().collect(Collectors.joining("\n"));
        }
        assertThat(content).contains("com.mythlane.beacon.agentext.BeaconExtension");
    }
}
