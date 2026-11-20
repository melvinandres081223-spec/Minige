const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

test("Unauthenticated user cannot read or write any Minige collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection(`users/${ALICE_UID}/deliveries`).get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_1").set({
      userId: ALICE_UID,
      dateKey: "2026-11-20",
      customerName: "Nene",
      purifiedGallons: 10,
      alkalineGallons: 0,
      purifiedUnitPrice: 30,
      alkalineUnitPrice: 40,
      totalAmount: 300,
      amountPaid: 300,
      paymentType: "CASH",
      commissionRate: 8,
      commissionEarned: 80,
      settledDateKey: "2026-11-20",
      notes: "",
      createdAt: past,
      updatedAt: past,
    })
  );
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/expenses`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/settings`).get());
});

test("Owner can create, list, partially update, and delete deliveries matching Kotlin payload", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const past = new Date(Date.now() - 5000);

  // Create CASH delivery (no @DocumentId `id` field, optional calendarEventId omitted)
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_cash_1").set({
      userId: ALICE_UID,
      dateKey: "2026-11-20",
      customerName: "Nene",
      purifiedGallons: 45,
      alkalineGallons: 5,
      purifiedUnitPrice: 30,
      alkalineUnitPrice: 40,
      totalAmount: 1550,
      amountPaid: 1550,
      paymentType: "CASH",
      commissionRate: 8,
      commissionEarned: 400,
      settledDateKey: "2026-11-20",
      notes: "Morning batch",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Subcollection list query succeeds even on populated or empty collections
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/deliveries`).get());

  // Partial update: link Google Calendar event ID while preserving createdAt
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_cash_1").update({
      calendarEventId: "evt_abc_123",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / @DocumentId `id` key
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_ghost").set({
      id: "del_ghost",
      userId: ALICE_UID,
      dateKey: "2026-11-20",
      customerName: "Jhon",
      purifiedGallons: 1,
      alkalineGallons: 0,
      purifiedUnitPrice: 30,
      alkalineUnitPrice: 40,
      totalAmount: 30,
      amountPaid: 30,
      paymentType: "CASH",
      commissionRate: 8,
      commissionEarned: 8,
      notes: "",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Reject tampering createdAt on update
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_cash_1").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_future").set({
      userId: ALICE_UID,
      dateKey: "2026-11-20",
      customerName: "Melvin",
      purifiedGallons: 2,
      alkalineGallons: 0,
      purifiedUnitPrice: 30,
      alkalineUnitPrice: 40,
      totalAmount: 0,
      amountPaid: 0,
      paymentType: "PICK_UP",
      commissionRate: 8,
      commissionEarned: 0,
      notes: "",
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/deliveries`).doc("del_cash_1").delete()
  );
});

test("Owner can manage utangPayments, expenses, dailyLedgers, and settings", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const past = new Date(Date.now() - 3000);

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/utangPayments`).doc("pay_1").set({
      userId: ALICE_UID,
      deliveryId: "del_utang_1",
      dateKey: "2026-11-20",
      amount: 100,
      method: "CASH",
      notes: "Partial payment",
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/expenses`).doc("exp_1").set({
      userId: ALICE_UID,
      dateKey: "2026-11-20",
      category: "GAS",
      amount: 150,
      notes: "Full tank",
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/dailyLedgers`).doc("2026-11-20").set({
      userId: ALICE_UID,
      dateKey: "2026-11-20",
      startingSukli: 500,
      notes: "Morning change",
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/settings`).doc("config").set({
      userId: ALICE_UID,
      purifiedPrice: 30,
      alkalinePrice: 40,
      commissionRate: 8,
      defaultSukli: 500,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Cross-user access is strictly denied across all Minige subcollections", async () => {
  const past = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection(`users/${BOB_UID}/deliveries`).doc("bob_del").set({
      userId: BOB_UID,
      dateKey: "2026-11-20",
      customerName: "Bob Customer",
      purifiedGallons: 5,
      alkalineGallons: 0,
      purifiedUnitPrice: 30,
      alkalineUnitPrice: 40,
      totalAmount: 150,
      amountPaid: 150,
      paymentType: "CASH",
      commissionRate: 8,
      commissionEarned: 40,
      settledDateKey: "2026-11-20",
      notes: "",
      createdAt: past,
      updatedAt: past,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection(`users/${BOB_UID}/deliveries`).get());
  await assertFails(aliceDb.collection(`users/${BOB_UID}/deliveries`).doc("bob_del").get());
  await assertFails(
    aliceDb.collection(`users/${BOB_UID}/deliveries`).doc("bob_del").update({
      notes: "Hijack",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(aliceDb.collection(`users/${BOB_UID}/deliveries`).doc("bob_del").delete());
});
