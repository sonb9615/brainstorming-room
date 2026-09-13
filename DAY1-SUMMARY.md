# Day 1 작업 — SSE 파이프라인 검증 + 그래프 시각화

## 1. 개요

**목표**: SPEC.md 1장에 정의된 제품 — "아이디어를 입력하면 3명의 AI 에이전트가
동시에 검토하고 1명이 종합하는 과정이 화면에 실시간으로 보이는 것" — 중에서
**LLM 호출 없이 SSE 파이프라인과 화면 시각화가 맞는지부터 검증**하는 것이
Day 1의 목표.

**범위**: SPEC.md 7장이 이번 주 스코프 아웃으로 명시한 도구 호출, RAG,
인증, 동적 그래프, Redis/큐, 멀티턴 대화는 전부 제외했다. 실제 LLM
(Spring AI + Anthropic) 연동도 아직 하지 않았다.

**결과물**: 백엔드 SSE 파이프라인(Spring Boot) + 프론트엔드 React Flow
그래프 시각화(4단계)까지 완료.

## 2. 백엔드 — SSE 파이프라인

기술 스택: Java 21, Spring Boot 3.x, Spring MVC. 가상 스레드
(`spring.threads.virtual.enabled=true`)로 블로킹 코드를 그대로 사용했다.

| 파일 | 역할 | 근거 |
|---|---|---|
| `EventType.java` | SPEC 3장 표의 7개 이벤트 타입만 정의 | SPEC.md 3장 — 계약에 없는 타입 추가 금지 |
| `AgentEvent.java` | `(seq, sessionId, nodeId, type, content, timestamp)` record | SPEC.md 3장 JSON 스키마와 필드 1:1 대응 |
| `Session.java` | 이벤트 append + SSE 발행을 **같은 lock**으로 묶음 | CLAUDE.md "이벤트는 세션 리스트에 먼저 append하고, 그 다음 SSE로 보낸다. 순서를 바꾸면 재연결 리플레이가 깨진다" |
| `StubAgentRunner.java` | 실제 LLM 대신 고정 텍스트를 80ms 간격 청크로 발행 | CLAUDE.md "AGENT_STUB=true면 미리 정해둔 텍스트를 80ms 간격으로 흘려보낸다. 프로젝트가 끝날 때까지 지우지 않는다" |
| `SessionService.java` | 세션당 가상 스레드 실행 + 15초 하트비트 | `spring.threads.virtual.enabled=true` 설정, SPEC.md 4장 "15초마다 `:ping`" |
| `SessionController.java` | `SseEmitter` 기반 3개 API | CLAUDE.md "SSE는 SseEmitter로 구현한다. Flux&lt;ServerSentEvent&gt;를 쓰지 않는다" |

노드 간 속도차(ARCHITECT 최단 → SECURITY → CFO 최장)는 SPEC.md 2장의 "완전
병렬" 그래프 구조를 curl 스트림에서 눈으로 확인하기 위한 장치였다.

### 검증 결과 (curl로 실측)

- `seq`가 1부터 빠짐없이 증가
- SECURITY/CFO/ARCHITECT의 `NODE_CHUNK`가 서로 섞여서 도착 (병렬 확인)
- 완료 순서: ARCHITECT → SECURITY → CFO
- MODERATOR는 3개가 전부 `NODE_COMPLETED`된 **뒤에만** 시작
  (SPEC.md 2장 "MODERATOR는 3개가 모두 성공해야 실행된다")
- 세션 종료 후 재요청 시 전체 리플레이 후 스트림 정상 종료
- `Last-Event-ID` 헤더로 중간부터 재개
- `DELETE`로 취소 시 `SESSION_FAILED`로 정상 종료
- 실행 중인 세션에 15초 이상 붙어 있으면 `:ping` 하트비트 수신

## 3. 프론트엔드 — 단계별 진행

기술 스택: Vite + React, 순수 JS. CLAUDE.md가 허용한 프론트 파일 3개
(`App.jsx`, `AgentNode.jsx`, `useSessionStream.js`) 범위 안에서만 작업했다.

### 1단계 — App.jsx만으로 SSE 원문 확인
`fetch`로 세션 생성 → `EventSource`로 스트림 구독 → 받은 이벤트를 그대로
`<pre>`에 한 줄씩 나열. **SSE 자체가 브라우저에서 정상 동작하는지**부터 확인하하기 위함.

### 2단계 — useSessionStream.js 훅 분리
`useReducer` 하나로 `{ lastSeq, nodes }`를 관리하도록 이벤트 처리 로직을
훅으로 분리했다. *근거: SPEC.md 5장 "노드 상태를 따로 저장하지 않는다.
필요하면 이벤트를 접어서(fold) 계산한다"* — 이 훅이 바로 그 fold 로직이다.
`SESSION_STARTED`에서 전체 노드를 IDLE로 초기화하는 것도 SPEC.md 3장 이벤트
타입 표에 명시된 프론트 동작을 그대로 따른 것이다.

세션을 다시 시작할 때 이전 `lastSeq`가 남아 새 세션의 이벤트가 전부
무시되는 문제가 있어, 훅 내부적으로 `RESET` 액션(서버 이벤트 타입 아님,
내부 전용)을 두어 해결했다.

### 3단계 — AgentNode.jsx + React Flow 그래프
`@xyflow/react` v12(신규 패키지명, 구 `reactflow` 아님)로 SPEC.md 2장의
그래프 구조를 그대로 그렸다. 노드 색은 SPEC.md 6장 상태-색 매핑표
(IDLE 회색 / THINKING 파랑 / DONE 초록 / FAILED 빨강)를 그대로 옮겼고,
출발 노드가 DONE이 되면 엣지가 `animated: true`가 되는 것도 SPEC.md 6장
규칙이다. `nodes`/`edges` 배열은 훅 상태에서 `useMemo`로 파생시켜 상태를
이중으로 두지 않았다.

### 3.1단계 — 노드 클릭 시 전체 답변 패널
3단계에서 텍스트를 6줄로 자르도록 만들었는데(원 요청사항), 실제 확인해보니
CFO처럼 답변이 긴 노드는 내용이 끝까지 안 보이는 문제가 있었다. 그래프
레이아웃(고정 좌표)을 안 건드리면서 전체 내용을 볼 수 있도록, 노드를 클릭하면
그래프 아래에 전체 텍스트 패널이 뜨는 방식을 추가했다. `selectedNodeId`는
스트림 상태(`nodes`)와 무관한 순수 UI 상태라 "상태 이중관리 금지" 원칙과
충돌하지 않는다.
