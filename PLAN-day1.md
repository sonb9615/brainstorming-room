# Day 1 — SSE 파이프라인 검증 (백엔드만)

## 배경

프로젝트 디렉터리에 `SPEC.md`와 `CLAUDE.md`만 있고 코드는 없다. Day 1의 목표는
LLM을 붙이기 전에 **SSE 파이프라인 자체가 맞는지** 검증하는 것이다. 즉:

- 이벤트가 seq 순서대로 append되고 SSE로 나가는가
- 3개 노드가 진짜 병렬로 흐르는가 (chunk가 섞여서 도착하는가)
- 끊고 다시 붙었을 때 `Last-Event-ID`로 정확히 이어지는가

그래서 Spring AI는 의존성조차 넣지 않고, 에이전트 자리에 고정 텍스트를 80ms씩
흘리는 스텁을 넣는다. 이 스텁은 CLAUDE.md 지침대로 프로젝트가 끝날 때까지 남긴다
(Day 2에 `AGENT_STUB` 분기의 스텁 쪽 구현이 그대로 된다).

## 전제

- 빌드: Maven. `mvn`이 없으므로 설치 필요. 설치 후 `mvn wrapper:wrapper`로
  `mvnw`를 만들어 둔다.
- JDK: 현재 17/11만 설치됨. 코드와 `pom.xml`은 **Java 21 기준**으로 작성한다.
  `brew install openjdk@21` 은 사용자가 직접 실행.
  → 코드는 다 나오지만 **컴파일/실행 검증은 JDK 21 설치 후**에 가능하다.

## 만들 파일

```
pom.xml
src/main/resources/application.properties
src/main/java/com/brainstorm/
  BrainstormingRoomApplication.java
  EventType.java
  AgentEvent.java
  Session.java
  SessionService.java
  StubAgentRunner.java
  SessionController.java
```

### `pom.xml`
`spring-boot-starter-parent` 3.3.x, `java.version=21`, 의존성은
`spring-boot-starter-web` 하나뿐. Spring AI / Anthropic 관련 없음.

### `application.properties`
```
spring.threads.virtual.enabled=true
server.port=8080
```

### `EventType.java`
SPEC 3장의 7개 값만. 추가 금지.
`SESSION_STARTED, NODE_STARTED, NODE_CHUNK, NODE_COMPLETED, NODE_FAILED,
SESSION_COMPLETED, SESSION_FAILED`

### `AgentEvent.java`
record. 필드 순서·이름을 SPEC 3장 표와 1:1로 맞춘다.
```java
record AgentEvent(long seq, String sessionId, String nodeId,
                  EventType type, String content, long timestamp)
```
`nodeId`는 `SESSION_*` 이벤트에서 `null`. enum은 Jackson이 이름 문자열로 직렬화되므로
계약과 일치한다.

### `Session.java` — 여기가 핵심
보유 상태:
- `UUID id`, `String idea`, `volatile Status status`, `Instant createdAt`
- `List<AgentEvent> events` (append-only)
- `long seqCounter` (1부터, lock 아래에서만 증가)
- `List<SseEmitter> emitters`
- `private final Object lock = new Object()`

두 개의 메서드가 **같은 lock**을 잡는 것이 이 설계의 전부다:

```
append(nodeId, type, content):
  synchronized (lock) {
    event = new AgentEvent(++seqCounter, ...)
    events.add(event)            // ← 항상 먼저 (CLAUDE.md 규칙)
    각 emitter에 send(id=seq, data=json)   // ← 그 다음
  }

subscribe(lastEventId) -> SseEmitter:
  synchronized (lock) {
    seq > lastEventId 인 이벤트 전부 emitter로 send   // 리플레이
    if (status == RUNNING) emitters.add(emitter)
    else emitter.complete()      // 종료된 세션은 리플레이 후 닫음
  }
```

같은 lock이라 **리플레이와 라이브 등록 사이에 이벤트가 끼어들 수 없다.**
락 없이 하면 그 틈에 발행된 이벤트가 누락되거나 중복된다 — Day 1에서 검증하려는
게 정확히 이 부분이다.

`send` 실패한 emitter는 그 자리에서 리스트에서 제거한다 (브라우저 탭 닫힘 등).

