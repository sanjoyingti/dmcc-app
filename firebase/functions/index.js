// Optional: sends push notifications when the app is closed (calls, notices, messages).
// Deploy with:  firebase deploy --only functions   (needs the Blaze plan)
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");
admin.initializeApp();
const db = admin.firestore();

async function tokensFor(uids, exceptUid) {
  const ids = uids.filter((u) => u && u !== exceptUid);
  const out = [];
  for (let i = 0; i < ids.length; i += 30) {
    const snap = await db.collection("users")
      .where(admin.firestore.FieldPath.documentId(), "in", ids.slice(i, i + 30)).get();
    snap.forEach((d) => { const t = d.get("fcmToken"); if (t) out.push(t); });
  }
  return out;
}

async function send(tokens, data) {
  if (!tokens.length) return;
  for (let i = 0; i < tokens.length; i += 500) {
    await admin.messaging().sendEachForMulticast({
      tokens: tokens.slice(i, i + 500),
      data,
      android: { priority: "high", ttl: data.type === "call" ? 45000 : 86400000 },
    });
  }
}

exports.onCall = onDocumentCreated("calls/{id}", async (e) => {
  const c = e.data.data();
  const t = await tokensFor([c.to], c.from);
  await send(t, { type: "call", callId: e.params.id, name: c.fromName || "Call", video: String(!!c.video) });
});

exports.onNotice = onDocumentCreated("groups/{gid}/notices/{nid}", async (e) => {
  const n = e.data.data();
  const g = (await db.collection("groups").doc(e.params.gid).get()).data() || {};
  const t = await tokensFor(g.memberIds || [], n.by);
  await send(t, { type: "notice", title: `📢 ${g.name}: ${n.title}`, body: n.body || "", route: "notices", tag: e.params.nid });
});

exports.onGroupMessage = onDocumentCreated("groups/{gid}/messages/{mid}", async (e) => {
  const m = e.data.data();
  const g = (await db.collection("groups").doc(e.params.gid).get()).data() || {};
  const t = await tokensFor(g.memberIds || [], m.from);
  const text = m.text || (String(m.mime || "").startsWith("image/") ? "📷 Photo" : `📎 ${m.fileName || ""}`);
  await send(t, { type: "message", title: g.name || "Group", body: `${m.fromName}: ${text}`, route: `chat/g/${e.params.gid}`, tag: e.params.gid });
});

exports.onDirectMessage = onDocumentCreated("dms/{dmId}/messages/{mid}", async (e) => {
  const m = e.data.data();
  const dm = (await db.collection("dms").doc(e.params.dmId).get()).data() || {};
  const t = await tokensFor(dm.members || [], m.from);
  const text = m.text || (String(m.mime || "").startsWith("image/") ? "📷 Photo" : `📎 ${m.fileName || ""}`);
  await send(t, { type: "message", title: m.fromName || "Message", body: text, route: `chat/d/${m.from}`, tag: e.params.dmId });
});
