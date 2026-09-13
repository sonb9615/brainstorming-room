package com.brainstorm;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public record CreateSessionRequest(String idea) {
    }

    public record CreateSessionResponse(String sessionId) {
    }

    @PostMapping
    public ResponseEntity<CreateSessionResponse> create(@RequestBody CreateSessionRequest request) {
        UUID sessionId = sessionService.create(request.idea());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateSessionResponse(sessionId.toString()));
    }

    /**
     * Last-Event-ID가 있으면 그 seq 다음부터, 없으면 seq 1부터 리플레이한 뒤
     * 라이브 이벤트로 이어진다. 이미 끝난 세션이면 리플레이 후 스트림을 닫는다.
     */
    @GetMapping("/{sessionId}/stream")
    public ResponseEntity<SseEmitter> stream(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        Session session = sessionService.find(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        session.subscribe(emitter, parseLastEventId(lastEventId));

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                // 프록시가 SSE를 버퍼링하지 못하게 막는다(SPEC.md 4장).
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(emitter);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID sessionId) {
        if (sessionService.find(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        sessionService.cancel(sessionId);
        return ResponseEntity.noContent().build();
    }

    private long parseLastEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
