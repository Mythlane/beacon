package com.mythlane.beacon.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reproduces the 11-step Hytale client session: QUIC open, auth flow init,
 * auth grant, JWT, mutual auth, required assets, world load, player ready,
 * keepalive loop, optional chat heartbeat, player leave. Frame layout:
 * {@code [4-byte LE length][4-byte LE packet ID][payload]}.
 */
public final class SessionSequence {

    // Packet IDs must be confirmed against PacketRegistry.getToServerPacketById()
    // on a live server before the bench is wired up.
    static final int PKT_AUTH_FLOW_INIT = 0x0001;
    static final int PKT_AUTH_TOKEN     = 0x0002;
    static final int PKT_REQUIRED_ASSETS_ACK = 0x0003;
    static final int PKT_PLAYER_READY   = 0x0004;
    static final int PKT_KEEPALIVE      = 0x0005;
    static final int PKT_CHAT           = 0x0006;
    static final int PKT_PLAYER_LEAVE   = 0x0007;

    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration KEEPALIVE_INTERVAL = Duration.ofMillis(1000);

    private final QuicBotClient client;
    private final AtomicInteger keepaliveCount = new AtomicInteger();

    public SessionSequence(QuicBotClient client) {
        this.client = client;
    }

    public void run(String host, int port, Duration duration) throws Exception {
        try {
            stepQuicOpen(host, port);
            stepAuthFlow();
            stepAuthGrant();
            stepJwt();
            stepMutualAuth();
            stepRequiredAssets();
            stepWorldLoad();
            stepPlayerReady();

            long deadline = System.nanoTime() + duration.toNanos();
            int beat = 0;
            while (System.nanoTime() < deadline) {
                stepKeepalive();
                if ((++beat % 10) == 0) {
                    stepChatHeartbeat();
                }
                Thread.sleep(KEEPALIVE_INTERVAL.toMillis());
            }
        } finally {
            try {
                stepPlayerLeave();
            } catch (Exception ignored) {
            }
        }
    }

    public void stepQuicOpen(String host, int port) throws Exception {
        client.connect(host, port, Duration.ofSeconds(10));
    }

    public void stepAuthFlow() throws Exception {
        client.sendDatagram(frame(PKT_AUTH_FLOW_INIT, new byte[]{0x01}));
    }

    public void stepAuthGrant() throws Exception {
        client.receiveDatagram(RECEIVE_TIMEOUT);
    }

    public void stepJwt() throws Exception {
        byte[] jwtPlaceholder = "eyJhbGciOiJub25lIn0.e30.".getBytes();
        client.sendDatagram(frame(PKT_AUTH_TOKEN, jwtPlaceholder));
    }

    public void stepMutualAuth() throws Exception {
        client.receiveDatagram(RECEIVE_TIMEOUT);
    }

    public void stepRequiredAssets() throws Exception {
        client.sendDatagram(frame(PKT_REQUIRED_ASSETS_ACK, new byte[0]));
    }

    public void stepWorldLoad() throws Exception {
        client.receiveDatagram(RECEIVE_TIMEOUT);
    }

    public void stepPlayerReady() throws Exception {
        client.sendDatagram(frame(PKT_PLAYER_READY, new byte[0]));
    }

    public void stepKeepalive() throws Exception {
        client.sendDatagram(frame(PKT_KEEPALIVE, new byte[0]));
        keepaliveCount.incrementAndGet();
    }

    public void stepChatHeartbeat() throws Exception {
        byte[] msg = "ping".getBytes();
        client.sendDatagram(frame(PKT_CHAT, msg));
    }

    public void stepPlayerLeave() throws Exception {
        if (client.isConnected()) {
            client.sendDatagram(frame(PKT_PLAYER_LEAVE, new byte[0]));
        }
        client.close();
    }

    public int keepaliveCount() {
        return keepaliveCount.get();
    }

    static byte[] frame(int packetId, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(payload.length);
        bb.putInt(packetId);
        bb.put(payload);
        return bb.array();
    }
}
