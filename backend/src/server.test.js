import assert from "node:assert/strict";
import test from "node:test";
import { addDays, dayDiff, detectCandidates, emptyStore, normalizeMerchant, seedMockData } from "./server.js";

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
