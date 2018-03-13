/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Tests what happens when response body handlers and subscribers
 *          throw unexpected exceptions.
 * @library /lib/testlibrary http2/server
 * @build jdk.testlibrary.SimpleSSLContext HttpServerAdapters ThrowingSubscribers
 * @modules java.base/sun.net.www.http
 *          java.net.http/jdk.internal.net.http.common
 *          java.net.http/jdk.internal.net.http.frame
 *          java.net.http/jdk.internal.net.http.hpack
 * @run testng/othervm -Djdk.internal.httpclient.debug=true ThrowingSubscribers
 */

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jdk.testlibrary.SimpleSSLContext;
import org.testng.annotations.AfterTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.System.out;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ThrowingSubscribers implements HttpServerAdapters {

    SSLContext sslContext;
    HttpTestServer httpTestServer;    // HTTP/1.1    [ 4 servers ]
    HttpTestServer httpsTestServer;   // HTTPS/1.1
    HttpTestServer http2TestServer;   // HTTP/2 ( h2c )
    HttpTestServer https2TestServer;  // HTTP/2 ( h2  )
    String httpURI_fixed;
    String httpURI_chunk;
    String httpsURI_fixed;
    String httpsURI_chunk;
    String http2URI_fixed;
    String http2URI_chunk;
    String https2URI_fixed;
    String https2URI_chunk;

    static final int ITERATION_COUNT = 1;
    // a shared executor helps reduce the amount of threads created by the test
    static final Executor executor = new TestExecutor(Executors.newCachedThreadPool());
    static final ConcurrentMap<String, Throwable> FAILURES = new ConcurrentHashMap<>();
    static volatile boolean tasksFailed;
    static final AtomicLong serverCount = new AtomicLong();
    static final AtomicLong clientCount = new AtomicLong();
    static final long start = System.nanoTime();
    public static String now() {
        long now = System.nanoTime() - start;
        long secs = now / 1000_000_000;
        long mill = (now % 1000_000_000) / 1000_000;
        long nan = now % 1000_000;
        return String.format("[%d s, %d ms, %d ns] ", secs, mill, nan);
    }

    private volatile HttpClient sharedClient;

    static class TestExecutor implements Executor {
        final AtomicLong tasks = new AtomicLong();
        Executor executor;
        TestExecutor(Executor executor) {
            this.executor = executor;
        }

        @Override
        public void execute(Runnable command) {
            long id = tasks.incrementAndGet();
            executor.execute(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    tasksFailed = true;
                    System.out.printf(now() + "Task %s failed: %s%n", id, t);
                    System.err.printf(now() + "Task %s failed: %s%n", id, t);
                    FAILURES.putIfAbsent("Task " + id, t);
                    throw t;
                }
            });
        }
    }

    @AfterClass
    static final void printFailedTests() {
        out.println("\n=========================");
        try {
            out.printf("%n%sCreated %d servers and %d clients%n",
                    now(), serverCount.get(), clientCount.get());
            if (FAILURES.isEmpty()) return;
            out.println("Failed tests: ");
            FAILURES.entrySet().forEach((e) -> {
                out.printf("\t%s: %s%n", e.getKey(), e.getValue());
                e.getValue().printStackTrace(out);
                e.getValue().printStackTrace();
            });
            if (tasksFailed) {
                System.out.println("WARNING: Some tasks failed");
            }
        } finally {
            out.println("\n=========================\n");
        }
    }

    private String[] uris() {
        return new String[] {
                httpURI_fixed,
                httpURI_chunk,
                httpsURI_fixed,
                httpsURI_chunk,
                http2URI_fixed,
                http2URI_chunk,
                https2URI_fixed,
                https2URI_chunk,
        };
    }

    @DataProvider(name = "noThrows")
    public Object[][] noThrows() {
        String[] uris = uris();
        Object[][] result = new Object[uris.length * 2][];
        int i = 0;
        for (boolean sameClient : List.of(false, true)) {
            for (String uri: uris()) {
                result[i++] = new Object[] {uri, sameClient};
            }
        }
        assert i == uris.length * 2;
        return result;
    }

    @DataProvider(name = "variants")
    public Object[][] variants() {
        String[] uris = uris();
        Object[][] result = new Object[uris.length * 2 * 2][];
        int i = 0;
        for (Thrower thrower : List.of(
                new UncheckedIOExceptionThrower(),
                new UncheckedCustomExceptionThrower())) {
            for (boolean sameClient : List.of(false, true)) {
                for (String uri : uris()) {
                    result[i++] = new Object[]{uri, sameClient, thrower};
                }
            }
        }
        assert i == uris.length * 2 * 2;
        return result;
    }

    private HttpClient makeNewClient() {
        clientCount.incrementAndGet();
        return HttpClient.newBuilder()
                .executor(executor)
                .sslContext(sslContext)
                .build();
    }

    HttpClient newHttpClient(boolean share) {
        if (!share) return makeNewClient();
        HttpClient shared = sharedClient;
        if (shared != null) return shared;
        synchronized (this) {
            shared = sharedClient;
            if (shared == null) {
                shared = sharedClient = makeNewClient();
            }
            return shared;
        }
    }

    @Test(dataProvider = "noThrows")
    public void testNoThrows(String uri, boolean sameClient)
            throws Exception {
        HttpClient client = null;
        out.printf("%ntestNoThrows(%s, %b)%n", uri, sameClient);
        for (int i=0; i< ITERATION_COUNT; i++) {
            if (!sameClient || client == null)
                client = newHttpClient(sameClient);

            HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                    .build();
            BodyHandler<String> handler =
                    new ThrowingBodyHandler((w) -> {},
                                            BodyHandlers.ofString());
            HttpResponse<String> response = client.send(req, handler);
            String body = response.body();
            assertEquals(URI.create(body).getPath(), URI.create(uri).getPath());
        }
    }

    @Test(dataProvider = "variants")
    public void testThrowingAsString(String uri,
                                     boolean sameClient,
                                     Thrower thrower)
            throws Exception
    {
        String test = format("testThrowingAsString(%s, %b, %s)",
                             uri, sameClient, thrower);
        testThrowing(test, uri, sameClient, BodyHandlers::ofString,
                this::shouldHaveThrown, thrower,false);
    }

    @Test(dataProvider = "variants")
    public void testThrowingAsLines(String uri,
                                    boolean sameClient,
                                    Thrower thrower)
            throws Exception
    {
        String test =  format("testThrowingAsLines(%s, %b, %s)",
                uri, sameClient, thrower);
        testThrowing(test, uri, sameClient, BodyHandlers::ofLines,
                this::checkAsLines, thrower,false);
    }

    @Test(dataProvider = "variants")
    public void testThrowingAsInputStream(String uri,
                                          boolean sameClient,
                                          Thrower thrower)
            throws Exception
    {
        String test = format("testThrowingAsInputStream(%s, %b, %s)",
                uri, sameClient, thrower);
        testThrowing(test, uri, sameClient, BodyHandlers::ofInputStream,
                this::checkAsInputStream,  thrower,false);
    }

    @Test(dataProvider = "variants")
    public void testThrowingAsStringAsync(String uri,
                                          boolean sameClient,
                                          Thrower thrower)
            throws Exception
    {
        String test = format("testThrowingAsStringAsync(%s, %b, %s)",
                uri, sameClient, thrower);
        testThrowing(test, uri, sameClient, BodyHandlers::ofString,
                     this::shouldHaveThrown, thrower, true);
    }

    @Test(dataProvider = "variants")
    public void testThrowingAsLinesAsync(String uri,
                                         boolean sameClient,
                                         Thrower thrower)
            throws Exception
    {
        String test = format("testThrowingAsLinesAsync(%s, %b, %s)",
                uri, sameClient, thrower);
        testThrowing(test, uri, sameClient, BodyHandlers::ofLines,
                this::checkAsLines, thrower,true);
    }

    @Test(dataProvider = "variants")
    public void testThrowingAsInputStreamAsync(String uri,
                                               boolean sameClient,
                                               Thrower thrower)
            throws Exception
    {
        String test = format("testThrowingAsInputStreamAsync(%s, %b, %s)",
                uri, sameClient, thrower);
        testThrowing(test, uri, sameClient, BodyHandlers::ofInputStream,
                this::checkAsInputStream, thrower,true);
    }

    private <T,U> void testThrowing(String name, String uri, boolean sameClient,
                                    Supplier<BodyHandler<T>> handlers,
                                    Finisher finisher, Thrower thrower, boolean async)
            throws Exception
    {
        out.printf("%n%s%s%n", now(), name);
        try {
            testThrowing(uri, sameClient, handlers, finisher, thrower, async);
        } catch (Error | Exception x) {
            FAILURES.putIfAbsent(name, x);
            throw x;
        }
    }

    private <T,U> void testThrowing(String uri, boolean sameClient,
                                    Supplier<BodyHandler<T>> handlers,
                                    Finisher finisher, Thrower thrower,
                                    boolean async)
            throws Exception
    {
        HttpClient client = null;
        for (Where where : Where.values()) {

            // Throwing on onSubscribe needs some more work
            // for the case of InputStream, where the body has already
            // completed by the time the subscriber is subscribed.
            // The only way we have at that point to relay the exception
            // is to call onError on the subscriber, but should we if
            // Subscriber::onSubscribed has thrown an exception and
            // not completed normally?
            if (where == Where.ON_SUBSCRIBE) continue;

            // Don't know how to make the stack reliably cause onError
            // to be called without closing the connection.
            // And how do we get the exception if onError throws anyway?
            if (where == Where.ON_ERROR) continue;

            if (!sameClient || client == null)
                client = newHttpClient(sameClient);

            HttpRequest req = HttpRequest.
                    newBuilder(URI.create(uri))
                    .build();
            BodyHandler<T> handler =
                    new ThrowingBodyHandler(where.select(thrower), handlers.get());
            System.out.println("try throwing in " + where);
            HttpResponse<T> response = null;
            if (async) {
                try {
                    response = client.sendAsync(req, handler).join();
                } catch (Error | Exception x) {
                    Throwable cause = findCause(x, thrower);
                    if (cause == null) throw causeNotFound(where, x);
                    System.out.println(now() + "Got expected exception: " + cause);
                }
            } else {
                try {
                    response = client.send(req, handler);
                } catch (Error | Exception t) {
                    if (thrower.test(t)) {
                        System.out.println(now() + "Got expected exception: " + t);
                    } else throw causeNotFound(where, t);
                }
            }
            if (response != null) {
                finisher.finish(where, response, thrower);
            }
        }
    }

    enum Where {
        BODY_HANDLER, ON_SUBSCRIBE, ON_NEXT, ON_COMPLETE, ON_ERROR, GET_BODY, BODY_CF;
        public Consumer<Where> select(Consumer<Where> consumer) {
            return new Consumer<Where>() {
                @Override
                public void accept(Where where) {
                    if (Where.this == where) {
                        consumer.accept(where);
                    }
                }
            };
        }
    }

    static AssertionError causeNotFound(Where w, Throwable t) {
        return new AssertionError("Expected exception not found in " + w, t);
    }

    interface Thrower extends Consumer<Where>, Predicate<Throwable> {

    }

    interface Finisher<T,U> {
        U finish(Where w, HttpResponse<T> resp, Thrower thrower) throws IOException;
    }

    final <T,U> U shouldHaveThrown(Where w, HttpResponse<T> resp, Thrower thrower) {
        String msg = "Expected exception not thrown in " + w
                + "\n\tReceived: " + resp
                + "\n\tWith body: " + resp.body();
        System.out.println(msg);
        throw new RuntimeException(msg);
    }

    final List<String> checkAsLines(Where w, HttpResponse<Stream<String>> resp, Thrower thrower) {
        switch(w) {
            case BODY_HANDLER: return shouldHaveThrown(w, resp, thrower);
            case ON_SUBSCRIBE: return shouldHaveThrown(w, resp, thrower);
            case GET_BODY: return shouldHaveThrown(w, resp, thrower);
            case BODY_CF: return shouldHaveThrown(w, resp, thrower);
            default: break;
        }
        List<String> result = null;
        try {
            result = resp.body().collect(Collectors.toList());
        } catch (Error | Exception x) {
            Throwable cause = findCause(x, thrower);
            if (cause != null) {
                out.println(now() + "Got expected exception in " + w + ": " + cause);
                return result;
            }
            throw causeNotFound(w, x);
        }
        throw new RuntimeException("Expected exception not thrown in " + w);
    }

    final List<String> checkAsInputStream(Where w, HttpResponse<InputStream> resp,
                                    Thrower thrower)
            throws IOException
    {
        switch(w) {
            case BODY_HANDLER: return shouldHaveThrown(w, resp, thrower);
            case ON_SUBSCRIBE: return shouldHaveThrown(w, resp, thrower);
            case GET_BODY: return shouldHaveThrown(w, resp, thrower);
            case BODY_CF: return shouldHaveThrown(w, resp, thrower);
            default: break;
        }
        List<String> result = null;
        try (InputStreamReader r1 = new InputStreamReader(resp.body(), UTF_8);
             BufferedReader r = new BufferedReader(r1)) {
            try {
                result = r.lines().collect(Collectors.toList());
            } catch (Error | Exception x) {
                Throwable cause = findCause(x, thrower);
                if (cause != null) {
                    out.println(now() + "Got expected exception in " + w + ": " + cause);
                    return result;
                }
                throw causeNotFound(w, x);
            }
        }
        return shouldHaveThrown(w, resp, thrower);
    }

    private static Throwable findCause(Throwable x,
                                       Predicate<Throwable> filter) {
        while (x != null && !filter.test(x)) x = x.getCause();
        return x;
    }

    static final class UncheckedCustomExceptionThrower implements Thrower {
        @Override
        public void accept(Where where) {
            out.println(now() + "Throwing in " + where);
            throw new UncheckedCustomException(where.name());
        }

        @Override
        public boolean test(Throwable throwable) {
            return UncheckedCustomException.class.isInstance(throwable);
        }

        @Override
        public String toString() {
            return "UncheckedCustomExceptionThrower";
        }
    }

    static final class UncheckedIOExceptionThrower implements Thrower {
        @Override
        public void accept(Where where) {
            out.println(now() + "Throwing in " + where);
            throw new UncheckedIOException(new CustomIOException(where.name()));
        }

        @Override
        public boolean test(Throwable throwable) {
            return UncheckedIOException.class.isInstance(throwable)
                    && CustomIOException.class.isInstance(throwable.getCause());
        }

        @Override
        public String toString() {
            return "UncheckedIOExceptionThrower";
        }
    }

    static final class UncheckedCustomException extends RuntimeException {
        UncheckedCustomException(String message) {
            super(message);
        }
        UncheckedCustomException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class CustomIOException extends IOException {
        CustomIOException(String message) {
            super(message);
        }
        CustomIOException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class ThrowingBodyHandler<T> implements BodyHandler<T> {
        final Consumer<Where> throwing;
        final BodyHandler<T> bodyHandler;
        ThrowingBodyHandler(Consumer<Where> throwing, BodyHandler<T> bodyHandler) {
            this.throwing = throwing;
            this.bodyHandler = bodyHandler;
        }
        @Override
        public BodySubscriber<T> apply(int statusCode, HttpHeaders responseHeaders) {
            throwing.accept(Where.BODY_HANDLER);
            BodySubscriber<T> subscriber = bodyHandler.apply(statusCode, responseHeaders);
            return new ThrowingBodySubscriber(throwing, subscriber);
        }
    }

    static final class ThrowingBodySubscriber<T> implements BodySubscriber<T> {
        private final BodySubscriber<T> subscriber;
        volatile boolean onSubscribeCalled;
        final Consumer<Where> throwing;
        ThrowingBodySubscriber(Consumer<Where> throwing, BodySubscriber<T> subscriber) {
            this.throwing = throwing;
            this.subscriber = subscriber;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            //out.println("onSubscribe ");
            onSubscribeCalled = true;
            throwing.accept(Where.ON_SUBSCRIBE);
            subscriber.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
           // out.println("onNext " + item);
            assertTrue(onSubscribeCalled);
            throwing.accept(Where.ON_NEXT);
            subscriber.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            //out.println("onError");
            assertTrue(onSubscribeCalled);
            throwing.accept(Where.ON_ERROR);
            subscriber.onError(throwable);
        }

        @Override
        public void onComplete() {
            //out.println("onComplete");
            assertTrue(onSubscribeCalled, "onComplete called before onSubscribe");
            throwing.accept(Where.ON_COMPLETE);
            subscriber.onComplete();
        }

        @Override
        public CompletionStage<T> getBody() {
            throwing.accept(Where.GET_BODY);
            try {
                throwing.accept(Where.BODY_CF);
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
            return subscriber.getBody();
        }
    }


    @BeforeTest
    public void setup() throws Exception {
        sslContext = new SimpleSSLContext().get();
        if (sslContext == null)
            throw new AssertionError("Unexpected null sslContext");

        // HTTP/1.1
        HttpTestHandler h1_fixedLengthHandler = new HTTP_FixedLengthHandler();
        HttpTestHandler h1_chunkHandler = new HTTP_ChunkedHandler();
        InetSocketAddress sa = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        httpTestServer = HttpTestServer.of(HttpServer.create(sa, 0));
        httpTestServer.addHandler(h1_fixedLengthHandler, "/http1/fixed");
        httpTestServer.addHandler(h1_chunkHandler, "/http1/chunk");
        httpURI_fixed = "http://" + httpTestServer.serverAuthority() + "/http1/fixed/x";
        httpURI_chunk = "http://" + httpTestServer.serverAuthority() + "/http1/chunk/x";

        HttpsServer httpsServer = HttpsServer.create(sa, 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        httpsTestServer = HttpTestServer.of(httpsServer);
        httpsTestServer.addHandler(h1_fixedLengthHandler, "/https1/fixed");
        httpsTestServer.addHandler(h1_chunkHandler, "/https1/chunk");
        httpsURI_fixed = "https://" + httpsTestServer.serverAuthority() + "/https1/fixed/x";
        httpsURI_chunk = "https://" + httpsTestServer.serverAuthority() + "/https1/chunk/x";

        // HTTP/2
        HttpTestHandler h2_fixedLengthHandler = new HTTP_FixedLengthHandler();
        HttpTestHandler h2_chunkedHandler = new HTTP_ChunkedHandler();

        http2TestServer = HttpTestServer.of(new Http2TestServer("localhost", false, 0));
        http2TestServer.addHandler(h2_fixedLengthHandler, "/http2/fixed");
        http2TestServer.addHandler(h2_chunkedHandler, "/http2/chunk");
        http2URI_fixed = "http://" + http2TestServer.serverAuthority() + "/http2/fixed/x";
        http2URI_chunk = "http://" + http2TestServer.serverAuthority() + "/http2/chunk/x";

        https2TestServer = HttpTestServer.of(new Http2TestServer("localhost", true, 0));
        https2TestServer.addHandler(h2_fixedLengthHandler, "/https2/fixed");
        https2TestServer.addHandler(h2_chunkedHandler, "/https2/chunk");
        https2URI_fixed = "https://" + https2TestServer.serverAuthority() + "/https2/fixed/x";
        https2URI_chunk = "https://" + https2TestServer.serverAuthority() + "/https2/chunk/x";

        serverCount.addAndGet(4);
        httpTestServer.start();
        httpsTestServer.start();
        http2TestServer.start();
        https2TestServer.start();
    }

    @AfterTest
    public void teardown() throws Exception {
        sharedClient = null;
        httpTestServer.stop();
        httpsTestServer.stop();
        http2TestServer.stop();
        https2TestServer.stop();
    }

    static class HTTP_FixedLengthHandler implements HttpTestHandler {
        @Override
        public void handle(HttpTestExchange t) throws IOException {
            out.println("HTTP_FixedLengthHandler received request to " + t.getRequestURI());
            try (InputStream is = t.getRequestBody()) {
                is.readAllBytes();
            }
            byte[] resp = t.getRequestURI().toString().getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, resp.length);  //fixed content length
            try (OutputStream os = t.getResponseBody()) {
                os.write(resp);
            }
        }
    }

    static class HTTP_ChunkedHandler implements HttpTestHandler {
        @Override
        public void handle(HttpTestExchange t) throws IOException {
            out.println("HTTP_ChunkedHandler received request to " + t.getRequestURI());
            byte[] resp = t.getRequestURI().toString().getBytes(StandardCharsets.UTF_8);
            try (InputStream is = t.getRequestBody()) {
                is.readAllBytes();
            }
            t.sendResponseHeaders(200, -1); // chunked/variable
            try (OutputStream os = t.getResponseBody()) {
                os.write(resp);
            }
        }
    }

}