### `SessionService.java`
- `ConcurrentHashMap<UUID, Session> sessions`
- `create(idea)`: Session 생성 → 맵에 저장 → `StubAgentRunner`를 가상 스레드로 실행 →
  sessionId 반환
- `cancel(id)`: 실행 스레드 interrupt, status = CANCELLED,
  `SESSION_FAILED("사용자가 취소함")` append, emitter 전부 complete
- 하트비트: `ScheduledExecutorService` 하나로 15초마다 전 세션 emitter에
  `SseEmitter.event().comment("ping")` 발송 (`:ping` 줄이 나감).
  `@PreDestroy`에서 shutdown.

### `StubAgentRunner.java`
Day 1의 가짜 실행. 흐름:

1. `SESSION_STARTED` (content = idea)
2. SECURITY / CFO / ARCHITECT 3개를 **각각 가상 스레드**로 동시에 시작
   (`Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll`)
   각 노드: `NODE_STARTED` → 고정 텍스트를 조각내어 80ms 간격 `NODE_CHUNK` →
   `NODE_COMPLETED`(전체 텍스트)
3. 3개 모두 끝나면 MODERATOR 동일 방식 실행
4. `SESSION_COMPLETED`, status = COMPLETED

**병렬이 눈에 보이게** 하는 방법: 청크 간격은 SPEC대로 80ms 고정, 대신 텍스트 길이를
다르게 해서 끝나는 시점을 벌린다.
- ARCHITECT — 가장 먼저 끝남
- SECURITY — 중간
- CFO — 가장 늦게 끝남
- MODERATOR — 마지막에 단독 실행

스트림을 보면 세 노드의 chunk가 서로 섞여 나오고, DONE이 되는 순서가 갈린다.

`InterruptedException`은 취소 신호로 보고 조용히 빠져나온다.

### `SessionController.java`
- `POST /api/sessions` → `201 {"sessionId": "..."}`
  요청/응답 DTO는 이 파일 안에 중첩 record로 둔다 (파일 수 최소화)
- `GET /api/sessions/{id}/stream` → `SseEmitter` (timeout 무제한 = `Long.MAX_VALUE`)
  응답 헤더에 `X-Accel-Buffering: no` 필수. `@RequestHeader(value="Last-Event-ID",
  required=false)` 로 이어받기. 없는 세션이면 404.
- `DELETE /api/sessions/{id}` → 204

컨트롤러 시그니처에 Reactor 타입 없음. 리턴은 `SseEmitter` / `ResponseEntity`뿐.

## 검증 방법

JDK 21 설치 후:

```bash
./mvnw spring-boot:run
```

터미널 2개로:

```bash
# 1) 세션 생성
curl -s -XPOST localhost:8080/api/sessions \
  -H 'Content-Type: application/json' \
  -d '{"idea":"동네 헬스장 PT 예약 앱"}'

# 2) 스트림 구독 (병렬 확인)
curl -N localhost:8080/api/sessions/<id>/stream
```

확인할 것:
1. `seq`가 1부터 빠짐없이 증가하는가
2. SECURITY/CFO/ARCHITECT의 `NODE_CHUNK`가 **서로 섞여서** 나오는가
   → 섞이지 않으면 병렬이 아니라는 뜻
3. `NODE_COMPLETED` 도착 순서가 ARCHITECT → SECURITY → CFO 인가
4. MODERATOR의 `NODE_STARTED`가 앞 3개 `NODE_COMPLETED` **뒤에** 오는가
5. 리플레이: 세션 끝난 뒤 다시 `curl -N` → 1번부터 전부 나오고 스트림이 닫히는가
6. 이어받기: `curl -N -H 'Last-Event-ID: 15' .../stream` → seq 16부터 나오는가
7. 하트비트: 실행 중 세션에 붙어 15초 대기 → `:ping` 나오는가
8. 취소: 세션 생성 직후 `curl -XDELETE` → `SESSION_FAILED`로 끝나는가

## 하지 않는 것

- Spring AI 의존성, LLM 호출, 프롬프트
- 프론트엔드 파일
- 영속화 (Day 4)
- 테스트 코드 (요청에 없음)
