# CLAUDE.md

## 이 프로젝트

실시간 멀티 에이전트 브레인스토밍 룸. 1주일 내 완성이 목표.
**작업 전에 항상 `SPEC.md`를 읽는다.** SPEC과 어긋나는 코드는 쓰지 않는다.

## 기술 스택 (변경 금지)

- 백엔드: Java 21, Spring Boot 3.x, **Spring MVC** (WebFlux 아님), Spring AI + Anthropic
- 프론트: **Vite + React** (Next.js 아님), React Flow, 순수 JS (TypeScript 아님)
- 저장소: Day 1~3 인메모리, Day 4부터 PostgreSQL

## 반드시 지킬 것

- `spring.threads.virtual.enabled=true` 를 켠다. 블로킹 코드를 그대로 쓴다.
- SSE는 `SseEmitter`로 구현한다. `Flux<ServerSentEvent>`를 쓰지 않는다.
- Spring AI의 `.stream()`이 리턴하는 Flux는 `.toStream()`으로 블로킹 소비한다.
- 모든 이벤트는 세션의 이벤트 리스트에 **먼저 append**하고, 그 다음 SSE로 보낸다.
  순서를 바꾸면 재연결 리플레이가 깨진다.
- 프론트 파일은 `App.jsx`, `AgentNode.jsx`, `useSessionStream.js` 3개로 제한한다.
  더 잘게 쪼개지 않는다.
- API 키는 환경변수 `ANTHROPIC_API_KEY`로만 읽는다. 코드나 설정 파일에 넣지 않는다.
- 테스트는 요청받은 것만 작성한다. 새 클래스를 만들 때 테스트를 자동으로 만들지 않는다.
- 테스트는 이벤트 발행 순서, SSE 리플레이, 병렬 조인/부분 실패에만 작성한다.

## 절대 하지 말 것

- Redis, Kafka, RabbitMQ 등 외부 브로커 추가
- WebFlux / Reactor 타입을 컨트롤러 시그니처에 노출
- pgvector, 벡터 검색, RAG
- 도구 호출(tool calling) 기능
- 인증/회원가입
- 새로운 이벤트 타입이나 필드를 SPEC.md 수정 없이 추가
- 요청하지 않은 리팩터링, 폴더 구조 재편, 추상화 계층 추가
- LLM 응답 내용을 단언하는 테스트 작성
- 코드가 테스트와 다르게 동작할 때 테스트 쪽을 고치는 것 (반드시 사람에게 보고한다)

## 작업 방식

- 한 번에 하나의 파일 또는 하나의 기능만 수정한다.
- 코드를 쓰기 전에 무엇을 할지 두세 줄로 먼저 말한다.
- 수정 후에는 어떤 파일이 어떻게 바뀌었는지 요약한다.
- 라이브러리 버전이나 API 시그니처가 불확실하면 추측하지 말고 확인을 요청한다.
- 커밋 메시지는 한국어 한 줄로 쓴다.

## 개발 편의

- `AGENT_STUB=true` 환경변수가 켜져 있으면 실제 LLM을 호출하지 않고
  미리 정해둔 텍스트를 80ms 간격으로 흘려보낸다. 프론트 작업 시 항상 이 모드를 쓴다.
  이 스텁 코드는 프로젝트가 끝날 때까지 지우지 않는다.
