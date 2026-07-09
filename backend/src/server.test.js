import assert from "node:assert/strict";
import test from "node:test";
import {
  addDays,
  billsBeforePayday,
  buildSafeToSpendSnapshot,
  currentPayPeriod,
  dayDiff,
  detectCandidates,
  detectIncomeStreams,
  detectPaycheckPilotData,
  emptyStore,
  normalizeMerchant,
  seedMockData
} from "./server.js";

test("normalizes merchants for recurring detection keys", () => {
  assert.equal(normalizeMerchant("  Amazon   Prime "), "amazon prime");
});

test("date helpers calculate monthly gaps", () => {
  assert.equal(dayDiff("2026-01-01", "2026-01-31"), 30);
  assert.equal(addDays("2026-01-01", 30), "2026-01-31");
});

test("mock seed creates safe subscription candidates", () => {
  const store = emptyStore();
  seedMockData(store, "user-1");
  const candidates = Object.values(store.subscriptionCandidates);
  assert.ok(candidates.length >= 10);
  assert.ok(candidates.some((candidate) => candidate.merchantName === "Netflix"));
  assert.ok(candidates.some((candidate) => candidate.merchantName === "Electric Utility" && candidate.candidateType === "bill"));
  assert.ok(candidates.every((candidate) => candidate.status === "pending"));
  assert.equal(Object.values(store.connectedInstitutions)[0].encryptedAccessToken.includes("mock-plaid-token-placeholder"), false);
});

test("detects stable monthly subscription and excludes one-time retail", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Credit Card" };
  for (const [index, date] of ["2026-01-02", "2026-02-01", "2026-03-03"].entries()) {
    store.bankTransactions[`netflix-${index}`] = tx("netflix", "Netflix", 1599, date);
  }
  store.bankTransactions["amazon-1"] = tx("amazon-1", "Amazon Marketplace", 4287, "2026-01-09");
  store.bankTransactions["target-1"] = tx("target-1", "Target", 7212, "2026-01-12");

  detectCandidates("user-1", store);

  const candidates = Object.values(store.subscriptionCandidates);
  assert.equal(candidates.length, 1);
  assert.equal(candidates[0].merchantName, "Netflix");
  assert.equal(candidates[0].cadence, "Monthly");
  assert.ok(candidates[0].confidenceScore >= 0.9);
  assert.ok(candidates[0].nextChargeWindowStart);
  assert.ok(candidates[0].nextChargeWindowEnd);
  assert.equal(candidates[0].amountVarianceCents, 0);
});

test("marks variable utility as bill and excludes paycheck deposits", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Checking" };
  [
    ["2026-01-12", 14220],
    ["2026-02-11", 16840],
    ["2026-03-13", 15110]
  ].forEach(([date, amount], index) => {
    store.bankTransactions[`utility-${index}`] = tx(`utility-${index}`, "Electric Utility", amount, date, "UTILITIES");
  });
  store.bankTransactions["paycheck-1"] = tx("paycheck-1", "Payroll Deposit", -85000, "2026-02-14", "INCOME");
  store.bankTransactions["paycheck-2"] = tx("paycheck-2", "Payroll Deposit", -85000, "2026-02-28", "INCOME");

  detectCandidates("user-1", store);

  const candidates = Object.values(store.subscriptionCandidates);
  assert.equal(candidates.length, 1);
  assert.equal(candidates[0].merchantName, "Electric Utility");
  assert.equal(candidates[0].candidateType, "bill");
  assert.ok(candidates[0].confidenceScore >= 0.7);
  assert.ok(dayDiff(candidates[0].nextChargeWindowStart, candidates[0].nextChargeWindowEnd) >= 10);
});

