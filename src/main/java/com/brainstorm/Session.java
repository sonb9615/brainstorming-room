package com.brainstorm;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 세션의 진실의 원천은 events 리스트다(SPEC.md 5장). 노드 상태는 따로 저장하지 않는다.
 *
 * <p>append()와 subscribe()가 같은 lock을 잡는 것이 이 클래스의 핵심이다.
 * 그래야 "리플레이 → 라이브 등록" 사이에 이벤트가 끼어들어 누락되거나
 * 중복되는 일이 없다.
 */
public class Session {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    private final UUID id;
    private final String idea;
    private final Instant createdAt = Instant.now();

    private final Object lock = new Object();
    private final List<AgentEvent> events = new ArrayList<>();
    private final List<SseEmitter> emitters = new ArrayList<>();
    private long seqCounter = 0;
    private volatile Status status = Status.RUNNING;

    /** 취소 시 interrupt할 실행 스레드. */
    private volatile Thread runnerThread;

    public Session(UUID id, String idea) {
        this.id = id;
        this.idea = idea;
    }

    public UUID getId() {
        return id;
    }

    public String getIdea() {
        return idea;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setRunnerThread(Thread runnerThread) {
        this.runnerThread = runnerThread;
    }

    public void interruptRunner() {
        Thread t = runnerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 이벤트를 리스트에 먼저 append하고, 그 다음 구독자에게 보낸다.
     * 이 순서를 바꾸면 재연결 리플레이가 깨진다.
     */
    public void append(String nodeId, EventType type, String content) {
        synchronized (lock) {
            AgentEvent event = new AgentEvent(
                    ++seqCounter,
                    id.toString(),
                    nodeId,
                    type,
                    content,
                    System.currentTimeMillis()
            );
            events.add(event);
            broadcast(event);
        }
    }

    /**
     * lastEventId 다음 seq부터 리플레이한 뒤, 세션이 실행 중이면 라이브 구독자로 등록한다.
     * 이미 끝난 세션이면 리플레이만 하고 스트림을 닫는다.
     */
    public void subscribe(SseEmitter emitter, long lastEventId) {
        synchronized (lock) {
            try {
                for (AgentEvent event : events) {
                    if (event.seq() > lastEventId) {
                        send(emitter, event);
                    }
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
                return;
            }

            if (status == Status.RUNNING) {
                emitters.add(emitter);
                emitter.onCompletion(() -> removeEmitter(emitter));
                emitter.onTimeout(() -> removeEmitter(emitter));
                emitter.onError(e -> removeEmitter(emitter));
            } else {
                emitter.complete();
            }
        }
    }

    /** 15초 하트비트. SSE 주석 줄(`:ping`)이라 프론트는 아무것도 하지 않는다. */
    public void sendHeartbeat() {
        synchronized (lock) {
            for (Iterator<SseEmitter> it = emitters.iterator(); it.hasNext(); ) {
                SseEmitter emitter = it.next();
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    it.remove();
                }
            }
        }
    }

    /** 세션 종료 시 남아 있는 구독자를 모두 닫는다. */
    public void closeAllEmitters() {
        synchronized (lock) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // 이미 닫힌 스트림
                }
            }
            emitters.clear();
        }
    }

    private void removeEmitter(SseEmitter emitter) {
        synchronized (lock) {
            emitters.remove(emitter);
        }
    }

    private void broadcast(AgentEvent event) {
        for (Iterator<SseEmitter> it = emitters.iterator(); it.hasNext(); ) {
            SseEmitter emitter = it.next();
            try {
                send(emitter, event);
            } catch (IOException | IllegalStateException e) {
                it.remove();
            }
        }
    }

    /** SSE의 id: 필드에 seq를 넣는다. 재연결 시 Last-Event-ID로 돌아온다. */
    private void send(SseEmitter emitter, AgentEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.seq()))
                .data(event, MediaType.APPLICATION_JSON));
    }
}
