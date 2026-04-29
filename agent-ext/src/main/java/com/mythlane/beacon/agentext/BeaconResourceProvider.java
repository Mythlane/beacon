package com.mythlane.beacon.agentext;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;

final class BeaconResourceProvider {

    static final String DISTRO_NAME = "beacon";
    static final String DISTRO_VERSION = "0.1.0-alpha";
    static final String DEPLOYMENT_ENV_VAR = "BEACON_DEPLOYMENT_ENVIRONMENT";

    static final AttributeKey<String> TELEMETRY_DISTRO_NAME =
        AttributeKey.stringKey("telemetry.distro.name");
    static final AttributeKey<String> TELEMETRY_DISTRO_VERSION =
        AttributeKey.stringKey("telemetry.distro.version");
    static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT =
        AttributeKey.stringKey("deployment.environment");

    private BeaconResourceProvider() {}

    static Resource augment(Resource input) {
        return augment(input, System.getenv(DEPLOYMENT_ENV_VAR));
    }

    static Resource augment(Resource input, String deploymentEnvironment) {
        AttributesBuilder builder = Attributes.builder()
            .put(TELEMETRY_DISTRO_NAME, DISTRO_NAME)
            .put(TELEMETRY_DISTRO_VERSION, DISTRO_VERSION);

        if (deploymentEnvironment != null && !deploymentEnvironment.isEmpty()) {
            builder.put(DEPLOYMENT_ENVIRONMENT, deploymentEnvironment);
        }

        Resource beaconResource = Resource.create(builder.build());
        return input.merge(beaconResource);
    }
}
