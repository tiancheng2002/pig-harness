package com.example.harness.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ClientAbortSilencerTest {

    private final ClientAbortSilencer silencer = new ClientAbortSilencer();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private MockHttpServletRequest request(DispatcherType type) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(type);
        return request;
    }

    @Test void windowsAbortDuringAsyncDispatchIsSwallowed() {
        IOException abort = new IOException("你的主机中的软件中止了一个已建立的连接。");

        assertNotNull(silencer.resolveException(request(DispatcherType.ASYNC), response, null, abort),
                "刷新页面导致的写入失败不应打印堆栈");
    }

    @Test void brokenPipeIsRecognizedOutsideAsyncDispatchToo() {
        assertNotNull(silencer.resolveException(request(DispatcherType.REQUEST), response, null,
                new IOException("Broken pipe")));
        assertNotNull(silencer.resolveException(request(DispatcherType.REQUEST), response, null,
                new IllegalStateException("wrapped", new IOException("Connection reset by peer"))));
    }

    @Test void realApplicationFailuresStillPropagate() {
        assertNull(silencer.resolveException(request(DispatcherType.REQUEST), response, null,
                new IllegalStateException("模型档案不存在")),
                "业务异常必须交给后续解析器，不能被静音");
        assertNull(silencer.resolveException(request(DispatcherType.REQUEST), response, null,
                new IOException("db/harness.db (拒绝访问)")),
                "普通 I/O 失败不是客户端断开");
    }

    @Test void aCauseCycleDoesNotHang() {
        IOException a = new IOException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertDoesNotThrow(() ->
                silencer.resolveException(request(DispatcherType.REQUEST), response, null, b));
    }
}
