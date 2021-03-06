package io.vlingo.xoom.wire.fdx.outbound.rsocket;

import io.rsocket.Closeable;
import io.rsocket.SocketAcceptor;
import io.rsocket.core.RSocketServer;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.ServerTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.server.CloseableChannel;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.vlingo.xoom.actors.Logger;
import io.vlingo.xoom.wire.message.ByteBufferAllocator;
import io.vlingo.xoom.wire.message.RawMessage;
import io.vlingo.xoom.wire.node.Address;
import io.vlingo.xoom.wire.node.AddressType;
import io.vlingo.xoom.wire.node.Host;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RSocketOutboundChannelTest {
  private static final Logger logger = Logger.basicLogger();

  @Ignore("NettyContext isDisposed() is not accurate. See https://github.com/reactor/reactor-netty/issues/581")
  @Test
  public void testChannelReconnects() throws InterruptedException {
    final ClientTransport localClientTransport = TcpClientTransport.create(49000);
    final ServerTransport<CloseableChannel> serverTransport = TcpServerTransport.create(49000);

    CountDownLatch firstCdl = new CountDownLatch(1);
    final Closeable serverSocket = createServerSocket(serverTransport, "node1", firstCdl);

    final RSocketOutboundChannel outbound = new RSocketOutboundChannel(new Address(Host.of("127.0.0.1"), 0, AddressType.MAIN), localClientTransport,
                                                                       Duration.ofMillis(2000), logger);

    final String message = UUID.randomUUID().toString();

    final RawMessage rawMessage = RawMessage.from(0, 0, message);
    final ByteBuffer buffer = ByteBufferAllocator.allocate(1024);

    outbound.write(rawMessage.asByteBuffer(buffer));
    Assert.assertTrue(firstCdl.await(100, TimeUnit.MILLISECONDS));

    serverSocket.dispose();

    Thread.sleep(1000);

    outbound.write(rawMessage.asByteBuffer(buffer));

    final CountDownLatch secondCdl = new CountDownLatch(1);

    @SuppressWarnings("unused")
    final Closeable secondServerSocket = createServerSocket(serverTransport, "node2", secondCdl);

    outbound.write(rawMessage.asByteBuffer(buffer));

    Assert.assertTrue(secondCdl.await(100, TimeUnit.MILLISECONDS));
  }

  private Closeable createServerSocket(ServerTransport<CloseableChannel> serverTransport, String name, CountDownLatch countDownLatch) {
    final Closeable serverSocket = RSocketServer.create()
                                                .acceptor(SocketAcceptor.forFireAndForget(payload -> {
                                                  logger.debug("Server socket {} received message", name);
                                                  countDownLatch.countDown();
                                                  return Mono.empty();
                                                }))
                                                .bindNow(serverTransport);

    serverSocket.onClose()
                .doFinally(signalType -> logger.info("Server socket {} closed", name))
                .subscribe();

    return serverSocket;
  }
}