test("detects paycheck income streams from shared transactions", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Checking" };
  [
    ["2026-01-03", -85000],
    ["2026-01-17", -85000],
    ["2026-01-31", -85000]
  ].forEach(([date, amount], index) => {
    store.bankTransactions[`paycheck-${index}`] = tx(`paycheck-${index}`, "PAYROLL ACH", amount, date, "INCOME");
  });

  detectIncomeStreams("user-1", Object.values(store.bankTransactions), store);

  const streams = Object.values(store.incomeStreams);
  assert.equal(streams.length, 1);
  assert.equal(streams[0].payerName, "PAYROLL ACH");
  assert.equal(streams[0].cadence, "biweekly");
  assert.equal(streams[0].averageAmountCents, 85000);
  assert.equal(streams[0].status, "pending");
  assert.ok(streams[0].reasonDetected.includes("3 similar deposits"));
  assert.ok(streams[0].confidenceScore >= 0.9);
});

test("creates review-first paycheck candidates with source details", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Checking" };
  ["2026-01-01", "2026-01-08", "2026-01-15", "2026-01-22"].forEach((date, index) => {
    store.bankTransactions[`publix-${index}`] = tx(`publix-${index}`, "PUBLIX PAYROLL", -72000, date, "INCOME");
  });

  detectPaycheckPilotData("user-1", store);

  const candidate = Object.values(store.paycheckCandidates)[0];
  assert.equal(candidate.sourceName, "PUBLIX PAYROLL");
  assert.equal(candidate.normalizedSourceName, "publix");
  assert.equal(candidate.cadence, "weekly");
  assert.equal(candidate.averageAmountCents, 72000);
  assert.equal(candidate.averageAmount, 72000);
  assert.equal(candidate.lastDepositDate, "2026-01-22");
  assert.equal(candidate.predictedNextPayday, "2026-01-29");
  assert.equal(candidate.status, "pending");
  assert.equal(candidate.transactionsUsed.length, 4);
  assert.ok(candidate.confidenceScore >= 0.9);
  assert.ok(candidate.reasonDetected.includes("weekly"));
});

test("detects recurring gig income but ignores one-time transfer app deposits", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Checking" };
  [
    ["2026-01-03", -4200],
    ["2026-01-06", -8700],
    ["2026-01-09", -3900],
    ["2026-01-13", -7600]
  ].forEach(([date, amount], index) => {
    store.bankTransactions[`doordash-${index}`] = tx(`doordash-${index}`, "DOORDASH", amount, date, "INCOME");
  });
  store.bankTransactions["venmo-1"] = tx("venmo-1", "VENMO FROM JOHN", -12500, "2026-01-14", "TRANSFER");

  detectPaycheckPilotData("user-1", store);

  const streams = Object.values(store.incomeStreams);
  assert.equal(streams.length, 1);
  assert.equal(streams[0].sourceName, "DOORDASH");
  assert.equal(streams[0].cadence, "irregular/gig");
  assert.equal(streams[0].incomeType, "gig");
  assert.ok(streams[0].confidenceScore >= 0.6);
  assert.equal(Object.values(store.paycheckCandidates).length, 1);
});

test("builds Paycheck Pilot bills before payday and safe-to-spend snapshot", () => {
  const store = emptyStore();
  seedMockData(store, "user-1");
  detectPaycheckPilotData("user-1", store);
  const period = currentPayPeriod("user-1", undefined, store);
  const bills = billsBeforePayday("user-1", period, store);
  const snapshot = buildSafeToSpendSnapshot("user-1", period, store);

  assert.ok(Object.values(store.incomeStreams).length >= 1);
  assert.ok(Object.values(store.paycheckCandidates).length >= 1);
  assert.ok(bills.some((bill) => bill.merchantName === "Phone Bill" || bill.merchantName === "Electric Utility"));
  assert.equal(snapshot.userId, "user-1");
  assert.ok(Number.isFinite(snapshot.safeToSpendCents));
});

