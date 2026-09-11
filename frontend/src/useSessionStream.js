import { useEffect, useReducer } from "react";

const NODE_IDS = ["SECURITY", "CFO", "ARCHITECT", "MODERATOR"];

function emptyNodes() {
  return Object.fromEntries(NODE_IDS.map((id) => [id, { status: "IDLE", text: "" }]));
}

const initialState = {
  lastSeq: 0,
  nodes: emptyNodes(),
};

function reducer(state, event) {
  if (event.type === "RESET") {
    return { lastSeq: 0, nodes: emptyNodes() };
  }

  if (event.seq <= state.lastSeq) {
    return state;
  }

  const nodeId = event.nodeId;
  let nodes = state.nodes;

  switch (event.type) {
    case "SESSION_STARTED":
      nodes = emptyNodes();
      break;
    case "NODE_STARTED":
      nodes = { ...nodes, [nodeId]: { ...nodes[nodeId], status: "THINKING" } };
      break;
    case "NODE_CHUNK":
      nodes = {
        ...nodes,
        [nodeId]: { ...nodes[nodeId], text: nodes[nodeId].text + event.content },
      };
      break;
    case "NODE_COMPLETED":
      nodes = { ...nodes, [nodeId]: { status: "DONE", text: event.content } };
      break;
    case "NODE_FAILED":
      nodes = { ...nodes, [nodeId]: { status: "FAILED", text: event.content } };
      break;
    default:
      break;
  }

  return { lastSeq: event.seq, nodes };
}

export function useSessionStream(sessionId) {
  const [state, dispatch] = useReducer(reducer, initialState);

  useEffect(() => {
    if (!sessionId) return;
    dispatch({ type: "RESET" });

    const es = new EventSource(`/api/sessions/${sessionId}/stream`);
    es.onmessage = (e) => {
      const event = JSON.parse(e.data);
      dispatch(event);
      if (event.type === "SESSION_COMPLETED" || event.type === "SESSION_FAILED") {
        es.close();
      }
    };

    return () => es.close();
  }, [sessionId]);

  return state;
}
