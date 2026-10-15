package damjay.control.ghosthand.guest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import damjay.control.ghosthand.net.Frame;
import damjay.control.ghosthand.net.FrameCodec;
import damjay.control.ghosthand.net.GhostProtocol;
import damjay.control.ghosthand.net.Record;

/**
 * Runs the actual guest transport on a plain JVM, including real loopback TCP.
 * The fake socket can stall connect/write until close, just like a lost WiFi
 * peer: an interrupt alone MUST NOT make those tests pass. No sleeps/poll loops.
 */
public class GuestConnectionTest {
    private final List<Fixture> fixtures = new ArrayList<>();

    private static Frame frame(byte type, long id) {
        return new Frame(type, (byte) 0, id,
                Record.create().putInt("id", id).toBytes());
    }

    private static Frame hello() {
        return frame(GhostProtocol.TYPE_HELLO, 1234);
    }

    private static Frame touch(int action, long timeMs) {
        Record r = Record.create().putInt("action", action)
                .putInt("xN", 2000).putInt("yN", 3000).putInt("timeMs", timeMs);
        if (action >= 4) {
            r.putInt("x2N", 8000).putInt("y2N", 7000);
        }
        return new Frame(GhostProtocol.TYPE_TOUCH, (byte) 0, timeMs * 1000L, r.toBytes());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("worker did not reach the expected state", latch.await(2, TimeUnit.SECONDS));
    }

    private static Frame take(BlockingQueue<Frame> frames) throws InterruptedException {
        Frame next = frames.poll(2, TimeUnit.SECONDS);
        assertNotNull("frame did not arrive", next);
        return next;
    }

