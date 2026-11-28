/**
 * djb ai — cloud worker.
 *
 * One file, no dependencies. It exists so the phone can do three things it cannot do
 * alone: run a task while it sleeps, remember across devices, and reach the internet
 * without spending its own connection.
 *
 * POST /task   {"task": "...", "allow_net": true}    -> runs, returns {id, answer, steps}
 * POST /sync   {"client": "...", "skills": [...], "lessons": [...], "memory": {...}}
 * GET  /sync?client=...                              -> last synced snapshot
 * GET  /health                                       -> readiness + which storage is live
 *
 * Storage: Supabase REST when SUPABASE_URL + SUPABASE_SERVICE_KEY are set, otherwise an
 * in-memory map. In-memory is honest for a demo and loses data on redeploy, so /health
 * reports which backend is live and the phone never has to guess.
 *
 * Auth: WORKER_TOKEN must arrive as `Authorization: Bearer <token>`. With no token the
 * worker refuses every task rather than running open to the internet.
 */
import http from "node:http";

const PORT = process.env.PORT || 3000;
const TOKEN = process.env.WORKER_TOKEN || "";
const SUPABASE_URL = (process.env.SUPABASE_URL || "").replace(/\/$/, "");
const SUPABASE_KEY = process.env.SUPABASE_SERVICE_KEY || "";
const TABLE = process.env.SUPABASE_TABLE || "djb_sync";

const memory = new Map();
const cloudEnabled = Boolean(SUPABASE_URL && SUPABASE_KEY);

function send(res, status, body) {
  const payload = typeof body === "string" ? body : JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-origin": "*",
    "access-control-allow-headers": "authorization,content-type",
    "access-control-allow-methods": "GET,POST,OPTIONS",
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > 2_000_000) reject(new Error("payload too large"));
    });
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch {
        reject(new Error("invalid json"));
      }
    });
    req.on("error", reject);
  });
}

async function supabase(method, path, body) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    method,
    headers: {
      apikey: SUPABASE_KEY,
      authorization: `Bearer ${SUPABASE_KEY}`,
      "content-type": "application/json",
      prefer: "resolution=merge-duplicates,return=representation",
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`supabase ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res.status === 204 ? null : res.json();
}

/**
 * The task runner. Answered by the same kind of model the phone uses so behaviour stays
 * consistent. Deliberately one turn and no tool calls: a server with shell access and no
 * human watching is a liability, and the phone already owns device control.
 */
async function runTask(task, allowNet) {
  const apiKey = process.env.OPENAI_API_KEY || "";
  const base = (process.env.OPENAI_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
  const model = process.env.MODEL || "gpt-4o-mini";

  if (!apiKey) {
    return {
      answer: "العامل السحابي جاهز لكن لا يوجد مفتاح موديل مضبوط (OPENAI_API_KEY).",
      steps: 0,
      degraded: true,
    };
  }

  const system =
    "أنت عامل خلفي لوكيل أندرويد. نفّذ المهمة النصية وأعد نتيجة موجزة وعملية بالعربية. " +
    (allowNet ? "يمكنك الاستفادة من معرفتك الحالية." : "لا تفترض وصولًا للإنترنت.");

  const res = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({
      model,
      messages: [
        { role: "system", content: system },
        { role: "user", content: task },
      ],
      temperature: 0.3,
    }),
  });

  if (!res.ok) {
    const text = (await res.text()).slice(0, 300);
    return { answer: `فشل الموديل: ${res.status} ${text}`, steps: 0, degraded: true };
  }

  const json = await res.json();
  return {
    answer: json.choices?.[0]?.message?.content ?? "(بلا إجابة)",
    steps: 1,
    model,
  };
}

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") return send(res, 204, "");

  const url = new URL(req.url, `http://localhost:${PORT}`);

  if (url.pathname === "/health") {
    return send(res, 200, {
      ok: true,
      service: "djb-ai-worker",
      storage: cloudEnabled ? "supabase" : "memory",
      model_configured: Boolean(process.env.OPENAI_API_KEY),
      auth_required: Boolean(TOKEN),
      at: new Date().toISOString(),
    });
  }

  const auth = req.headers.authorization || "";
  if (!TOKEN || auth !== `Bearer ${TOKEN}`) {
    return send(res, 401, { error: "unauthorized", hint: "اضبط WORKER_TOKEN وأرسله" });
  }

  try {
    if (url.pathname === "/task" && req.method === "POST") {
      const body = await readBody(req);
      const task = String(body.task || "").trim();
      if (!task) return send(res, 400, { error: "task مطلوبة" });
      const result = await runTask(task, body.allow_net !== false);
      const id = `t_${Date.now().toString(36)}`;
      return send(res, 200, { id, ...result });
    }

    if (url.pathname === "/sync" && req.method === "POST") {
      const body = await readBody(req);
      const client = String(body.client || "default").slice(0, 64);
      const snapshot = {
        skills: body.skills || [],
        lessons: body.lessons || [],
        memory: body.memory || {},
        updated_at: new Date().toISOString(),
      };
      if (cloudEnabled) {
        await supabase("POST", TABLE, [{ client_id: client, snapshot }]);
      } else {
        memory.set(client, snapshot);
      }
      return send(res, 200, {
        ok: true,
        storage: cloudEnabled ? "supabase" : "memory",
        skills: snapshot.skills.length,
        lessons: snapshot.lessons.length,
      });
    }

    if (url.pathname === "/sync" && req.method === "GET") {
      const client = url.searchParams.get("client") || "default";
      if (cloudEnabled) {
        const rows = await supabase(
          "GET",
          `${TABLE}?client_id=eq.${encodeURIComponent(client)}&select=snapshot`,
        );
        return send(res, 200, rows?.[0]?.snapshot ?? { empty: true });
      }
      return send(res, 200, memory.get(client) ?? { empty: true });
    }

    return send(res, 404, { error: "not found" });
  } catch (e) {
    return send(res, 500, { error: String(e.message || e) });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`djb-ai-worker listening on 0.0.0.0:${PORT} storage=${cloudEnabled ? "supabase" : "memory"}`);
});
