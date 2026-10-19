package com.example.harness.core.stream;

import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link SseEmitter} that stops touching the socket once the client is gone.
 *
 * <p>A task keeps running after its browser tab refreshes or navigates away, so the loop goes on
 * emitting events into a connection nobody is reading. The first write fails with
 * "你的主机中的软件中止了一个已建立的连接" (WSAECONNABORTED) and every later write fails the same way;
 * worse, the final {@code complete()} makes Spring re-dispatch the async request onto a container
 * thread, where the failing write escapes {@code DispatcherServlet} and Tomcat logs it as an
 * unhandled servlet exception. None of that is a real fault — the task itself is fine and the
 * events are still buffered in the {@link StreamHub} for replay — but it fills the console.
 *
 * <p>This emitter records the death once (from a failed write or from Spring's own
 * error/timeout/completion callbacks) and afterwards makes {@code send} a no-op and {@code complete}
 * a no-op, so no dead socket is ever written to or re-dispatched.
 */
public class SafeSseEmitter extends SseEmitter {

    private final AtomicBoolean dead = new AtomicBoolean(false);

    public SafeSseEmitter(Long timeout) {
        super(timeout);
        onError(error -> dead.set(true));
        onTimeout(() -> dead.set(true));
        onCompletion(() -> dead.set(true));
    }

    /** True once the client has gone away (or the stream was completed). */
    public boolean isDead() {
        return dead.get();
    }

    /** True when the emitter is still worth writing to. */
    public static boolean isAlive(@Nullable Object emitter) {
        return !(emitter instanceof SafeSseEmitter safe) || !safe.isDead();
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        if (dead.get()) return;
        try {
            super.send(builder);
        } catch (Exception e) {
            dead.set(true);
            throw asIoException(e);
        }
    }

    @Override
    public void send(Object object) throws IOException {
        send(object, null);
    }

    @Override
    public void send(Object object, @Nullable MediaType mediaType) throws IOException {
        if (dead.get()) return;
        try {
            super.send(object, mediaType);
        } catch (Exception e) {
            dead.set(true);
            throw asIoException(e);
        }
    }

    @Override
    public void complete() {
        // Completing a dead emitter is what triggers the async re-dispatch that Tomcat then logs.
        if (dead.getAndSet(true)) return;
        try {
            super.complete();
        } catch (Exception ignore) {
            // The container may already have torn the exchange down.
        }
    }

    @Override
    public void completeWithError(Throwable ex) {
        if (dead.getAndSet(true)) return;
        try {
            super.completeWithError(ex);
        } catch (Exception ignore) {
            // As above: nothing useful can be reported on a broken exchange.
        }
    }

    private static IOException asIoException(Exception e) {
        return e instanceof IOException io ? io : new IOException(e);
    }
}
