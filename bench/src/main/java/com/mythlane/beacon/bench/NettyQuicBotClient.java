package com.mythlane.beacon.bench;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Netty incubator QUIC implementation of {@link QuicBotClient}. Opens a single
 * bidirectional stream and lets {@link SessionSequence} assemble frames. The
 * Hytale server enforces JWT identity; the bench is expected to run against a
 * dev server that accepts the placeholder tokens {@link SessionSequence} sends.
 */
public final class NettyQuicBotClient implements QuicBotClient {

    private static final String[] HYTALE_QUIC_ALPN = new String[]{"hytale/2", "hytale/1"};

    private EventLoopGroup group;
    private Channel udpChannel;
    private QuicChannel quicChannel;
    private QuicStreamChannel stream;
    private final LinkedBlockingQueue<byte[]> rx = new LinkedBlockingQueue<>();

    @Override
    public void connect(String host, int port, Duration timeout) throws Exception {
        group = new NioEventLoopGroup(1);

        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(HYTALE_QUIC_ALPN)
                .build();

        Bootstrap bs = new Bootstrap();
        udpChannel = bs.group(group)
                .channel(NioDatagramChannel.class)
                .handler(new QuicClientCodecBuilder()
                        .sslContext(sslContext)
                        .maxIdleTimeout(30, TimeUnit.SECONDS)
                        .initialMaxData(10_000_000)
                        .initialMaxStreamDataBidirectionalLocal(1_000_000)
                        .initialMaxStreamDataBidirectionalRemote(1_000_000)
                        .initialMaxStreamsBidirectional(100)
                        .build())
                .bind(0).sync().channel();

        Future<QuicChannel> qcFuture = QuicChannel.newBootstrap(udpChannel)
                .handler(new ChannelInboundHandlerAdapter())
                .remoteAddress(new InetSocketAddress(host, port))
                .connect();

        if (!qcFuture.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("QUIC connect timeout to " + host + ":" + port);
        }
        quicChannel = qcFuture.getNow();

        stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof ByteBuf buf) {
                            try {
                                byte[] copy = new byte[buf.readableBytes()];
                                buf.readBytes(copy);
                                rx.offer(copy);
                            } finally {
                                buf.release();
                            }
                        }
                    }
                }).sync().getNow();
    }

    @Override
    public void sendDatagram(byte[] bytes) {
        if (stream == null || !stream.isActive()) {
            throw new IllegalStateException("stream not open");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        stream.writeAndFlush(buf);
    }

    @Override
    public byte[] receiveDatagram(Duration timeout) throws InterruptedException {
        return rx.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isConnected() {
        return stream != null && stream.isActive();
    }

    @Override
    public void close() {
        try {
            if (stream != null) stream.close().syncUninterruptibly();
            if (quicChannel != null) quicChannel.close().syncUninterruptibly();
            if (udpChannel != null) udpChannel.close().syncUninterruptibly();
        } finally {
            if (group != null) group.shutdownGracefully();
        }
    }
}
