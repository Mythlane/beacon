package com.mythlane.beacon.dist;

/**
 * The {@code dist} module exists solely to assemble the Beacon fat JAR via the
 * Shadow plugin (see {@code dist/build.gradle}). It carries no production code
 * of its own — all behavior lives in {@code core}, {@code instrum}, and
 * {@code binding}, which Shadow bundles together with relocated OTel / gRPC /
 * Netty / protobuf / OkHttp dependencies into
 * {@code dist/build/libs/beacon-0.1.0-alpha.jar}.
 *
 * <p>This placeholder ensures the module has a compilable Java source tree so
 * that {@code beacon-conventions} (which applies {@code java-library}) and the
 * Shadow plugin both have something to operate on.
 */
final class Placeholder {
    private Placeholder() {
        // no instances
    }
}
