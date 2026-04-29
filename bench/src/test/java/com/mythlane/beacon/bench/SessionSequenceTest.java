package com.mythlane.beacon.bench;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSequenceTest {

    private static class RecordingClient implements QuicBotClient {
        final List<Integer> sentPacketIds = Collections.synchronizedList(new ArrayList<>());
        boolean connected = false;
        boolean closed = false;
        Exception keepaliveException;

        @Override
        public void connect(String host, int port, Duration timeout) {
            connected = true;
        }

        @Override
        public void sendDatagram(byte[] bytes) throws Exception {
            int id = (bytes[4] & 0xff) | ((bytes[5] & 0xff) << 8)
                    | ((bytes[6] & 0xff) << 16) | ((bytes[7] & 0xff) << 24);
            sentPacketIds.add(id);
            if (id == SessionSequence.PKT_KEEPALIVE && keepaliveException != null) {
                throw keepaliveException;
            }
        }

        @Override
        public byte[] receiveDatagram(Duration timeout) {
            return new byte[]{0};
        }

        @Override
        public boolean isConnected() {
            return connected && !closed;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }
    }

    @Test
    void run_invokesAllStepMethodsInDocumentedOrder() throws Exception {
        RecordingClient client = new RecordingClient();
        SessionSequence seq = new SessionSequence(client);

        seq.run("localhost", 5520, Duration.ZERO);

        assertThat(client.sentPacketIds).containsExactly(
                SessionSequence.PKT_AUTH_FLOW_INIT,
                SessionSequence.PKT_AUTH_TOKEN,
                SessionSequence.PKT_REQUIRED_ASSETS_ACK,
                SessionSequence.PKT_PLAYER_READY,
                SessionSequence.PKT_PLAYER_LEAVE
        );
        assertThat(client.closed).isTrue();
    }

    @Test
    void keepaliveLoopIteratesAtLeastOnceWhenDurationExceedsCadence() throws Exception {
        RecordingClient client = new RecordingClient();
        SessionSequence seq = new SessionSequence(client);

        seq.run("localhost", 5520, Duration.ofMillis(1500));

        assertThat(seq.keepaliveCount()).isGreaterThanOrEqualTo(1);
        assertThat(client.sentPacketIds).contains(SessionSequence.PKT_KEEPALIVE);
    }

    @Test
    void playerLeaveIsAlwaysCalledEvenWhenKeepaliveThrows() {
        RecordingClient client = new RecordingClient();
        client.keepaliveException = new RuntimeException("simulated network drop");
        SessionSequence seq = new SessionSequence(client);

        AtomicBoolean threw = new AtomicBoolean(false);
        try {
            seq.run("localhost", 5520, Duration.ofSeconds(5));
        } catch (Exception e) {
            threw.set(true);
        }

        assertThat(client.closed).isTrue();
    }

    @Test
    void elevenStepMethodsExist_bytheirPublicNames() throws NoSuchMethodException {
        Class<?> c = SessionSequence.class;
        c.getMethod("stepQuicOpen", String.class, int.class);
        c.getMethod("stepAuthFlow");
        c.getMethod("stepAuthGrant");
        c.getMethod("stepJwt");
        c.getMethod("stepMutualAuth");
        c.getMethod("stepRequiredAssets");
        c.getMethod("stepWorldLoad");
        c.getMethod("stepPlayerReady");
        c.getMethod("stepKeepalive");
        c.getMethod("stepChatHeartbeat");
        c.getMethod("stepPlayerLeave");
    }

    @Test
    void frameLayoutIsLittleEndianLengthAndPacketId() {
        byte[] f = SessionSequence.frame(0x12345678, new byte[]{(byte) 0xAA, (byte) 0xBB});
        assertThat(f).hasSize(10);
        assertThat(f[0]).isEqualTo((byte) 2);
        assertThat(f[1]).isEqualTo((byte) 0);
        assertThat(f[4]).isEqualTo((byte) 0x78);
        assertThat(f[5]).isEqualTo((byte) 0x56);
        assertThat(f[6]).isEqualTo((byte) 0x34);
        assertThat(f[7]).isEqualTo((byte) 0x12);
        assertThat(f[8]).isEqualTo((byte) 0xAA);
        assertThat(f[9]).isEqualTo((byte) 0xBB);
    }
}
