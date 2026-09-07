package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.protocol.BoltMessageDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * z-graph Bolt 4.4 服务端 — Netty 实现,POC 版本。
 *
 * 启动:java BoltServer [port]    (默认 7687)
 *
 * 连接后接受 neo4j-python / neo4j-java driver,执行简单 Cypher。
 */
public class BoltServer {

    private static final Logger log = LogManager.getLogger(BoltServer.class);

    private final int port;
    private final GraphVersionStore graphRepository;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public BoltServer(int port) {
        this(port, new GraphVersionStore());
    }

    public BoltServer(int port, GraphVersionStore graphRepository) {
        this.port = port;
        this.graphRepository = graphRepository;
    }

    public BoltServer(int port, Path dataDirectory) {
        this(port, new GraphVersionStore(dataDirectory));
    }

    public GraphVersionStore graphRepository() {
        return graphRepository;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new BoltMessageDecoder(),
                                new BoltMessageHandler(graphRepository)
                        );
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture f = b.bind(new InetSocketAddress(port)).sync();
        serverChannel = f.channel();
        log.info("✅ z-graph BoltServer started on port {} (PID={})", port, ProcessHandle.current().pid());
    }

    public int port() {
        if (serverChannel == null) {
            return port;
        }
        if (serverChannel.localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return port;
    }

    public void shutdown() {
        log.info("Shutting down z-graph BoltServer...");
        if (serverChannel != null) { serverChannel.close(); }

        if (bossGroup != null) { bossGroup.shutdownGracefully(); }

        if (workerGroup != null) { workerGroup.shutdownGracefully(); }

    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7687;
        String dataDirectory = System.getProperty("z.graph.dataDir");
        BoltServer server = dataDirectory == null
                ? new BoltServer(port)
                : new BoltServer(port, Path.of(dataDirectory));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        // 阻塞直到 channel 关闭
        server.serverChannel.closeFuture().sync();
    }
}