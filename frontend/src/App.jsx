import { useMemo, useState } from "react";
import { ReactFlow, Background } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import AgentNode from "./AgentNode.jsx";
import { useSessionStream } from "./useSessionStream.js";

const nodeTypes = { agent: AgentNode };

const NODE_META = {
  USER_INPUT: { label: "아이디어", position: { x: 0, y: 150 } },
  SECURITY: { label: "보안 담당자", position: { x: 280, y: 0 } },
  CFO: { label: "CFO", position: { x: 280, y: 150 } },
  ARCHITECT: { label: "아키텍트", position: { x: 280, y: 300 } },
  MODERATOR: { label: "종합", position: { x: 560, y: 150 } },
};

const EDGE_PAIRS = [
  ["USER_INPUT", "SECURITY"],
  ["USER_INPUT", "CFO"],
  ["USER_INPUT", "ARCHITECT"],
  ["SECURITY", "MODERATOR"],
  ["CFO", "MODERATOR"],
  ["ARCHITECT", "MODERATOR"],
];

export default function App() {
  const [idea, setIdea] = useState("");
  const [sessionId, setSessionId] = useState(null);
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const { lastSeq, nodes } = useSessionStream(sessionId);

  const statusOf = (id) =>
    id === "USER_INPUT" ? (sessionId ? "DONE" : "IDLE") : nodes[id].status;

  const rfNodes = useMemo(
    () =>
      Object.entries(NODE_META).map(([id, meta]) => ({
        id,
        type: "agent",
        position: meta.position,
        data: {
          label: meta.label,
          status: statusOf(id),
          text: id === "USER_INPUT" ? idea : nodes[id].text,
        },
      })),
    [nodes, sessionId, idea]
  );

  const rfEdges = useMemo(
    () =>
      EDGE_PAIRS.map(([source, target]) => ({
        id: `${source}-${target}`,
        source,
        target,
        animated: statusOf(source) === "DONE",
      })),
    [nodes, sessionId]
  );

  async function handleStart() {
    const res = await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idea }),
    });
    const data = await res.json();
    setSessionId(data.sessionId);
  }

  return (
    <div>
      <input value={idea} onChange={(e) => setIdea(e.target.value)} />
      <button onClick={handleStart}>시작</button>
      <div>lastSeq: {lastSeq}</div>
      <div style={{ height: "70vh" }}>
        <ReactFlow
          nodes={rfNodes}
          edges={rfEdges}
          nodeTypes={nodeTypes}
          fitView
          onNodeClick={(_, node) => setSelectedNodeId(node.id)}
        >
          <Background />
        </ReactFlow>
      </div>
      {selectedNodeId &&
        (() => {
          const node = rfNodes.find((n) => n.id === selectedNodeId);
          return (
            <div style={{ marginTop: 8, padding: 8, border: "1px solid #ccc" }}>
              <strong>{node.data.label}</strong>
              <button onClick={() => setSelectedNodeId(null)}>닫기</button>
              <pre style={{ whiteSpace: "pre-wrap" }}>{node.data.text}</pre>
            </div>
          );
        })()}
    </div>
  );
}
