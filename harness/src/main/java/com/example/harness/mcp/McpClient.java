package com.example.harness.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端传输抽象（stdio 扩展引入）：协议方法（initialize / tools/list / tools/call）
 * 在所有传输间一致，区别仅在帧载体——Streamable HTTP（POST + SSE 帧）或
 * 本地子进程 stdio（stdin/stdout 行分隔 JSON）。
 * McpConnectionManager 只依赖本接口；新增传输实现它即可接入。
 */
public interface McpClient {

    /** 工具调用结果：content 数组（原样）与 isError 标记。 */
    record CallResult(List<Map<String, Object>> content, boolean isError) {
    }

    /** initialize 握手并记录会话；随后发送 initialized 通知。返回 serverInfo+capabilities 结果。 */
    Map<String, Object> initialize() throws Exception;

    /** tools/list：返回工具清单（name/description/inputSchema）。 */
    List<Map<String, Object>> listTools() throws Exception;

    /** 工具调用：返回 content 数组与 isError 标记。 */
    CallResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    /** 关闭连接（stdio 传输须优雅终止子进程）。幂等。 */
    void close();
}
