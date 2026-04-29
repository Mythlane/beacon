package com.mythlane.beacon.core;

/** Thrown when {@link ConfigLoader} cannot produce a usable {@link BeaconConfig}. */
public class ConfigLoaderException extends RuntimeException {
    public ConfigLoaderException(String message) { super(message); }
    public ConfigLoaderException(String message, Throwable cause) { super(message, cause); }
}
