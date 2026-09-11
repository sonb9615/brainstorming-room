import { useState } from "react";
import { useSessionStream } from "./useSessionStream.js";

export default function App() {
  const [idea, setIdea] = useState("");
  const [sessionId, setSessionId] = useState(null);
  const { lastSeq, nodes } = useSessionStream(sessionId);

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
      <pre>
        {Object.entries(nodes)
          .map(([id, n]) => `[${id}] ${n.status}\n${n.text}`)
          .join("\n\n")}
      </pre>
    </div>
  );
}
