package com.mythlane.beacon.bench;

import java.time.Duration;

public final class SmokeTest {

    private SmokeTest() {}

    public static void main(String[] args) {
        String target = "localhost:5520";
        for (String a : args) {
            if (a.startsWith("--target=")) target = a.substring("--target=".length());
        }
        String[] hp = target.split(":");
        String host = hp[0];
        int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 5520;

        System.out.printf("[smoke] connecting 1 bot to %s:%d for 30s%n", host, port);
        QuicBotClient client = new NettyQuicBotClient();
        SessionSequence seq = new SessionSequence(client);
        try {
            seq.run(host, port, Duration.ofSeconds(30));
            System.out.println("[smoke] OK keepalives=" + seq.keepaliveCount());
            System.exit(0);
        } catch (Exception e) {
            System.err.println("[smoke] FAIL: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
