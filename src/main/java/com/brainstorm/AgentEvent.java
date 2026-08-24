package com.brainstorm;

/**
 * SSE로 흐르는 유일한 데이터 구조. 필드는 SPEC.md 3장 표와 1:1로 대응한다.
 * SESSION_* 이벤트에서는 nodeId가 null이다.
 */
public record AgentEvent(
        long seq,
        String sessionId,
        String nodeId,
        EventType type,
        String content,
        long timestamp
) {
}