    private static void assertFrame(Frame expected, Frame actual) {
        assertEquals(expected.type, actual.type);
        assertEquals(expected.flags, actual.flags);
        assertEquals(expected.ptsUs, actual.ptsUs);
        assertArrayEquals(expected.payload, actual.payload);
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(2000);
            assertFalse("worker leaked: " + thread.getName(), thread.isAlive());
        }
    }

    /** Deliberately ignores interruption: only socket.close() releases the I/O. */
    private static void socketWait(CountDownLatch gate) {
        boolean interrupted = false;
        while (true) {
            try {
                gate.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static class Probe implements GuestConnection.Listener {
        final BlockingQueue<Frame> received = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();
        volatile String reason;
        volatile Thread callbackThread;
        volatile Thread closeThread;

        @Override
        public void onFrame(GuestConnection connection, Frame frame) {
            callbackThread = Thread.currentThread();
            received.add(frame);
        }

        @Override
        public void onReadTimeout(GuestConnection connection) {
            // Overridden for the watchdog test.
        }

        @Override
        public void onClosed(GuestConnection connection, String message) {
            reason = message;
            closeThread = Thread.currentThread();
            closeCount.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class FakeSocket extends Socket {
        final Thread caller = Thread.currentThread();
        final AtomicReference<String> violation = new AtomicReference<>();
        final CountDownLatch connectEntered = new CountDownLatch(1);
        final CountDownLatch connectGate = new CountDownLatch(1);
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch readGate = new CountDownLatch(1);
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch writeGate = new CountDownLatch(1);
        final BlockingQueue<Frame> written = new LinkedBlockingQueue<>();
        final AtomicInteger connectCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicInteger timeouts = new AtomicInteger();
        volatile Thread readerThread;
        volatile Thread writerThread;
        volatile Thread closerThread;
        volatile boolean blockConnect;
        volatile boolean blockWrites;
        volatile boolean failConnect;
        volatile boolean failWrites;
        volatile boolean closed;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        void offCaller(String operation) {
            if (Thread.currentThread() == caller) {
                violation.compareAndSet(null, operation + " ran on the producer/UI thread");
                throw new AssertionError(violation.get());
            }
        }

        void writerOnly() {
            offCaller("write/flush/getOutputStream");
            Thread current = Thread.currentThread();
            if (writerThread == null) {
                writerThread = current;
            } else if (writerThread != current) {
                violation.compareAndSet(null, "more than one socket writer");
                throw new AssertionError(violation.get());
            }
        }

        @Override
        public void setTcpNoDelay(boolean enabled) {
            offCaller("setTcpNoDelay");
        }

        @Override
        public void setKeepAlive(boolean enabled) {
            offCaller("setKeepAlive");
        }

        @Override
        public void setReceiveBufferSize(int size) {
            offCaller("setReceiveBufferSize");
        }

        @Override
        public void setSoTimeout(int timeout) {
            offCaller("setSoTimeout");
        }

        @Override
        public void connect(SocketAddress address, int timeout) throws IOException {
            offCaller("connect");
            readerThread = Thread.currentThread();
            connectCalls.incrementAndGet();
            connectEntered.countDown();
            if (blockConnect) {
                socketWait(connectGate);
            }
            if (closed || failConnect) {
                throw new IOException("dial failed");
            }
        }

        @Override
        public InputStream getInputStream() {
            offCaller("getInputStream");
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    offCaller("read");
                    if (timeouts.getAndDecrement() > 0) {
                        throw new SocketTimeoutException("test watchdog tick");
                    }
                    readEntered.countDown();
                    socketWait(readGate);
                    return -1;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            writerOnly();
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    write(new byte[] { (byte) value }, 0, 1);
                }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    writerOnly();
                    writeEntered.countDown();
                    if (blockWrites) {
                        socketWait(writeGate);
                    }
                    if (closed || failWrites) {
                        throw new IOException("write failed");
                    }
                    bytes.write(data, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    writerOnly();
                    // FrameCodec flushes once per frame. Decode every byte, so
                    // interleaved headers or a truncated payload fail the test.
                    ByteArrayInputStream in = new ByteArrayInputStream(bytes.toByteArray());
                    Frame decoded = FrameCodec.readFully(in);
                    assertEquals("extra bytes after frame", 0, in.available());
                    bytes.reset();
                    written.add(decoded);
                }
            };
        }

        @Override
        public synchronized void close() {
            offCaller("close");
            closerThread = Thread.currentThread();
            closeCalls.incrementAndGet();
            closed = true;
            connectGate.countDown();
            writeGate.countDown();
            readGate.countDown();
        }
    }

    private static final class Fixture {
        final FakeSocket socket = new FakeSocket();
        final Probe probe;
        final GuestConnection connection;

        Fixture(int capacity, Probe probe) {
            this.probe = probe;
            connection = new GuestConnection("127.0.0.1", 7890, hello(), probe, socket, capacity);
        }
    }

    private Fixture fixture() {
        return fixture(GuestConnection.MAX_PENDING_FRAMES, new Probe());
    }

    private Fixture fixture(int capacity, Probe probe) {
        Fixture f = new Fixture(capacity, probe);
        fixtures.add(f);
        return f;
    }

    @After
    public void stopAllWorkers() throws InterruptedException {
        for (Fixture f : fixtures) {
            f.connection.close("test cleanup");
            await(f.probe.closed);
            join(f.socket.readerThread);
            join(f.socket.writerThread);
            join(f.probe.closeThread);
            assertNull(f.socket.violation.get());
            assertEquals("duplicate close callback", 1, f.probe.closeCount.get());
            assertEquals("socket closed more than once", 1, f.socket.closeCalls.get());
        }
    }

    @Test(timeout = 8000)
    public void connectHelloCommandsAndCloseNeverDoIoOnTheCaller() throws Exception {
        Fixture f = fixture();
        Frame command = touch(0, 100);
        assertTrue(f.connection.send(command)); // safe even before dialing
        assertNull(f.socket.written.poll());
        f.connection.start();
        assertFrame(hello(), take(f.socket.written));
        assertFrame(command, take(f.socket.written));
        assertNotSame(Thread.currentThread(), f.socket.readerThread);
        assertNotSame(Thread.currentThread(), f.socket.writerThread);
        assertNotSame(f.socket.readerThread, f.socket.writerThread);
        f.connection.close("leaving");
        await(f.probe.closed);
        assertNotSame(Thread.currentThread(), f.socket.closerThread);
        assertEquals("leaving", f.probe.reason);
    }

    @Test(timeout = 8000)
    public void singlePinchNavigationClipboardAndPingFramesStayInFifoOrder() throws Exception {
        Fixture f = fixture();
        List<Frame> expected = new ArrayList<>();
        expected.add(hello());
        for (int action : new int[] { 0, 1, 2, 0, 4, 5, 6, 0, 2, 3 }) {
            expected.add(touch(action, 100 + expected.size()));
        }
        expected.add(frame(GhostProtocol.TYPE_GLOBAL_ACTION, 1));
        expected.add(frame(GhostProtocol.TYPE_CLIPBOARD_GET, 2));
        expected.add(new Frame(GhostProtocol.TYPE_CLIPBOARD_SET, (byte) 0, 0,
                Record.create().putString("text", "copied text \u20ac").putInt("ok", 1).toBytes()));
        expected.add(frame(GhostProtocol.TYPE_PING, 3));
        for (int i = 1; i < expected.size(); i++) {
            assertTrue(f.connection.send(expected.get(i)));
        }
        f.connection.start();
        for (Frame wanted : expected) {
            assertFrame(wanted, take(f.socket.written));
        }
        assertNull(f.socket.written.poll());
    }

    @Test(timeout = 8000)
    public void blockedWriteCannotBlockSendOrDisconnectAndQueuedCommandsAreDiscarded() throws Exception {
        Fixture f = fixture();
        f.socket.blockWrites = true;
        f.connection.start();
        await(f.socket.writeEntered); // HELLO is stuck inside write(), not queue.take()
        assertTrue(f.connection.send(touch(0, 1)));
        assertTrue(f.connection.send(touch(1, 2)));
        assertTrue(f.connection.send(touch(2, 3)));
        f.connection.close("disconnect while writing");
        assertFalse(f.connection.isOpen());
        assertFalse(f.connection.send(frame(GhostProtocol.TYPE_GLOBAL_ACTION, 4)));
        await(f.probe.closed);
        join(f.socket.writerThread);
        assertNull("queued bytes or BYE escaped after disconnect", f.socket.written.poll());
    }

    @Test(timeout = 8000)
    public void fullQueueFailsExplicitlyInsteadOfBlockingOrSilentlyLosingAnUp() throws Exception {
        Fixture f = fixture(2, new Probe());
        f.socket.blockWrites = true;
        f.connection.start();
        await(f.socket.writeEntered);
        assertTrue(f.connection.send(touch(0, 1)));
        assertTrue(f.connection.send(touch(1, 2)));
        assertFalse(f.connection.send(touch(2, 3)));
        assertFalse(f.connection.isOpen());
        await(f.probe.closed);
        assertTrue(f.probe.reason, f.probe.reason.contains("queue full"));
        assertFalse(f.connection.send(touch(0, 4)));
    }

    @Test(timeout = 8000)
    public void disconnectCancelsAnInFlightDialWithoutStartingAWriter() throws Exception {
        Fixture f = fixture();
        f.socket.blockConnect = true;
        f.connection.start();
        await(f.socket.connectEntered);
        assertTrue(f.connection.send(touch(0, 1)));
        f.connection.close("cancel dialing");
        await(f.probe.closed);
        join(f.socket.readerThread);
        assertNull(f.socket.writerThread);
        assertNull(f.socket.written.poll());
        assertEquals("cancel dialing", f.probe.reason);
    }

    @Test(timeout = 8000)
    public void closeBeforeStartCannotBeReopenedOrAcceptMoreCommands() throws Exception {
        Fixture f = fixture();
        f.connection.close("cancel before dial");
        f.connection.start();
        assertFalse(f.connection.send(touch(0, 1)));
        await(f.probe.closed);
        assertEquals(0, f.socket.connectCalls.get());
        assertNull(f.socket.written.poll());
    }

    @Test(timeout = 8000)
    public void repeatedStartDoesNotCreateAnotherWriterOrSendAnotherHello() throws Exception {
        Fixture f = fixture();
        f.connection.start();
        f.connection.start();
        assertFrame(hello(), take(f.socket.written));
        Frame command = frame(GhostProtocol.TYPE_GLOBAL_ACTION, 1);
        assertTrue(f.connection.send(command));
        assertFrame(command, take(f.socket.written));
        assertEquals(1, f.socket.connectCalls.get());
    }

    @Test(timeout = 8000)
    public void writeFailureNotifiesOnceAndShutsDownTheReader() throws Exception {
        Fixture f = fixture();
        f.socket.failWrites = true;
        f.connection.start();
        await(f.probe.closed);
        assertTrue(f.probe.reason, f.probe.reason.startsWith("send failed:"));
        assertFalse(f.connection.isOpen());
        assertFalse(f.connection.send(touch(2, 1)));
        f.connection.close("redundant close");
    }

    @Test(timeout = 8000)
    public void connectFailureNotifiesAndNeverStartsTheWriter() throws Exception {
        Fixture f = fixture();
        f.socket.failConnect = true;
        f.connection.start();
        await(f.probe.closed);
        assertTrue(f.probe.reason, f.probe.reason.startsWith("connection failed:"));
        assertFalse(f.connection.isOpen());
        assertNull(f.socket.writerThread);
    }

    @Test(timeout = 8000)
    public void watchdogPingGoesThroughTheSameWriterAsUiCommands() throws Exception {
        Frame ping = frame(GhostProtocol.TYPE_PING, 77);
        Probe watchdog = new Probe() {
            @Override
            public void onReadTimeout(GuestConnection connection) {
                assertTrue(connection.send(ping));
            }
        };
        Fixture f = fixture(10, watchdog);
        f.socket.timeouts.set(1);
        assertTrue(f.connection.send(touch(0, 1)));
        f.connection.start();
        assertFrame(hello(), take(f.socket.written));
        assertFrame(touch(0, 1), take(f.socket.written));
        assertFrame(ping, take(f.socket.written));
    }

    @Test(timeout = 8000)
    public void oldQueuesAndLateCleanupCannotReachTheNextConnection() throws Exception {
        Fixture old = fixture();
        old.socket.blockWrites = true;
        old.connection.start();
        await(old.socket.writeEntered);
        assertTrue(old.connection.send(touch(0, 111)));
        old.connection.close("reconnecting");

        Fixture next = fixture();
        Frame command = touch(0, 222);
        assertTrue(next.connection.send(command));
        next.connection.start();
        assertFrame(hello(), take(next.socket.written));
        assertFrame(command, take(next.socket.written));
        await(old.probe.closed);
        join(old.socket.writerThread);
        old.connection.close("late cleanup");
        assertTrue(next.connection.isOpen());
        assertTrue(next.connection.send(touch(2, 333)));
        assertFrame(touch(2, 333), take(next.socket.written));
        assertNull(old.socket.written.poll());
    }

    @Test(timeout = 8000)
    public void concurrentProducersNeverInterleaveHeadersOrReorderTheirOwnEvents() throws Exception {
        Fixture f = fixture();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> producers = new ArrayList<>();
        for (int producer = 0; producer < 4; producer++) {
            final int id = producer;
            Thread thread = new Thread(() -> {
                try {
                    gate.await();
                    for (int seq = 0; seq < 20; seq++) {
                        assertTrue(f.connection.send(frame(GhostProtocol.TYPE_TOUCH, id * 100 + seq)));
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            });
            producers.add(thread);
            thread.start();
        }
        gate.countDown();
        for (Thread producer : producers) {
            join(producer);
        }
        assertNull(error.get());
        f.connection.start();
        assertFrame(hello(), take(f.socket.written));
        int[] seen = new int[4];
        for (int i = 0; i < 80; i++) {
            Frame actual = take(f.socket.written);
            int id = (int) (actual.ptsUs / 100);
            assertTrue(id >= 0 && id < seen.length);
            assertFrame(frame(GhostProtocol.TYPE_TOUCH, id * 100 + seen[id]++), actual);
        }
        for (int count : seen) {
            assertEquals(20, count);
        }
    }

    @Test(timeout = 8000)
    public void actualTcpDeliversHelloAndControlImmediatelyAndClosesWithEof() throws Exception {
        Probe probe = new Probe();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(2000);
        GuestConnection connection = new GuestConnection("127.0.0.1", server.getLocalPort(), hello(), probe);
        Socket peer = null;
        try {
            connection.start();
            peer = server.accept();
            peer.setSoTimeout(2000);
            assertFrame(hello(), FrameCodec.readFully(peer.getInputStream()));
            Frame command = touch(4, 999);
            assertTrue(connection.send(command));
            assertFrame(command, FrameCodec.readFully(peer.getInputStream()));
            Frame reply = frame(GhostProtocol.TYPE_WELCOME, 55);
            FrameCodec.write(peer.getOutputStream(), reply);
            assertFrame(reply, take(probe.received));
            assertNotSame(Thread.currentThread(), probe.callbackThread);
            connection.close("test disconnect");
            await(probe.closed);
            assertNotSame(Thread.currentThread(), probe.closeThread);
            assertEquals(-1, peer.getInputStream().read());
            assertFalse(connection.send(command));
        } finally {
            connection.close("test cleanup");
            await(probe.closed);
            if (peer != null) {
                peer.close();
            }
            server.close();
        }
    }

    @Test(timeout = 8000)
    public void remoteEofReportsExactlyOnceAndRejectsLaterCommands() throws Exception {
        Probe probe = new Probe();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(2000);
        GuestConnection connection = new GuestConnection("127.0.0.1", server.getLocalPort(), hello(), probe);
        Socket peer = null;
        try {
            connection.start();
            peer = server.accept();
            peer.setSoTimeout(2000);
            assertFrame(hello(), FrameCodec.readFully(peer.getInputStream()));
            peer.close();
            await(probe.closed);
            assertEquals("host closed the connection", probe.reason);
            assertFalse(connection.isOpen());
            assertFalse(connection.send(touch(0, 1)));
            connection.close("another close");
            assertEquals(1, probe.closeCount.get());
        } finally {
            connection.close("test cleanup");
            await(probe.closed);
            if (peer != null) {
                peer.close();
            }
            server.close();
        }
    }

    @Test
    public void controllerBytecodeCannotWriteOrCloseASocketDirectlyAgain() throws IOException {
        // This pins the wiring, not just the helper: the vc14 controller FAILS
        // it. Looking at class bytes does not load/execute any Android stubs.
        for (String name : new String[] { "GuestController", "GuestController$Session" }) {
            InputStream in = getClass().getResourceAsStream(name + ".class");
            assertNotNull(name, in);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, n);
                }
            } finally {
                in.close();
            }
            String pool = new String(bytes.toByteArray(), "ISO-8859-1");
            assertFalse(name + " directly references a socket", pool.contains("java/net/"));
            assertFalse(name + " bypasses the writer", pool.contains("getOutputStream"));
            assertFalse(name + " bypasses the codec boundary", pool.contains("net/FrameCodec"));
            assertFalse(name + " relaxes StrictMode", pool.contains("android/os/StrictMode"));
            assertTrue(name + " must use the tested transport", pool.contains("guest/GuestConnection"));
        }
    }
}
