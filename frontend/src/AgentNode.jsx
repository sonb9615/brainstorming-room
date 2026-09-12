import { Handle, Position } from "@xyflow/react";

const BORDER_COLOR = {
  IDLE: "#9ca3af",
  THINKING: "#3b82f6",
  DONE: "#22c55e",
  FAILED: "#ef4444",
};

export default function AgentNode({ data }) {
  return (
    <div
      style={{
        width: 220,
        border: `2px solid ${BORDER_COLOR[data.status]}`,
        borderRadius: 8,
        padding: 8,
        background: "white",
      }}
    >
      <Handle type="target" position={Position.Left} />
      <div style={{ fontWeight: "bold", marginBottom: 4 }}>{data.label}</div>
      <div
        style={{
          lineHeight: "1.4em",
          maxHeight: "8.4em",
          overflow: "hidden",
          fontSize: 13,
          whiteSpace: "pre-wrap",
        }}
      >
        {data.text}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
