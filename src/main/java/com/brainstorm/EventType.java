package com.brainstorm;

/** SPEC.md 3장의 이벤트 타입. SPEC 수정 없이 값을 추가하지 않는다. */
public enum EventType {
    SESSION_STARTED,
    NODE_STARTED,
    NODE_CHUNK,
    NODE_COMPLETED,
    NODE_FAILED,
    SESSION_COMPLETED,
    SESSION_FAILED
}
