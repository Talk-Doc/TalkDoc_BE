#!/usr/bin/env node
// TalkDoc 백엔드 WebSocket 이벤트 확인용 간단 클라이언트.
//
// 사용법:
//   node scripts/ws-client.mjs <sessionId> <token> [baseUrl]
//
// 예시:
//   node scripts/ws-client.mjs 3f2a... eyJhbGciOi... ws://localhost:8080
//
// ws://localhost:8080/ws/sessions/<sessionId>?token=<token> 에 연결하여
// 서버가 보내는 SessionEvent({"type":...,"session_id":...,"payload":...,"timestamp":...})를
// 예쁘게 출력한다. Node 22+에서 기본 제공되는 전역 WebSocket을 사용한다
// (Node 20/21은 --experimental-websocket 플래그가 필요할 수 있다).

const [, , sessionId, token, baseUrlArg] = process.argv;

if (!sessionId || !token) {
  console.error("사용법: node scripts/ws-client.mjs <sessionId> <token> [baseUrl]");
  console.error("예시:   node scripts/ws-client.mjs 3f2a... eyJhbGciOi... ws://localhost:8080");
  process.exit(1);
}

if (typeof WebSocket === "undefined") {
  console.error("이 Node 버전에는 전역 WebSocket이 없습니다.");
  console.error("Node 22 이상을 사용하거나, Node 20/21에서는 다음처럼 실행하세요:");
  console.error("  node --experimental-websocket scripts/ws-client.mjs <sessionId> <token>");
  console.error("또는 `npm install ws` 후 이 스크립트를 'ws' 패키지를 쓰도록 수정하세요.");
  process.exit(1);
}

const baseUrl = baseUrlArg || "ws://localhost:8080";
const url = `${baseUrl}/ws/sessions/${encodeURIComponent(sessionId)}?token=${encodeURIComponent(token)}`;

console.log(`[ws-client] 연결 시도: ${url}`);

const socket = new WebSocket(url);

socket.addEventListener("open", () => {
  console.log("[ws-client] 연결됨. 이벤트 수신 대기 중... (Ctrl+C로 종료)");
});

socket.addEventListener("message", (event) => {
  const raw = event.data;
  try {
    const parsed = JSON.parse(raw);
    console.log(`\n[event] ${new Date().toISOString()}`);
    console.log(JSON.stringify(parsed, null, 2));
  } catch {
    console.log(`\n[event:raw] ${raw}`);
  }
});

socket.addEventListener("close", (event) => {
  console.log(`[ws-client] 연결 종료 (code=${event.code}, reason=${event.reason || "-"})`);
  process.exit(0);
});

socket.addEventListener("error", (event) => {
  console.error("[ws-client] 에러:", event.message || event);
});

process.on("SIGINT", () => {
  console.log("\n[ws-client] 종료합니다.");
  try {
    socket.close();
  } finally {
    process.exit(0);
  }
});
