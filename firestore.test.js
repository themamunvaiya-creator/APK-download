const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function ts() {
  return new Date(Date.now() - 1000);
}

function validSettings(uid) {
  return {
    userId: uid,
    assistantEnabled: true,
    autoAnswerMode: "all",
    assistantLanguage: "bilingual",
    assistantName: "Aria",
    greetingPrompt: "Hello! I am CallGuard AI assistant. How can I help?",
    importanceRules: "Medical, family emergencies, delivery at gate, banking alerts.",
    notifyUrgentOnly: true,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function validVip(uid) {
  return {
    userId: uid,
    name: "Dr. Rahman",
    phoneNumber: "+8801711000001",
    priorityLevel: "critical",
    note: "Always notify immediately",
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function validCallLog(uid) {
  return {
    userId: uid,
    callerName: "City Hospital",
    callerNumber: "+8801711000999",
    status: "important",
    importanceScore: 95,
    aiSummary: "Appointment confirmation needed immediately.",
    transcript: "AI: Hello\nCaller: Urgent lab report ready.",
    actionTaken: "Urgent Notification Sent",
    durationSeconds: 32,
    notifiedUser: true,
    acknowledged: false,
    isSimulated: true,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write settings, VIPs, or call logs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/call_logs`).get());
  await assertFails(
    unauthDb.doc(`users/${ALICE_UID}/settings/assistant`).set(validSettings(ALICE_UID))
  );
  await assertFails(
    unauthDb.doc(`users/${ALICE_UID}/vip_contacts/vip_1`).set(validVip(ALICE_UID))
  );
});

test("Authenticated owner can create and update assistant settings", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb.doc(`users/${ALICE_UID}/settings/assistant`);
  await assertSucceeds(ref.set(validSettings(ALICE_UID)));
  await assertSucceeds(
    ref.update({
      assistantEnabled: false,
      updatedAt: ts(),
    })
  );
});

test("Rejects arbitrary settingId or ghost fields on settings", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/settings/other`).set(validSettings(ALICE_UID))
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/settings/assistant`).set({
      ...validSettings(ALICE_UID),
      isAdmin: true,
    })
  );
});

test("Cross-user access to VIP contacts and call logs is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/vip_contacts/vip_1`).set(validVip(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/call_logs/call_1`).set(validCallLog(ALICE_UID))
  );

  await assertFails(bobDb.doc(`users/${ALICE_UID}/vip_contacts/vip_1`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/call_logs`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/call_logs/call_1`).update({
      acknowledged: true,
      updatedAt: ts(),
    })
  );
});

test("Rejects invalid importanceScore, invalid status, or tampering callLog fields on update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/call_logs/bad_score`).set({
      ...validCallLog(ALICE_UID),
      importanceScore: 150,
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/call_logs/bad_status`).set({
      ...validCallLog(ALICE_UID),
      status: "unknown_status",
    })
  );

  const logRef = aliceDb.doc(`users/${ALICE_UID}/call_logs/call_2`);
  await assertSucceeds(logRef.set(validCallLog(ALICE_UID)));
  await assertSucceeds(
    logRef.update({ acknowledged: true, updatedAt: ts() })
  );
  await assertFails(
    logRef.update({ aiSummary: "Tampered", updatedAt: ts() })
  );
});
