import { useEffect, useState } from "react";

export default function App() {
  const [idea, setIdea] = useState("");
  const [sessionId, setSessionId] = useState(null);
  const [lines, setLines] = useState([]);

  async function handleStart() {
    const res = await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idea }),
    });
    const data = await res.json();
    setLines([]);
    setSessionId(data.sessionId);
  }

  useEffect(() => {
    if (!sessionId) return;

    const es = new EventSource(`/api/sessions/${sessionId}/stream`);
    es.onmessage = (e) => {
      const ev = JSON.parse(e.data);
      setLines((prev) => [
        ...prev,
        `${ev.seq} ${ev.nodeId ?? "-"} ${ev.type} ${ev.content ?? ""}`,
      ]);
    };

    return () => es.close();
  }, [sessionId]);

  return (
    <div>
      <input value={idea} onChange={(e) => setIdea(e.target.value)} />
      <button onClick={handleStart}>시작</button>
      <pre>{lines.join("\n")}</pre>
    </div>
  );
}
