package com.example.harness.core.stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

class SafeSseEmitterTest {

    @Test void completingTwiceOnlyDispatchesOnce() {
        SafeSseEmitter emitter = new SafeSseEmitter(1000L);
        assertFalse(emitter.isDead());

        emitter.complete();
        assertTrue(emitter.isDead());
        assertDoesNotThrow(emitter::complete, "重复 complete 不得再次触发异步重派发");
    }

    @Test void sendingAfterTheStreamIsGoneIsASilentNoOp() {
        SafeSseEmitter emitter = new SafeSseEmitter(1000L);
        emitter.complete();

        // 普通 SseEmitter 在此处会抛 IllegalStateException，任务循环里就会变成满屏报错。
        assertDoesNotThrow(() -> emitter.send(SseEmitter.event().data("ignored")));
        assertDoesNotThrow(() -> emitter.send("ignored"));
    }

    @Test void plainEmittersAndNullsCountAsAlive() {
        assertTrue(SafeSseEmitter.isAlive(null));
        assertTrue(SafeSseEmitter.isAlive(new SseEmitter(1000L)));
        SafeSseEmitter dead = new SafeSseEmitter(1000L);
        dead.complete();
        assertFalse(SafeSseEmitter.isAlive(dead));
    }
}
