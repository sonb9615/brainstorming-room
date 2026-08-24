package com.brainstorm;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Day 1~3은 인메모리로 충분하다(SPEC.md 5장). Day 4에서 Postgres로 옮긴다. */
@Service
public class SessionService {

    private static final long HEARTBEAT_SECONDS = 15;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final StubAgentRunner runner;

    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public SessionService(StubAgentRunner runner) {
        this.runner = runner;
        heartbeat.scheduleAtFixedRate(this::pingAll,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** 세션을 만들고 즉시 백그라운드 실행을 시작한다. */
    public UUID create(String idea) {
        Session session = new Session(UUID.randomUUID(), idea);
        sessions.put(session.getId(), session);

        Thread.ofVirtual()
                .name("session-" + session.getId())
                .start(() -> {
                    session.setRunnerThread(Thread.currentThread());
                    runner.run(session);
                });

        return session.getId();
    }

    public Optional<Session> find(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 실행 중인 세션을 취소한다. SESSION_FAILED로 마무리된다(SPEC.md 4장). */
    public void cancel(UUID sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null || session.getStatus() != Session.Status.RUNNING) {
            return;
        }
        session.setStatus(Session.Status.CANCELLED);
        session.interruptRunner();
        session.append(null, EventType.SESSION_FAILED, "사용자가 실행을 취소했습니다.");
        session.closeAllEmitters();
    }

    private void pingAll() {
        for (Session session : sessions.values()) {
            session.sendHeartbeat();
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
    }
}
