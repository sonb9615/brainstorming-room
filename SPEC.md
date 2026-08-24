# SPEC.md — 실시간 멀티 에이전트 브레인스토밍 룸

이 문서는 백엔드와 프론트엔드가 공유하는 **계약**이다.
이 문서에 없는 필드/이벤트/엔드포인트는 만들지 않는다.
변경이 필요하면 코드보다 이 문서를 먼저 고친다.

---

## 1. 제품 한 줄 정의

사용자가 아이디어를 한 문장 입력하면, 서로 다른 관점의 AI 에이전트 3명이
**동시에** 검토하고, 마지막에 1명이 종합한다. 그 과정 전체가 화면의 그래프에서
실시간으로 보인다.

---

## 2. 에이전트 그래프 (하드코딩. 이번 주에 일반화하지 않는다)

```
                 ┌─────────────┐
                 │  SECURITY   │
                 └─────────────┘
                        │
[USER_INPUT] ──┬─ ┌─────────────┐ ─┬──▶ [MODERATOR]
               ├─ │     CFO     │  │
               │  └─────────────┘  │
               │  ┌─────────────┐  │
               └─ │  ARCHITECT  │ ─┘
                  └─────────────┘
```

| 노드 ID | 표시 이름 | 모델 | 역할 프롬프트 요지 |
|---|---|---|---|
| `USER_INPUT` | 아이디어 | - | 노드가 아니라 시작점. LLM 호출 없음 |
| `SECURITY` | 보안 담당자 | haiku | 보안·프라이버시 리스크 2가지를 지적 |
| `CFO` | CFO | haiku | 예상 비용과 수익 타당성을 평가 |
| `ARCHITECT` | 아키텍트 | haiku | 구현 난이도와 기술적 병목을 지적 |
| `MODERATOR` | 종합 | sonnet | 위 3개 의견을 받아 최종 결론과 권고안 작성 |

- `SECURITY`, `CFO`, `ARCHITECT`는 **서로를 참조하지 않는다.** 완전 병렬.
- `MODERATOR`는 3개가 **모두** 성공해야 실행된다.
- 3개 중 하나라도 실패하면 `MODERATOR`는 실패한 노드를 제외하고 실행한다.
  전부 실패한 경우에만 세션 전체가 `FAILED`.

---

## 3. 이벤트 스키마 (가장 중요한 계약)

SSE로 흐르는 유일한 데이터 구조. 백엔드는 이것만 발행하고,
프론트는 이것만 해석한다.

```json
{
  "seq": 42,
  "sessionId": "8f3c1e2a-...",
  "nodeId": "SECURITY",
  "type": "NODE_STARTED",
  "content": null,
  "timestamp": 1735000000000
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `seq` | long | **세션 내 1부터 증가하는 정수.** 재연결 시 이 값 기준으로 이어받는다 |
| `sessionId` | string | UUID |
| `nodeId` | string | `SECURITY` \| `CFO` \| `ARCHITECT` \| `MODERATOR` |
| `type` | string | 아래 표 참조 |
| `content` | string \| null | 타입에 따라 의미가 다름 |
| `timestamp` | long | epoch millis |

### 이벤트 타입

| type | content 의미 | 프론트 동작 |
|---|---|---|
| `SESSION_STARTED` | 사용자가 입력한 아이디어 원문 | 전체 그래프를 IDLE 상태로 초기화 |
| `NODE_STARTED` | `null` | 해당 노드를 THINKING 상태로 (파란 테두리 + 펄스) |
| `NODE_CHUNK` | 텍스트 조각 (누적 아님, 증분) | 해당 노드의 텍스트 버퍼에 append |
| `NODE_COMPLETED` | 해당 노드의 전체 최종 텍스트 | 노드를 DONE 상태로 (초록). 버퍼를 이 값으로 교체 |
| `NODE_FAILED` | 사용자에게 보여줄 에러 메시지 | 노드를 FAILED 상태로 (빨강) |
| `SESSION_COMPLETED` | `null` | 실행 종료. 재생 버튼 활성화 |
| `SESSION_FAILED` | 에러 메시지 | 실행 종료 |

**규칙**
- `NODE_CHUNK`는 **증분**이다. 프론트는 이어붙이기만 한다.
- `NODE_COMPLETED`의 `content`는 그 노드의 **전체** 텍스트다.
  청크를 놓쳤을 경우를 대비한 안전장치이므로, 프론트는 버퍼를 통째로 교체한다.
- 백엔드는 `NODE_CHUNK`를 토큰마다 보내지 않는다. **80ms 단위로 묶어서** 보낸다.
- SSE의 `id:` 필드에 `seq`를 넣는다. (재연결 시 `Last-Event-ID`로 돌아온다)

---

## 4. API

### `POST /api/sessions`
아이디어를 받아 세션을 생성하고 **즉시 실행을 시작**한다.

```
Request:  { "idea": "동네 헬스장 PT 예약 앱" }
Response: 201 { "sessionId": "8f3c1e2a-..." }
```

### `GET /api/sessions/{sessionId}/stream`
`Content-Type: text/event-stream`

- 헤더에 `X-Accel-Buffering: no`를 반드시 포함한다.
- 요청에 `Last-Event-ID` 헤더가 있으면 **그 seq 다음 것부터** 보낸다.
  없으면 **seq 1부터 전부** 보낸다. (리플레이)
- 리플레이가 끝나면 라이브 이벤트로 자연스럽게 이어진다.
- 이미 종료된 세션이면 전부 리플레이한 뒤 스트림을 닫는다.
- 15초마다 `:ping` 주석 줄을 보낸다.

### `DELETE /api/sessions/{sessionId}`
실행 중인 세션을 취소한다. `SESSION_FAILED` 이벤트로 마무리된다.

---

## 5. 상태 저장

세션의 **진실의 원천은 이벤트 리스트**다.
노드 상태를 따로 저장하지 않는다. 필요하면 이벤트를 접어서(fold) 계산한다.

```
Session {
  id: UUID
  idea: String
  status: RUNNING | COMPLETED | FAILED | CANCELLED
  events: List<AgentEvent>   // append-only
  createdAt: Instant
}
```

Day 1~3은 `ConcurrentHashMap<UUID, Session>` 인메모리로 충분하다.
Day 4에서 Postgres(`events`는 JSONB 컬럼)로 옮긴다.

---

## 6. 프론트 상태 매핑

| 노드 상태 | 색 | 트리거 |
|---|---|---|
| IDLE | 회색 | 초기값 |
| THINKING | 파랑 + 테두리 펄스 | `NODE_STARTED` |
| DONE | 초록 | `NODE_COMPLETED` |
| FAILED | 빨강 | `NODE_FAILED` |

엣지는 **출발 노드가 DONE이 되는 순간** `animated: true`가 된다.

---

## 7. 이번 주에 만들지 않는 것 (명시적 스코프 아웃)

- 도구 호출(tool calling) / 함수 실행
- RAG, 벡터 검색, pgvector
- 사용자 인증, 회원가입
- 그래프 구조의 동적 정의 (하드코딩 유지)
- Redis, 메시지 큐, 멀티 인스턴스
- 대화 이어가기 (한 세션 = 한 번 실행)
