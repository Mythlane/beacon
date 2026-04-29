package com.mythlane.beacon.core;

/** Thrown by {@link OpenTelemetryFactory} when SDK construction fails. */
public class BeaconInitException extends RuntimeException {
    public BeaconInitException(String message) { super(message); }
    public BeaconInitException(String message, Throwable cause) { super(message, cause); }
}
