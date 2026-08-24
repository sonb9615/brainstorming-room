package com.brainstorm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * LLM 없이 실행 흐름만 재현하는 스텁. AGENT_STUB 모드의 구현체이며,
 * 프로젝트가 끝날 때까지 지우지 않는다.
 *
 * <p>SECURITY / CFO / ARCHITECT는 각각 가상 스레드에서 동시에 흐르고,
 * 셋이 모두 끝나야 MODERATOR가 시작한다. 텍스트 길이를 다르게 두어
 * 끝나는 시점이 벌어지도록 했다 — 스트림에서 청크가 섞여 나오는지로
 * 병렬 여부를 눈으로 확인할 수 있다.
 */
@Component
public class StubAgentRunner {

    /** SPEC.md 3장: 청크는 토큰마다가 아니라 80ms 단위로 묶어서 보낸다. */
    private static final long CHUNK_INTERVAL_MS = 80;
    private static final int CHUNK_SIZE = 6;

    private static final List<String> PARALLEL_NODES = List.of("SECURITY", "CFO", "ARCHITECT");

    private static final Map<String, String> STUB_TEXTS = Map.of(
            // 가장 짧다 → 가장 먼저 DONE
            "ARCHITECT", """
                    구현 난이도는 중간입니다. 예약 충돌 처리와 트레이너 스케줄 동기화가 \
                    실질적인 병목입니다.""",

            "SECURITY", """
                    보안 리스크 두 가지를 짚습니다. 첫째, 회원의 건강 정보와 신체 계측 \
                    데이터는 민감정보이므로 별도 암호화 저장이 필요합니다. 둘째, 트레이너 \
                    계정이 담당하지 않는 회원의 기록까지 조회할 수 있는 권한 설계는 위험합니다.""",

            // 가장 길다 → 가장 늦게 DONE
            "CFO", """
                    비용과 수익 타당성을 봅니다. 초기 개발비는 3인 기준 2개월, 약 6천만 원을 \
                    예상합니다. 운영비는 서버와 알림 발송을 합쳐 월 80만 원 수준입니다. \
                    수익 모델은 헬스장당 월 구독료가 현실적이며, 손익분기를 넘으려면 최소 \
                    120개 지점이 필요합니다. 문제는 이 시장의 이탈률이 높다는 점이고, \
                    영업 비용을 함께 계산하지 않으면 회수 기간이 크게 늘어납니다.""",

            "MODERATOR", """
                    세 관점을 종합합니다. 기술적으로는 실현 가능하나 예약 충돌 처리가 핵심 \
                    난제이고, 보안 측면에서는 건강 정보 취급과 권한 분리를 초기 설계에 \
                    반드시 반영해야 합니다. 사업성은 지점 확보 속도에 전적으로 달려 있습니다. \
                    권고안은 단일 헬스장 대상 파일럿을 먼저 돌려 이탈률을 실측한 뒤 확장 \
                    여부를 결정하는 것입니다."""
    );

    public void run(Session session) {
        try {
            session.append(null, EventType.SESSION_STARTED, session.getIdea());

            runParallelNodes(session);
            runNode(session, "MODERATOR");

            if (session.getStatus() == Session.Status.RUNNING) {
                session.setStatus(Session.Status.COMPLETED);
                session.append(null, EventType.SESSION_COMPLETED, null);
            }
        } catch (InterruptedException e) {
            // DELETE로 취소된 경우. SESSION_FAILED는 SessionService가 이미 발행했다.
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (session.getStatus() == Session.Status.RUNNING) {
                session.setStatus(Session.Status.FAILED);
                session.append(null, EventType.SESSION_FAILED, "실행 중 오류: " + e.getMessage());
            }
        } finally {
            session.closeAllEmitters();
        }
    }

    /** 3개 노드를 가상 스레드로 동시에 돌리고 전부 끝날 때까지 기다린다. */
    private void runParallelNodes(Session session) throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String nodeId : PARALLEL_NODES) {
                futures.add(executor.submit(() -> {
                    runNode(session, nodeId);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    // Day 1 스텁은 실패하지 않는다. 실제 노드 실패 처리는 Day 2에서 다룬다.
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void runNode(Session session, String nodeId) throws InterruptedException {
        String text = STUB_TEXTS.get(nodeId);

        session.append(nodeId, EventType.NODE_STARTED, null);

        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            Thread.sleep(CHUNK_INTERVAL_MS);
            session.append(nodeId, EventType.NODE_CHUNK,
                    text.substring(i, Math.min(i + CHUNK_SIZE, text.length())));
        }

        // content는 그 노드의 전체 텍스트. 프론트는 버퍼를 이 값으로 통째로 교체한다.
        session.append(nodeId, EventType.NODE_COMPLETED, text);
    }
}