test("safe-to-spend engine subtracts committed money and explains the estimate", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Checking" };
  store.confirmedSubscriptions["rent"] = {
    id: "rent",
    userId: "user-1",
    merchantName: "Rent",
    amountCents: 85000,
    cadence: "Monthly",
    nextChargeDate: "2026-01-10",
    sourceAccountId: "acct-1"
  };
  store.confirmedSubscriptions["netflix"] = {
    id: "netflix",
    userId: "user-1",
    merchantName: "Netflix",
    amountCents: 1599,
    cadence: "Monthly",
    nextChargeDate: "2026-01-09",
    sourceAccountId: "acct-1"
  };
  const period = {
    id: "period-1",
    userId: "user-1",
    startDate: "2026-01-01",
    nextPayday: "2026-01-15",
    expectedPaycheckCents: 90000,
    currentBalanceCents: 150000,
    safetyBufferCents: 20000,
    savingsGoalCents: 10000,
    essentialsAllowanceCents: 12000,
    gasAllowanceCents: 8000,
    groceryAllowanceCents: 15000,
    alreadySpentCents: 5000,
    manualBills: [{ name: "Phone bill", amountCents: 6000, dueDate: "2026-01-12" }],
    excludedAccountIds: [],
    excludedTransactionIds: [],
    excludedCandidateIds: [],
    excludedSubscriptionIds: []
  };

  const snapshot = buildSafeToSpendSnapshot("user-1", period, store);

  assert.equal(snapshot.currentBalance, 150000);
  assert.equal(snapshot.nextPayday, "2026-01-15");
  assert.equal(snapshot.expectedIncome, 90000);
  assert.equal(snapshot.committedBills, 91000);
  assert.equal(snapshot.recurringCharges, 1599);
  assert.equal(snapshot.bufferAmount, 20000);
  assert.equal(snapshot.savingsGoal, 10000);
  assert.equal(snapshot.essentialsAllowance, 35000);
  assert.equal(snapshot.safeToSpendTotal, 0);
  assert.equal(snapshot.safeToSpendDaily, 0);
  assert.ok(snapshot.explanation.includes("This is an estimate, not financial advice."));
  assert.ok(snapshot.watchOuts.some((item) => item.title.includes("plan is tight")));
});

test("safe-to-spend engine supports manual overrides and exclusions", () => {
  const store = emptyStore();
  store.users["user-1"] = { id: "user-1" };
  store.connectedAccounts["acct-1"] = { id: "acct-1", userId: "user-1", plaidAccountId: "acct-1", name: "Checking" };
  store.confirmedSubscriptions["rent"] = {
    id: "rent",
    userId: "user-1",
    merchantName: "Rent",
    amountCents: 85000,
    cadence: "Monthly",
    nextChargeDate: "2026-01-10",
    sourceAccountId: "acct-1"
  };
  const period = {
    id: "period-2",
    userId: "user-1",
    startDate: "2026-01-01",
    nextPayday: "2026-01-15",
    expectedPaycheckCents: 90000,
    currentBalanceCents: 150000,
    safetyBufferCents: 20000,
    savingsGoalCents: 0,
    essentialsAllowanceCents: 0,
    gasAllowanceCents: 0,
    groceryAllowanceCents: 0,
    alreadySpentCents: 0,
    manualBills: [],
    excludedAccountIds: [],
    excludedTransactionIds: [],
    excludedCandidateIds: [],
    excludedSubscriptionIds: ["rent"]
  };

  const snapshot = buildSafeToSpendSnapshot("user-1", period, store);

  assert.equal(snapshot.committedBills, 0);
  assert.equal(snapshot.safeToSpendTotal, 130000);
  assert.ok(snapshot.confidenceScore < 0.95);
});

function tx(id, merchantName, amountCents, date, category = "GENERAL_SERVICES") {
  return {
    id,
    transactionId: id,
    userId: "user-1",
    institutionId: "inst-1",
    plaidAccountId: "acct-1",
    merchantName,
    originalDescription: merchantName,
    amountCents,
    currency: "USD",
    date,
    authorizedDate: date,
    pending: false,
    category,
    paymentChannel: "online"
  };
}
