package com.example.harness.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Keeps a browser walking away from an SSE stream out of the error log.
 *
 * <p>When a tab refreshes or navigates away mid-task the socket dies, and the next event the agent
 * writes fails with a transport error ("你的主机中的软件中止了一个已建立的连接", "Broken pipe",
 * "Connection reset by peer" — the wording is OS and locale specific). The emitter already swallows
 * that on the writing thread, but Spring's {@code LifecycleServletOutputStream} also reports the
 * I/O failure to the container, which dispatches the same exception back through
 * {@code DispatcherServlet} on a request thread. With nothing to resolve it there, it escapes
 * {@code service()} and Tomcat logs a full stack trace for what is simply a closed tab.
 *
 * <p>Returning an empty {@link ModelAndView} marks that dispatch as handled, so the exception is no
 * longer rethrown and no stack trace is printed. The task itself is unaffected: it keeps running,
 * and its events stay buffered in the StreamHub for replay over {@code /live}.
 */
@Component
public class ClientAbortSilencer implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientAbortSilencer.class);

    /** Lower-cased fragments of the usual transport-abort messages across platforms and locales. */
    private static final List<String> ABORT_PHRASES = List.of(
            "broken pipe",
            "connection reset",
            "connection abort",
            "established connection was aborted",
            "an existing connection was forcibly closed",
            "中止了一个已建立的连接",
            "强迫关闭了一个现有的连接");

    private static final int MAX_CAUSE_DEPTH = 10;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    @Nullable
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         @Nullable Object handler, Exception ex) {
        if (!isClientAbort(request, ex)) {
            return null; // 交给后面的解析器按原有逻辑处理
        }
        log.debug("客户端已断开（刷新/关闭页面），忽略该连接的写入失败: {}", ex.getMessage());
        return new ModelAndView(); // 空视图 = 已处理，DispatcherServlet 不再向容器抛出
    }

    private boolean isClientAbort(HttpServletRequest request, @Nullable Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof IOException) {
                // Tomcat 的 ClientAbortException（按类名判断，避免对容器实现的硬依赖）
                if ("ClientAbortException".equals(current.getClass().getSimpleName())) {
                    return true;
                }
                // 异步派发中的 I/O 失败只可能来自连接本身——此时响应早已提交，不是业务异常
                if (request != null && request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;
                }
                if (matchesAbortPhrase(current.getMessage())) {
                    return true;
                }
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return false;
    }

    private static boolean matchesAbortPhrase(@Nullable String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return ABORT_PHRASES.stream().anyMatch(lower::contains);
    }
}
