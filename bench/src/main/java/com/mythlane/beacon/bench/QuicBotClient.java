package com.mythlane.beacon.bench;

import java.io.Closeable;
import java.time.Duration;

/**
 * Minimal QUIC client surface used by {@link SessionSequence}, backed in
 * production by {@link NettyQuicBotClient}. Default Hytale bench target is
 * {@code localhost:5520}.
 */
public interface QuicBotClient extends Closeable {

    void connect(String host, int port, Duration timeout) throws Exception;

    /**
     * Send one datagram. Hytale frame layout:
     * {@code [4-byte LE length][4-byte LE packet ID][payload]}.
     */
    void sendDatagram(byte[] bytes) throws Exception;

    /** Returns {@code null} on timeout. */
    byte[] receiveDatagram(Duration timeout) throws Exception;

    boolean isConnected();

    @Override
    void close();
}
