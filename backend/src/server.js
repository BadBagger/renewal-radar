import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const __dirname = path.dirname(currentFile);
const dataDir = path.resolve(__dirname, "../data");
const storePath = path.join(dataDir, "dev-store.json");

const config = {
  port: Number(process.env.PORT || 8788),
  publicBaseUrl: process.env.BACKEND_PUBLIC_BASE_URL || "http://localhost:8788",
  plaidClientId: process.env.PLAID_CLIENT_ID || "",
  plaidSecret: process.env.PLAID_SECRET || "",
  plaidEnv: process.env.PLAID_ENV || "sandbox",
  plaidProducts: splitEnv(process.env.PLAID_PRODUCTS || "transactions"),
  plaidCountryCodes: splitEnv(process.env.PLAID_COUNTRY_CODES || "US"),
  plaidRedirectUri: process.env.PLAID_REDIRECT_URI || "",
  plaidAndroidPackageName: process.env.PLAID_ANDROID_PACKAGE_NAME || "com.renewalradar.app",
  paycheckPilotAndroidPackageName: process.env.PAYCHECK_PILOT_ANDROID_PACKAGE_NAME || "com.paycheckpilot",
  mockMode: (process.env.PLAID_MOCK_MODE || "true").toLowerCase() === "true",
  seedMockData: (process.env.SEED_MOCK_DATA || "true").toLowerCase() === "true",
  betaMode: (process.env.BETA_MODE || "false").toLowerCase() === "true",
  betaMaxUsers: Number(process.env.BETA_MAX_USERS || 10),
  betaAllowedUserIds: splitEnv(process.env.BETA_ALLOWED_USER_IDS || ""),
  rateLimitWindowMs: Number(process.env.RATE_LIMIT_WINDOW_MS || 60_000),
  rateLimitMaxRequests: Number(process.env.RATE_LIMIT_MAX_REQUESTS || 120)
};

const plaidHosts = {
  sandbox: "https://sandbox.plaid.com",
  development: "https://development.plaid.com",
  production: "https://production.plaid.com"
};

const tokenKey = loadEncryptionKey();
const rateBuckets = new Map();

ensureStorage();
const store = loadStore();
ensureStoreShape(store);
if (config.mockMode && config.seedMockData) {
  seedMockData(store, "local-user");
  saveStore(store);
}

const server = http.createServer(async (req, res) => {
  try {
    if (!rateLimit(req, res)) return;
    if (!config.mockMode && !isHttpsRequest(req)) {
      return sendJson(res, 403, { error: "https_required" });
    }

    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    const userId = getUserId(req);

    if (req.method === "GET" && url.pathname === "/health") {
      return sendJson(res, 200, {
        status: "ok",
        environment: config.plaidEnv,
        plaidConfigured: Boolean(config.plaidClientId && config.plaidSecret),
        mockMode: config.mockMode,
        betaTrial: betaStatus()
      });
    }

    if (req.method === "POST" && url.pathname === "/api/plaid/create-link-token") {
      return handleCreateLinkToken(req, res, userId);
    }

    if (req.method === "POST" && url.pathname === "/api/plaid/exchange-public-token") {
      return handleExchangePublicToken(req, res, userId);
    }

    if (req.method === "POST" && url.pathname === "/api/plaid/sync-transactions") {
      return handleSyncTransactions(req, res, userId);
    }

    if (req.method === "GET" && url.pathname === "/api/accounts") {
      return handleGetAccounts(res, userId);
    }

    if (req.method === "GET" && url.pathname === "/api/transactions") {
      return handleGetTransactions(res, userId, url);
    }

    if (req.method === "GET" && url.pathname === "/api/renewals/candidates") {
      return handleGetCandidates(res, userId);
    }

    const confirmMatch = url.pathname.match(/^\/api\/renewals\/candidates\/([^/]+)\/confirm$/);
    if (req.method === "POST" && confirmMatch) {
      return handleCandidateDecision(req, res, userId, confirmMatch[1], "confirmed");
    }

    const ignoreMatch = url.pathname.match(/^\/api\/renewals\/candidates\/([^/]+)\/ignore$/);
    if (req.method === "POST" && ignoreMatch) {
      return handleCandidateDecision(req, res, userId, ignoreMatch[1], "ignored");
    }

    if (req.method === "POST" && url.pathname === "/api/plaid/disconnect") {
      return handleDisconnect(req, res, userId);
    }

    if (req.method === "POST" && url.pathname === "/api/plaid/webhook") {
      return handlePlaidWebhook(req, res);
    }

    if (req.method === "GET" && url.pathname === "/api/paycheck/income-streams") {
      return handleGetIncomeStreams(res, userId);
    }

    if (req.method === "GET" && url.pathname === "/api/paycheck/paychecks") {
      return handleGetPaycheckCandidates(res, userId);
    }

    if (req.method === "POST" && url.pathname === "/api/paycheck/paychecks/confirm") {
      return handlePaycheckDecision(req, res, userId, "confirmed");
    }

    if (req.method === "POST" && url.pathname === "/api/paycheck/paychecks/ignore") {
      return handlePaycheckDecision(req, res, userId, "ignored");
    }

    if (req.method === "GET" && url.pathname === "/api/paycheck/bills-before-payday") {
      return handleBillsBeforePayday(res, userId, url);
    }

    if (req.method === "GET" && url.pathname === "/api/paycheck/safe-to-spend") {
      return handleSafeToSpend(res, userId, url);
    }

    if (req.method === "POST" && url.pathname === "/api/paycheck/pay-periods") {
      return handleUpsertPayPeriod(req, res, userId);
    }

    if (req.method === "GET" && url.pathname === "/api/paycheck/pay-periods/current") {
      return handleCurrentPayPeriod(res, userId, url);
    }

    if (req.method === "GET" && url.pathname === "/api/paycheck/watch-outs") {
      return handlePaycheckWatchOuts(res, userId, url);
    }

    if (req.method === "GET" && url.pathname === "/api/account/export") {
      return handleExport(res, userId);
    }

    if (req.method === "POST" && url.pathname === "/api/account/delete") {
      return handleDeleteAccount(res, userId);
    }

    return sendJson(res, 404, { error: "not_found" });
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown_error";
    return sendJson(res, 500, { error: "server_error", message: redact(message) });
  }
});

if (process.argv[1] === currentFile) {
  server.listen(config.port, () => {
    console.log(`Renewal Radar bank backend listening on ${config.port}`);
  });
}

async function handleCreateLinkToken(req, res, userId) {
  const betaProblem = betaAccessProblem(userId);
  if (betaProblem) return sendJson(res, 403, betaProblem);
  requireUser(store, userId);

  if (config.mockMode) {
    audit(userId, "plaid.create_link_token.mock", {});
    return sendJson(res, 200, {
      link_token: `link-sandbox-${crypto.randomUUID()}`,
      mock_mode: true,
      expiration: new Date(Date.now() + 30 * 60 * 1000).toISOString()
    });
  }

  const response = await plaidPost("/link/token/create", {
    client_name: getClientApp(req) === "paycheck-pilot" ? "Paycheck Pilot" : "Renewal Radar",
    language: "en",
    country_codes: config.plaidCountryCodes,
    user: { client_user_id: userId },
    products: config.plaidProducts,
    webhook: `${config.publicBaseUrl.replace(/\/$/, "")}/api/plaid/webhook`,
    android_package_name: getAndroidPackageName(req),
    ...(config.plaidRedirectUri ? { redirect_uri: config.plaidRedirectUri } : {})
  });

  audit(userId, "plaid.create_link_token", {});
  return sendJson(res, 200, {
    link_token: response.link_token,
    mock_mode: false,
    expiration: response.expiration
  });
}

async function handleExchangePublicToken(req, res, userId) {
  const body = await readJson(req);
  if (!body.public_token || typeof body.public_token !== "string") {
    return sendJson(res, 400, { error: "public_token_required" });
  }

  const betaProblem = betaAccessProblem(userId);
  if (betaProblem) return sendJson(res, 403, betaProblem);
  requireUser(store, userId);

  if (config.mockMode) {
    const institutionId = `inst-${crypto.randomUUID()}`;
    const accountId = `acct-${crypto.randomUUID()}`;
    store.connectedInstitutions[institutionId] = {
      id: institutionId,
      userId,
      plaidItemId: "mock-item",
      institutionName: body.institution?.name || "Plaid Sandbox Bank",
      encryptedAccessToken: encryptToken("mock-plaid-token-placeholder"),
      transactionCursor: null,
      status: "connected",
      createdAt: nowIso(),
      disconnectedAt: null
    };
    store.connectedAccounts[accountId] = {
      id: accountId,
      userId,
      institutionId,
      plaidAccountId: "mock-account",
      name: "Everyday Checking",
      mask: "0000",
      type: "depository",
      subtype: "checking",
      status: "connected",
      lastSyncedAt: null
    };
    seedMockData(store, userId, institutionId, accountId);
    audit(userId, "plaid.exchange_public_token.mock", { institutionId });
    saveStore(store);
    return sendJson(res, 200, { institutionId, accounts: safeAccounts(userId) });
  }

  const exchange = await plaidPost("/item/public_token/exchange", {
    public_token: body.public_token
  });
  const accessToken = exchange.access_token;
  const itemId = exchange.item_id;
  const institutionId = `inst-${crypto.randomUUID()}`;
  const accounts = normalizeLinkAccounts(body.accounts || [], institutionId, userId);

  store.connectedInstitutions[institutionId] = {
    id: institutionId,
    userId,
    plaidItemId: itemId,
    institutionName: body.institution?.name || "Connected institution",
    encryptedAccessToken: encryptToken(accessToken),
    transactionCursor: null,
    status: "connected",
    createdAt: nowIso(),
    disconnectedAt: null
  };
  for (const account of accounts) {
    store.connectedAccounts[account.id] = account;
  }

  audit(userId, "plaid.exchange_public_token", { institutionId, accountCount: accounts.length });
  saveStore(store);
  return sendJson(res, 200, { institutionId, accounts: safeAccounts(userId) });
}

async function handleSyncTransactions(req, res, userId) {
  const betaProblem = betaAccessProblem(userId);
  if (betaProblem) return sendJson(res, 403, betaProblem);
  const body = await readJson(req).catch(() => ({}));
  const institutionId = body.institutionId || null;
  const result = await syncUserTransactions(userId, institutionId);
  return sendJson(res, 200, result);
}

async function syncUserTransactions(userId, institutionId = null) {
  const institutions = Object.values(store.connectedInstitutions).filter((institution) =>
    institution.userId === userId &&
    institution.status === "connected" &&
    (!institutionId || institution.id === institutionId)
  );

  let added = 0;
  let modified = 0;
  let removed = 0;

  for (const institution of institutions) {
    const syncStarted = nowIso();
    if (config.mockMode) {
      const account = Object.values(store.connectedAccounts).find((candidate) => candidate.institutionId === institution.id);
      seedMockData(store, userId, institution.id, account?.id);
      markAccountsSynced(userId, institution.id);
      added += 10;
      audit(userId, "plaid.sync_transactions.mock", { institutionId: institution.id });
      addSyncLog(userId, institution.id, "success", syncStarted, nowIso(), "mock sync complete");
      continue;
    }

    const accessToken = decryptToken(institution.encryptedAccessToken);
    let cursor = institution.transactionCursor;
    let hasMore = true;

    while (hasMore) {
      const response = await plaidPost("/transactions/sync", {
        access_token: accessToken,
        cursor,
        count: 250
      });
      for (const transaction of response.added || []) {
        store.bankTransactions[transaction.transaction_id] = normalizeTransaction(transaction, userId, institution.id);
      }
      for (const transaction of response.modified || []) {
        store.bankTransactions[transaction.transaction_id] = normalizeTransaction(transaction, userId, institution.id);
      }
      for (const transaction of response.removed || []) {
        delete store.bankTransactions[transaction.transaction_id];
      }
      added += (response.added || []).length;
      modified += (response.modified || []).length;
      removed += (response.removed || []).length;
      cursor = response.next_cursor;
      hasMore = Boolean(response.has_more);
    }

    institution.transactionCursor = cursor;
    markAccountsSynced(userId, institution.id);
    detectCandidates(userId);
    detectPaycheckPilotData(userId);
    audit(userId, "plaid.sync_transactions", { institutionId: institution.id, added, modified, removed });
    addSyncLog(userId, institution.id, "success", syncStarted, nowIso(), "transactions synced");
  }

  detectCandidates(userId);
  detectPaycheckPilotData(userId);
  saveStore(store);
  return {
    status: "ok",
    syncedAt: nowIso(),
    institutions: institutions.length,
    added,
    modified,
    removed,
    candidateCount: candidatesForUser(userId).length
  };
}

function handleGetCandidates(res, userId) {
  return sendJson(res, 200, { candidates: candidatesForUser(userId) });
}

function handleGetAccounts(res, userId) {
  return sendJson(res, 200, { accounts: safeAccounts(userId) });
}

function handleGetTransactions(res, userId, url) {
  const limit = Number(url.searchParams.get("limit") || 250);
  return sendJson(res, 200, {
    transactions: transactionsForUser(userId)
      .slice(-Math.max(1, Math.min(limit, 500)))
      .reverse()
  });
}

async function handleCandidateDecision(req, res, userId, candidateId, decision) {
  const candidate = store.subscriptionCandidates[candidateId];
  if (!candidate || candidate.userId !== userId) {
    return sendJson(res, 404, { error: "candidate_not_found" });
  }

  candidate.status = decision;
  candidate.updatedAt = nowIso();

  if (decision === "confirmed") {
    const body = await readJson(req).catch(() => ({}));
    const subscriptionId = `sub-${crypto.randomUUID()}`;
    store.confirmedSubscriptions[subscriptionId] = {
      id: subscriptionId,
      userId,
      candidateId,
      merchantName: body.merchantName || candidate.merchantName,
      amountCents: Number(body.amountCents || candidate.amountCents),
      cadence: body.cadence || candidate.cadence,
      nextChargeDate: body.nextChargeDate || candidate.nextChargeDate,
      sourceAccountId: candidate.accountId,
      createdAt: nowIso()
    };
  }

  audit(userId, `candidate.${decision}`, { candidateId });
  saveStore(store);
  return sendJson(res, 200, { status: decision, candidate });
}

function handleGetIncomeStreams(res, userId) {
  detectPaycheckPilotData(userId);
  return sendJson(res, 200, {
    incomeStreams: Object.values(store.incomeStreams)
      .filter((stream) => stream.userId === userId)
      .sort((a, b) => a.predictedNextPayDate.localeCompare(b.predictedNextPayDate))
  });
}

function handleGetPaycheckCandidates(res, userId) {
  detectPaycheckPilotData(userId);
  return sendJson(res, 200, {
    paychecks: Object.values(store.paycheckCandidates)
      .filter((candidate) => candidate.userId === userId && candidate.status === "pending")
      .sort((a, b) => a.predictedPayDate.localeCompare(b.predictedPayDate))
  });
}

async function handlePaycheckDecision(req, res, userId, decision) {
  const body = await readJson(req).catch(() => ({}));
  const paycheckId = body.candidateId || body.paycheckId || body.id;
  const candidate = store.paycheckCandidates[paycheckId];
  if (!candidate || candidate.userId !== userId) {
    return sendJson(res, 404, { error: "paycheck_candidate_not_found" });
  }
  candidate.status = decision;
  candidate.updatedAt = nowIso();
  if (decision === "confirmed") {
    const confirmedId = `confirmed-pay-${crypto.randomUUID()}`;
    store.confirmedPaychecks[confirmedId] = {
      id: confirmedId,
      userId,
      paycheckCandidateId: candidate.id,
      incomeStreamId: candidate.incomeStreamId,
      payerName: body.payerName || candidate.payerName,
      amountCents: Number(body.actualAmountCents || body.amountCents || candidate.expectedAmountCents),
      payDate: body.payDate || candidate.predictedPayDate,
      actualTransactionId: body.actualTransactionId || null,
      notes: body.notes || "",
      confirmedAt: nowIso()
    };
  }
  audit(userId, `paycheck.${decision}`, { paycheckId });
  saveStore(store);
  return sendJson(res, 200, { status: decision, paycheck: candidate });
}

function handleBillsBeforePayday(res, userId, url) {
  detectPaycheckPilotData(userId);
  const current = currentPayPeriod(userId, url.searchParams.get("asOf") || todayIso());
  const bills = billsBeforePayday(userId, current);
  const snapshotId = `bbp-${userId}-${current.startDate}-${current.nextPayday}`;
  store.billBeforePaydaySnapshots[snapshotId] = {
    id: snapshotId,
    userId,
    payPeriodId: current.id,
    nextPayday: current.nextPayday,
    billIds: bills.map((bill) => bill.id),
    totalCents: bills.reduce((sum, bill) => sum + bill.amountCents, 0),
    createdAt: nowIso()
  };
  saveStore(store);
  return sendJson(res, 200, { payPeriod: current, bills, totalCents: store.billBeforePaydaySnapshots[snapshotId].totalCents });
}

function handleSafeToSpend(res, userId, url) {
  detectPaycheckPilotData(userId);
  const current = currentPayPeriod(userId, url.searchParams.get("asOf") || todayIso());
  const snapshot = buildSafeToSpendSnapshot(userId, current);
  store.safeToSpendSnapshots[snapshot.id] = snapshot;
  saveStore(store);
  return sendJson(res, 200, { snapshot, watchOuts: snapshot.watchOuts, missingData: snapshot.missingData });
}

async function handleUpsertPayPeriod(req, res, userId) {
  const body = await readJson(req);
  const id = body.id || `period-${crypto.randomUUID()}`;
  const payPeriod = {
    id,
    userId,
    startDate: body.startDate || todayIso(),
    nextPayday: body.nextPayday,
    expectedPaycheckCents: Number(body.expectedPaycheckCents || body.expectedAmountCents || 0),
    safetyBufferCents: centsFromBody(body, ["safetyBufferCents", "bufferAmountCents"], 20000),
    currentBalanceCents: centsFromBody(body, ["currentBalanceCents", "currentBalance"], null),
    savingsGoalCents: centsFromBody(body, ["savingsGoalCents", "plannedSavingsCents", "savingsGoal"], 0),
    essentialsAllowanceCents: centsFromBody(body, ["essentialsAllowanceCents", "essentialsAllowance"], 0),
    gasAllowanceCents: centsFromBody(body, ["gasAllowanceCents", "gasAllowance"], 0),
    groceryAllowanceCents: centsFromBody(body, ["groceryAllowanceCents", "groceryAllowance"], 0),
    alreadySpentCents: centsFromBody(body, ["alreadySpentCents", "alreadySpent"], 0),
    manualBills: normalizeManualBills(body.manualBills || body.bills || []),
    excludedAccountIds: normalizeStringList(body.excludedAccountIds),
    excludedTransactionIds: normalizeStringList(body.excludedTransactionIds),
    excludedCandidateIds: normalizeStringList(body.excludedCandidateIds),
    excludedSubscriptionIds: normalizeStringList(body.excludedSubscriptionIds),
    createdAt: store.payPeriods[id]?.createdAt || nowIso(),
    updatedAt: nowIso()
  };
  if (!payPeriod.nextPayday) return sendJson(res, 400, { error: "nextPayday_required" });
  store.payPeriods[id] = payPeriod;
  saveStore(store);
  return sendJson(res, 200, { payPeriod });
}

function handleCurrentPayPeriod(res, userId, url) {
  detectPaycheckPilotData(userId);
  return sendJson(res, 200, { payPeriod: currentPayPeriod(userId, url.searchParams.get("asOf") || todayIso()) });
}

function handlePaycheckWatchOuts(res, userId, url) {
  detectPaycheckPilotData(userId);
  const current = currentPayPeriod(userId, url.searchParams.get("asOf") || todayIso());
  const watchOuts = buildPaycheckWatchOuts(userId, current);
  for (const item of watchOuts) store.paycheckWatchOuts[item.id] = item;
  saveStore(store);
  return sendJson(res, 200, { watchOuts });
}

async function handleDisconnect(req, res, userId) {
  const body = await readJson(req);
  const institutionId = body.institutionId || null;
  const accountId = body.accountId || null;

  const institutions = Object.values(store.connectedInstitutions).filter((institution) =>
    institution.userId === userId &&
    institution.status === "connected" &&
    (!institutionId || institution.id === institutionId)
  );

  for (const institution of institutions) {
    if (!config.mockMode) {
      await plaidPost("/item/remove", {
        access_token: decryptToken(institution.encryptedAccessToken)
      }).catch(() => null);
    }
    institution.encryptedAccessToken = null;
    institution.status = "disconnected";
    institution.disconnectedAt = nowIso();
  }

  for (const account of Object.values(store.connectedAccounts)) {
    if (account.userId !== userId) continue;
    if (accountId && account.id !== accountId) continue;
    if (institutionId && account.institutionId !== institutionId) continue;
    account.status = "disconnected";
  }

  audit(userId, "plaid.disconnect", { institutionId, accountId });
  saveStore(store);
  return sendJson(res, 200, { status: "disconnected" });
}

async function handlePlaidWebhook(req, res) {
  const body = await readJson(req);
  const itemId = body.item_id;
  const institution = Object.values(store.connectedInstitutions).find((candidate) => candidate.plaidItemId === itemId);
  if (!institution) {
    return sendJson(res, 202, { status: "ignored" });
  }

  audit(institution.userId, "plaid.webhook", {
    webhookType: body.webhook_type,
    webhookCode: body.webhook_code,
    institutionId: institution.id
  });

  if (body.webhook_type === "TRANSACTIONS" || body.webhook_type === "RECURRING_TRANSACTIONS") {
    await syncUserTransactions(institution.userId, institution.id);
  }

  return sendJson(res, 200, { status: "received" });
}

function handleExport(res, userId) {
  audit(userId, "account.export", {});
  return sendJson(res, 200, {
    user: store.users[userId] || null,
    connectedInstitutions: Object.values(store.connectedInstitutions)
      .filter((item) => item.userId === userId)
      .map(({ encryptedAccessToken, ...safe }) => safe),
    connectedAccounts: safeAccounts(userId),
    bankTransactions: Object.values(store.bankTransactions).filter((item) => item.userId === userId),
    recurringStreams: Object.values(store.recurringStreams).filter((item) => item.userId === userId),
    subscriptionCandidates: Object.values(store.subscriptionCandidates).filter((item) => item.userId === userId),
    confirmedSubscriptions: Object.values(store.confirmedSubscriptions).filter((item) => item.userId === userId),
    renewalWatchOuts: Object.values(store.renewalWatchOuts).filter((item) => item.userId === userId),
    incomeStreams: Object.values(store.incomeStreams).filter((item) => item.userId === userId),
    paycheckCandidates: Object.values(store.paycheckCandidates).filter((item) => item.userId === userId),
    confirmedPaychecks: Object.values(store.confirmedPaychecks).filter((item) => item.userId === userId),
    payPeriods: Object.values(store.payPeriods).filter((item) => item.userId === userId),
    billBeforePaydaySnapshots: Object.values(store.billBeforePaydaySnapshots).filter((item) => item.userId === userId),
    safeToSpendSnapshots: Object.values(store.safeToSpendSnapshots).filter((item) => item.userId === userId),
    paycheckWatchOuts: Object.values(store.paycheckWatchOuts).filter((item) => item.userId === userId),
    syncLogs: Object.values(store.syncLogs).filter((item) => item.userId === userId)
  });
}

function handleDeleteAccount(res, userId) {
  delete store.users[userId];
  for (const table of [
    "connectedInstitutions",
    "connectedAccounts",
    "bankTransactions",
    "recurringStreams",
    "subscriptionCandidates",
    "confirmedSubscriptions",
    "renewalWatchOuts",
    "incomeStreams",
    "paycheckCandidates",
    "confirmedPaychecks",
    "payPeriods",
    "billBeforePaydaySnapshots",
    "safeToSpendSnapshots",
    "paycheckWatchOuts",
    "syncLogs",
    "auditLogs"
  ]) {
    for (const [id, record] of Object.entries(store[table])) {
      if (record.userId === userId) delete store[table][id];
    }
  }
  saveStore(store);
  return sendJson(res, 200, { status: "deleted" });
}

async function plaidPost(endpoint, payload) {
  if (!config.plaidClientId || !config.plaidSecret) {
    throw new Error("Plaid credentials are required when PLAID_MOCK_MODE=false.");
  }
  const host = plaidHosts[config.plaidEnv];
  if (!host) throw new Error(`Unsupported PLAID_ENV: ${config.plaidEnv}`);

  const response = await fetch(`${host}${endpoint}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      client_id: config.plaidClientId,
      secret: config.plaidSecret,
      ...payload
    })
  });
  const json = await response.json();
  if (!response.ok) {
    throw new Error(`Plaid request failed: ${json.error_code || response.status}`);
  }
  return json;
}

function normalizeLinkAccounts(accounts, institutionId, userId) {
  return accounts.map((account) => ({
    id: `acct-${crypto.randomUUID()}`,
    userId,
    institutionId,
    plaidAccountId: account.id || account.account_id,
    name: account.name || "Account",
    mask: account.mask || "",
    type: account.type || "unknown",
    subtype: account.subtype || "",
    status: "connected",
    lastSyncedAt: null
  }));
}

function normalizeTransaction(transaction, userId, institutionId) {
  return {
    id: transaction.transaction_id,
    transactionId: transaction.transaction_id,
    userId,
    institutionId,
    plaidAccountId: transaction.account_id,
    merchantName: transaction.merchant_name || transaction.name || "Unknown merchant",
    originalDescription: transaction.name || transaction.original_description || transaction.merchant_name || "Unknown merchant",
    amountCents: Math.round(Number(transaction.amount || 0) * 100),
    currency: transaction.iso_currency_code || transaction.unofficial_currency_code || "USD",
    date: transaction.date,
    authorizedDate: transaction.authorized_date || null,
    pending: Boolean(transaction.pending),
    category: transaction.personal_finance_category?.primary || (transaction.category || []).join(" / ") || "",
    paymentChannel: transaction.payment_channel || "",
    logoUrl: transaction.logo_url || null,
    website: transaction.website || null,
    createdAt: nowIso()
  };
}

function detectCandidates(userId, currentStore = store) {
  const transactions = Object.values(currentStore.bankTransactions)
    .filter((transaction) => transaction.userId === userId && !transaction.pending && isRecurringOutflow(transaction))
    .sort((a, b) => a.date.localeCompare(b.date));
  const groups = new Map();
  for (const transaction of transactions) {
    const key = `${transaction.plaidAccountId}:${normalizeMerchant(transaction.merchantName)}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(transaction);
  }

  for (const group of groups.values()) {
    for (const cluster of clusterBySimilarAmount(group)) {
      const candidate = buildRecurringCandidate(userId, cluster, currentStore);
      if (!candidate) continue;
      if (["confirmed", "ignored", "dismissed"].includes(currentStore.subscriptionCandidates[candidate.id]?.status)) continue;
      currentStore.subscriptionCandidates[candidate.id] = {
        ...candidate,
        createdAt: currentStore.subscriptionCandidates[candidate.id]?.createdAt || nowIso(),
        updatedAt: nowIso()
      };
      currentStore.recurringStreams[candidate.streamId] = {
        id: candidate.streamId,
        userId,
        accountId: candidate.accountId,
        merchantName: candidate.merchantName,
        cadence: candidate.cadence,
        averageAmountCents: candidate.averageAmountCents,
        lastChargeDate: candidate.lastChargeDate,
        predictedNextChargeDate: candidate.predictedNextChargeDate,
        transactionIds: candidate.transactionsUsed,
        confidenceScore: candidate.confidenceScore,
        streamType: candidate.candidateType,
        updatedAt: nowIso()
      };
    }
  }
}

function buildRecurringCandidate(userId, transactions, currentStore = store) {
  if (transactions.length < 2) return null;
  const sorted = [...transactions].sort((a, b) => a.date.localeCompare(b.date));
  const gaps = sorted.slice(1).map((transaction, index) => dayDiff(sorted[index].date, transaction.date));
  const cadence = classifyCadence(sorted, gaps);
  if (!cadence && sorted.length < 3) return null;
  const latest = sorted.at(-1);
  const averageGap = gaps.length ? gaps.reduce((sum, gap) => sum + gap, 0) / gaps.length : 30;
  const averageAmountCents = Math.round(sorted.reduce((sum, transaction) => sum + transaction.amountCents, 0) / sorted.length);
  const amountVarianceCents = amountSpread(sorted);
  const stableAmount = amountVarianceCents <= stableAmountTolerance(averageAmountCents);
  const candidateType = classifyCandidateType(sorted);
  const confidenceScore = confidenceFor(sorted.length, cadence, stableAmount);
  const predictedNextChargeDate = addDays(latest.date, predictedGapDays(cadence, averageGap));
  const window = predictionWindow(predictedNextChargeDate, cadence, candidateType, stableAmount);
  const watchOuts = predictionWatchOuts(sorted, predictedNextChargeDate, window, averageAmountCents);
  const merchantKey = normalizeMerchant(latest.merchantName);
  const amountKey = Math.round(averageAmountCents / 100);
  const id = `cand-${crypto.createHash("sha256").update(`${latest.plaidAccountId}:${merchantKey}:${amountKey}`).digest("hex").slice(0, 16)}`;
  const streamId = `stream-${crypto.createHash("sha256").update(`${latest.plaidAccountId}:${merchantKey}:${amountKey}`).digest("hex").slice(0, 16)}`;
  const account = Object.values(currentStore.connectedAccounts).find((item) => item.plaidAccountId === latest.plaidAccountId || item.id === latest.plaidAccountId);
  const reasonDetected = `${sorted.length} recurring outflows with ${stableAmount ? "stable" : "variable"} amount and ${cadence || "Irregular but repeated"} cadence; marked as ${candidateType} for review.`;

  return {
    id,
    streamId,
    userId,
    accountId: account?.id || latest.plaidAccountId,
    merchantName: latest.merchantName,
    averageAmountCents,
    amountVarianceCents,
    amountCents: averageAmountCents,
    lastChargeDate: latest.date,
    predictedNextChargeDate,
    nextChargeDate: predictedNextChargeDate,
    nextChargeWindowStart: window.start,
    nextChargeWindowEnd: window.end,
    cadence: cadence || "Irregular but repeated",
    confidenceScore,
    confidence: confidenceScore,
    transactionsUsed: sorted.map((transaction) => transaction.transactionId || transaction.id),
    matchingTransactions: sorted.slice(-3).map((transaction) => ({
      transactionId: transaction.transactionId || transaction.id,
      date: transaction.date,
      merchantName: transaction.merchantName,
      amountCents: transaction.amountCents,
      currency: transaction.currency || "USD"
    })),
    accountNickname: account?.name || "",
    category: latest.category || "",
    reasonDetected,
    candidateType,
    inactive: watchOuts.includes("Subscription not seen recently"),
    watchOuts,
    reminderDays: 7,
    status: "pending"
  };
}

function clusterBySimilarAmount(transactions) {
  const clusters = [];
  for (const transaction of [...transactions].sort((a, b) => a.amountCents - b.amountCents)) {
    const cluster = clusters.find((candidate) => {
      const average = Math.round(candidate.reduce((sum, item) => sum + item.amountCents, 0) / candidate.length);
      return isSimilarAmount(transaction.amountCents, average, transaction);
    });
    if (cluster) cluster.push(transaction);
    else clusters.push([transaction]);
  }
  return clusters.filter((cluster) => cluster.length >= 2);
}

function classifyCadence(sorted, gaps) {
  if (!gaps.length) return null;
  const average = gaps.reduce((sum, gap) => sum + gap, 0) / gaps.length;
  const spread = Math.max(...gaps) - Math.min(...gaps);
  const days = sorted.map((transaction) => Number(transaction.date.slice(8, 10)));
  const stableDayOfMonth = Math.max(...days) - Math.min(...days) <= 3;
  if (average >= 6 && average <= 8 && spread <= 2) return "Weekly";
  if (average >= 13 && average <= 16 && spread <= 3) return "Biweekly";
  if (average >= 26 && average <= 30 && spread <= 3 && !stableDayOfMonth) return "Every 4 weeks";
  if (average >= 25 && average <= 35 && spread <= 7) return "Monthly";
  if (average >= 80 && average <= 100 && spread <= 14) return "Quarterly";
  if (average >= 170 && average <= 195 && spread <= 21) return "Semiannual";
  if (average >= 330 && average <= 395 && spread <= 35) return "Annual";
  return sorted.length >= 3 ? "Irregular but repeated" : null;
}

function confidenceFor(count, cadence, stableAmount) {
  if (count >= 3 && cadence && cadence !== "Irregular but repeated" && stableAmount) return 0.92;
  if (count >= 3 && cadence) return 0.78;
  if (count === 2 && cadence === "Monthly" && stableAmount) return 0.64;
  return 0.42;
}

function predictedGapDays(cadence, averageGap) {
  if (cadence === "Weekly" || cadence === "weekly") return 7;
  if (cadence === "Biweekly" || cadence === "biweekly") return 14;
  if (cadence === "semi-monthly") return 15;
  if (cadence === "Every 4 weeks") return 28;
  if (cadence === "Monthly" || cadence === "monthly") return 30;
  if (cadence === "Quarterly") return 91;
  if (cadence === "Semiannual") return 182;
  if (cadence === "Annual") return 365;
  return Math.max(7, Math.round(averageGap));
}

function predictionWindow(predictedDate, cadence, candidateType, stableAmount) {
  let spread = 1;
  if (candidateType === "bill" || !stableAmount) spread = 5;
  else if (cadence === "Annual") spread = 7;
  else if (cadence === "Quarterly" || cadence === "Semiannual") spread = 4;
  else if (cadence === "Monthly") spread = 2;
  return {
    start: addDays(predictedDate, -spread),
    end: addDays(predictedDate, spread)
  };
}

function predictionWatchOuts(sorted, predictedDate, window, averageAmountCents) {
  const watchOuts = [];
  const latest = sorted.at(-1);
  const previous = sorted.at(-2);
  if (previous && latest.amountCents > Math.round(previous.amountCents * 1.08) && latest.amountCents - previous.amountCents >= 100) {
    watchOuts.push("Price increased");
  }
  const gaps = sorted.slice(1).map((transaction, index) => dayDiff(sorted[index].date, transaction.date));
  const previousGaps = gaps.slice(0, -1);
  const expectedGap = previousGaps.length ? previousGaps.reduce((sum, gap) => sum + gap, 0) / previousGaps.length : null;
  const latestGap = gaps.at(-1);
  if (expectedGap && latestGap && latestGap < expectedGap - 3) {
    watchOuts.push("Charged earlier than usual");
  }
  if (sorted.length >= 2) {
    const lastTwo = sorted.slice(-2);
    const duplicate =
      dayDiff(lastTwo[0].date, lastTwo[1].date) <= 3 &&
      Math.abs(lastTwo[0].amountCents - lastTwo[1].amountCents) <= stableAmountTolerance(averageAmountCents);
    if (duplicate) watchOuts.push("Duplicate charge suspected");
  }
  if (Date.parse(`${window.end}T00:00:00Z`) + 14 * 86_400_000 < Date.now()) {
    watchOuts.push("Subscription not seen recently");
  }
  const text = sorted.map((transaction) => `${transaction.merchantName} ${transaction.originalDescription || ""} ${transaction.category || ""}`).join(" ").toLowerCase();
  if (text.includes("trial") || text.includes("free trial")) {
    watchOuts.push("Free trial may convert soon");
  }
  const day = new Date(`${predictedDate}T00:00:00Z`).getUTCDay();
  if (day === 0 || day === 6) {
    watchOuts.push("May shift for weekend or holiday");
  }
  return [...new Set(watchOuts)];
}

function classifyCandidateType(transactions) {
  const text = transactions.map((transaction) => `${transaction.merchantName} ${transaction.originalDescription || ""} ${transaction.category || ""}`).join(" ").toLowerCase();
  const billTerms = ["utility", "electric", "water", "gas", "phone", "wireless", "insurance", "internet", "telecom"];
  return billTerms.some((term) => text.includes(term)) ? "bill" : "subscription";
}

function isRecurringOutflow(transaction) {
  const text = `${transaction.merchantName} ${transaction.originalDescription || ""} ${transaction.category || ""}`.toLowerCase();
  if (transaction.amountCents <= 0) return false;
  return !["payroll", "paycheck", "salary", "income", "deposit", "refund"].some((term) => text.includes(term));
}

function isSimilarAmount(amountCents, baselineCents, transaction) {
  const variableBill = classifyCandidateType([transaction]) === "bill";
  const tolerance = variableBill ? Math.max(2_500, Math.round(baselineCents * 0.45)) : stableAmountTolerance(baselineCents);
  return Math.abs(amountCents - baselineCents) <= tolerance;
}

function stableAmountTolerance(amountCents) {
  return Math.max(100, Math.round(amountCents * 0.08));
}

function amountSpread(transactions) {
  return Math.max(...transactions.map((transaction) => transaction.amountCents)) -
    Math.min(...transactions.map((transaction) => transaction.amountCents));
}

function seedMockData(currentStore, userId, institutionId = "mock-institution", accountId = "mock-account") {
  requireUser(currentStore, userId);
  const institution = currentStore.connectedInstitutions[institutionId] || {
    id: institutionId,
    userId,
    plaidItemId: "mock-item",
    institutionName: "Plaid Sandbox Bank",
    encryptedAccessToken: encryptToken("mock-plaid-token-placeholder"),
    transactionCursor: null,
    status: "connected",
    createdAt: nowIso(),
    disconnectedAt: null
  };
  currentStore.connectedInstitutions[institutionId] = institution;
  currentStore.connectedAccounts[accountId] = currentStore.connectedAccounts[accountId] || {
    id: accountId,
    userId,
    institutionId,
    plaidAccountId: accountId,
    name: "Everyday Checking",
    mask: "0000",
    type: "depository",
    subtype: "checking",
    status: "connected",
    lastSyncedAt: nowIso()
  };

  seedMockTransactions(currentStore, userId, institutionId, accountId);
  detectCandidates(userId, currentStore);
}

function seedMockTransactions(currentStore, userId, institutionId, accountId) {
  const today = new Date().toISOString().slice(0, 10);
  const recurringSeeds = [
    { merchantName: "Netflix", amountCents: 1599, category: "ENTERTAINMENT", cadenceDays: 30, count: 4 },
    { merchantName: "Spotify", amountCents: 1199, category: "ENTERTAINMENT", cadenceDays: 30, count: 4 },
    { merchantName: "Adobe", amountCents: 3299, category: "GENERAL_SERVICES", cadenceDays: 30, count: 4 },
    { merchantName: "Google", amountCents: 299, category: "GENERAL_SERVICES", cadenceDays: 30, count: 3 },
    { merchantName: "Apple", amountCents: 999, category: "GENERAL_MERCHANDISE", cadenceDays: 30, count: 3 },
    { merchantName: "Amazon Prime", amountCents: 13900, category: "GENERAL_MERCHANDISE", cadenceDays: 365, count: 2 },
    { merchantName: "Gym Membership", amountCents: 2999, category: "GENERAL_SERVICES", cadenceDays: 30, count: 4 },
    { merchantName: "Phone Bill", amountCents: 8499, category: "PHONE", cadenceDays: 30, count: 4, variable: [0, 320, -180, 260] },
    { merchantName: "Insurance", amountCents: 11200, category: "INSURANCE", cadenceDays: 30, count: 4, variable: [0, 0, 210, 210] },
    { merchantName: "Electric Utility", amountCents: 15640, category: "UTILITIES", cadenceDays: 30, count: 4, variable: [-2200, 1400, 3100, -800] },
    { merchantName: "Cloud Backup", amountCents: 999, category: "GENERAL_SERVICES", cadenceDays: 30, count: 4, variable: [0, 0, 0, 100] },
    { merchantName: "Video Stream Plus", amountCents: 1499, category: "ENTERTAINMENT", cadenceDays: 30, count: 3 }
  ];

  for (const seed of recurringSeeds) {
    for (let index = 0; index < seed.count; index += 1) {
      const date = addDays(today, -seed.cadenceDays * (seed.count - index));
      const amountCents = seed.amountCents + (seed.variable?.[index] || 0);
      const id = `mock-tx-${normalizeMerchant(seed.merchantName).replaceAll(" ", "-")}-${index}-${accountId}`;
      currentStore.bankTransactions[id] = {
        id,
        transactionId: id,
        userId,
        institutionId,
        plaidAccountId: accountId,
        merchantName: seed.merchantName,
        originalDescription: seed.merchantName,
        amountCents,
        currency: "USD",
        date,
        authorizedDate: date,
        pending: false,
        category: seed.category,
        paymentChannel: "online",
        logoUrl: null,
        website: null,
        createdAt: nowIso()
      };
    }
  }

  const duplicateDate = addDays(today, -28);
  const duplicateId = `mock-tx-video-stream-plus-duplicate-${accountId}`;
  currentStore.bankTransactions[duplicateId] = {
    id: duplicateId,
    transactionId: duplicateId,
    userId,
    institutionId,
    plaidAccountId: accountId,
    merchantName: "Video Stream Plus",
    originalDescription: "Video Stream Plus",
    amountCents: 1499,
    currency: "USD",
    date: addDays(duplicateDate, 2),
    authorizedDate: duplicateDate,
    pending: false,
    category: "ENTERTAINMENT",
    paymentChannel: "online",
    logoUrl: null,
    website: null,
    createdAt: nowIso()
  };

  const retailSeeds = [
    ["Amazon Marketplace", 4372, 12],
    ["Amazon Marketplace", 895, 7],
    ["Target", 6421, 20],
    ["Grocery Store", 8120, 4],
    ["Coffee Shop", 525, 2],
    ["Payroll Deposit", -85000, 14],
    ["Payroll Deposit", -85000, 28],
    ["Payroll Deposit", -85000, 42],
    ["Payroll Deposit", -85000, 56]
  ];
  for (const [merchantName, amountCents, daysAgo] of retailSeeds) {
    const date = addDays(today, -daysAgo);
    const id = `mock-tx-${normalizeMerchant(merchantName).replaceAll(" ", "-")}-${daysAgo}-${accountId}`;
    currentStore.bankTransactions[id] = {
      id,
      transactionId: id,
      userId,
      institutionId,
      plaidAccountId: accountId,
      merchantName,
      originalDescription: merchantName,
      amountCents,
      currency: "USD",
      date,
      authorizedDate: date,
      pending: false,
      category: merchantName.includes("Payroll") ? "INCOME" : "GENERAL_MERCHANDISE",
      paymentChannel: "in store",
      logoUrl: null,
      website: null,
      createdAt: nowIso()
    };
  }
}

function requireUser(currentStore, userId) {
  currentStore.users[userId] = currentStore.users[userId] || {
    id: userId,
    createdAt: nowIso(),
    mode: config.mockMode ? "mock" : "plaid"
  };
  return currentStore.users[userId];
}

function safeAccounts(userId, currentStore = store) {
  return Object.values(currentStore.connectedAccounts)
    .filter((account) => account.userId === userId)
    .map(({ plaidAccountId, ...safe }) => ({
      ...safe,
      institutionName: currentStore.connectedInstitutions[safe.institutionId]?.institutionName || "Connected institution"
    }));
}

function candidatesForUser(userId, currentStore = store) {
  return Object.values(currentStore.subscriptionCandidates)
    .filter((candidate) => candidate.userId === userId && (candidate.status === "pending" || candidate.status === "new"))
    .sort((a, b) => (a.nextChargeDate || "").localeCompare(b.nextChargeDate || ""));
}

function transactionsForUser(userId, currentStore = store) {
  return Object.values(currentStore.bankTransactions)
    .filter((transaction) => transaction.userId === userId)
    .sort((a, b) => a.date.localeCompare(b.date))
    .map(({ institutionId, ...safe }) => ({
      ...safe,
      accountNickname: accountNicknameForPlaidAccount(userId, safe.plaidAccountId, currentStore)
    }));
}

function detectPaycheckPilotData(userId, currentStore = store) {
  ensureStoreShape(currentStore);
  detectCandidates(userId, currentStore);
  const transactions = Object.values(currentStore.bankTransactions)
    .filter((transaction) => transaction.userId === userId && !transaction.pending)
    .sort((a, b) => a.date.localeCompare(b.date));
  detectIncomeStreams(userId, transactions, currentStore);
  detectPaycheckCandidates(userId, currentStore);
  detectBillBeforePaydayCandidates(userId, currentStore);
}

function detectIncomeStreams(userId, transactions, currentStore = store) {
  const income = transactions
    .map((transaction) => ({ transaction, signal: incomeSignalFor(transaction) }))
    .filter((item) => item.signal.isPossibleIncome);
  const groups = groupBy(income, (item) => `${item.transaction.plaidAccountId}:${item.signal.normalizedSourceName}`);
  for (const group of groups.values()) {
    const sortedItems = [...group].sort((a, b) => a.transaction.date.localeCompare(b.transaction.date));
    const sorted = sortedItems.map((item) => item.transaction);
    const signals = sortedItems.map((item) => item.signal);
    const gaps = sorted.slice(1).map((transaction, index) => dayDiff(sorted[index].date, transaction.date));
    const cadence = classifyIncomeCadence(sorted, gaps, signals);
    const latest = sorted.at(-1);
    const latestSignal = signals.at(-1);
    const averageGap = gaps.length ? gaps.reduce((sum, gap) => sum + gap, 0) / gaps.length : incomeDefaultGapDays(cadence);
    const averageAmountCents = Math.round(sorted.reduce((sum, transaction) => sum + Math.abs(transaction.amountCents), 0) / sorted.length);
    const amountVarianceCents = maxAmountVarianceCents(sorted);
    const stableAmount = hasStableIncomeAmount(sorted);
    const confidence = incomeConfidence(sorted.length, cadence, stableAmount, signals);
    if (!shouldCreateIncomeCandidate(sorted, cadence, confidence, signals)) continue;
    const id = `income-${stableHash(`${latest.plaidAccountId}:${latestSignal.normalizedSourceName}:${averageAmountCents}`)}`;
    const predictedNextPayDate = addDays(latest.date, predictedGapDays(cadence, averageGap));
    currentStore.incomeStreams[id] = {
      id,
      userId,
      accountId: accountIdForPlaidAccount(userId, latest.plaidAccountId, currentStore),
      sourceName: latestSignal.sourceName,
      normalizedSourceName: latestSignal.normalizedSourceName,
      payerName: latestSignal.sourceName,
      cadence,
      incomeType: incomeTypeFor(signals),
      averageAmountCents,
      averageAmount: averageAmountCents,
      amountVarianceCents,
      lastPayDate: latest.date,
      lastDepositDate: latest.date,
      predictedNextPayDate,
      predictedNextPayday: predictedNextPayDate,
      transactionIds: sorted.map((transaction) => transaction.transactionId || transaction.id),
      transactionsUsed: sorted.map((transaction) => transaction.transactionId || transaction.id),
      reasonDetected: incomeReasonDetected(sorted.length, cadence, stableAmount, signals),
      confidenceScore: confidence,
      status: "pending",
      updatedAt: nowIso()
    };
  }
}

function detectPaycheckCandidates(userId, currentStore = store) {
  for (const stream of Object.values(currentStore.incomeStreams).filter((item) => item.userId === userId)) {
    const id = `paycheck-${stableHash(`${stream.id}:${stream.predictedNextPayDate}`)}`;
    const existing = currentStore.paycheckCandidates[id];
    if (existing && ["confirmed", "ignored"].includes(existing.status)) continue;
    currentStore.paycheckCandidates[id] = {
      id,
      userId,
      incomeStreamId: stream.id,
      sourceName: stream.sourceName || stream.payerName,
      normalizedSourceName: stream.normalizedSourceName || normalizeMerchant(stream.payerName),
      payerName: stream.payerName,
      expectedAmountCents: stream.averageAmountCents,
      averageAmount: stream.averageAmountCents,
      averageAmountCents: stream.averageAmountCents,
      lastDepositDate: stream.lastDepositDate || stream.lastPayDate,
      predictedPayDate: stream.predictedNextPayDate,
      predictedNextPayday: stream.predictedNextPayday || stream.predictedNextPayDate,
      cadence: stream.cadence,
      confidenceScore: stream.confidenceScore,
      transactionsUsed: stream.transactionsUsed || stream.transactionIds || [],
      accountNickname: accountNicknameForAccountId(stream.accountId, currentStore),
      reasonDetected: stream.reasonDetected || "Detected from recurring deposits.",
      status: "pending",
      createdAt: existing?.createdAt || nowIso(),
      updatedAt: nowIso()
    };
  }
}

function detectBillBeforePaydayCandidates(userId, currentStore = store) {
  const pendingBills = candidatesForUser(userId, currentStore)
    .filter((candidate) => candidate.candidateType === "bill" || likelyBill(candidate));
  for (const bill of pendingBills) {
    const id = `bill-before-${stableHash(`${bill.id}:${bill.nextChargeDate}`)}`;
    currentStore.billBeforePaydaySnapshots[id] ||= {
      id,
      userId,
      payPeriodId: null,
      nextPayday: null,
      billIds: [],
      totalCents: 0,
      createdAt: nowIso()
    };
  }
}

function currentPayPeriod(userId, asOf = todayIso(), currentStore = store) {
  const existing = Object.values(currentStore.payPeriods)
    .filter((period) => period.userId === userId && period.startDate <= asOf && period.nextPayday >= asOf)
    .sort((a, b) => a.nextPayday.localeCompare(b.nextPayday))[0];
  if (existing) return existing;
  const stream = Object.values(currentStore.incomeStreams)
    .filter((item) => item.userId === userId)
    .sort((a, b) => a.predictedNextPayDate.localeCompare(b.predictedNextPayDate))[0];
  const lastPayDate = stream?.lastPayDate || addDays(asOf, -14);
  const nextPayday = stream?.predictedNextPayDate || addDays(asOf, 14);
  const period = {
    id: `period-${stableHash(`${userId}:${lastPayDate}:${nextPayday}`)}`,
    userId,
    startDate: lastPayDate,
    nextPayday,
    expectedPaycheckCents: stream?.averageAmountCents || 0,
    safetyBufferCents: 20000,
    currentBalanceCents: estimateCurrentBalanceCents(userId, currentStore),
    savingsGoalCents: 0,
    essentialsAllowanceCents: 0,
    gasAllowanceCents: 0,
    groceryAllowanceCents: 0,
    alreadySpentCents: 0,
    manualBills: [],
    excludedAccountIds: [],
    excludedTransactionIds: [],
    excludedCandidateIds: [],
    excludedSubscriptionIds: [],
    createdAt: nowIso(),
    updatedAt: nowIso()
  };
  currentStore.payPeriods[period.id] = period;
  return period;
}

function billsBeforePayday(userId, payPeriod, currentStore = store) {
  const excludedCandidates = new Set(payPeriod.excludedCandidateIds || []);
  const excludedAccounts = new Set(payPeriod.excludedAccountIds || []);
  const excludedSubscriptions = new Set(payPeriod.excludedSubscriptionIds || []);
  const detectedBills = Object.values(currentStore.subscriptionCandidates)
    .filter((candidate) => candidate.userId === userId && (candidate.status === "pending" || candidate.status === "new"))
    .filter((candidate) => !excludedCandidates.has(candidate.id))
    .filter((candidate) => !excludedAccounts.has(candidate.accountId))
    .sort((a, b) => (a.nextChargeDate || "").localeCompare(b.nextChargeDate || ""))
    .filter((candidate) => !candidate.nextChargeDate || candidate.nextChargeDate <= payPeriod.nextPayday)
    .filter((candidate) => candidate.candidateType === "bill" || likelyBill(candidate))
    .map((candidate) => ({
      id: `bill-${candidate.id}`,
      userId,
      candidateId: candidate.id,
      merchantName: candidate.merchantName,
      amountCents: candidate.amountCents || candidate.averageAmountCents,
      expectedDate: candidate.nextChargeDate,
      windowStart: candidate.nextChargeWindowStart,
      windowEnd: candidate.nextChargeWindowEnd,
      cadence: candidate.cadence,
      confidenceScore: candidate.confidenceScore,
      accountNickname: candidate.accountNickname || accountNicknameForAccountId(candidate.accountId, currentStore),
      category: candidate.category || "Detected bill",
      status: "pending"
    }));
  const confirmedBills = Object.values(currentStore.confirmedSubscriptions)
    .filter((item) => item.userId === userId)
    .filter((item) => !excludedSubscriptions.has(item.id))
    .filter((item) => !excludedAccounts.has(item.sourceAccountId))
    .filter((item) => item.nextChargeDate && item.nextChargeDate <= payPeriod.nextPayday)
    .filter((item) => likelyBill(item))
    .map((item) => ({
      id: `confirmed-bill-${item.id}`,
      userId,
      subscriptionId: item.id,
      merchantName: item.merchantName,
      amountCents: Number(item.amountCents || 0),
      expectedDate: item.nextChargeDate,
      cadence: item.cadence,
      confidenceScore: 1,
      accountNickname: accountNicknameForAccountId(item.sourceAccountId, currentStore),
      category: "Confirmed bill",
      status: "confirmed"
    }));
  return [...confirmedBills, ...detectedBills, ...manualBillsBeforePayday(userId, payPeriod)]
    .sort((a, b) => (a.expectedDate || "").localeCompare(b.expectedDate || ""));
}

function buildSafeToSpendSnapshot(userId, payPeriod, currentStore = store) {
  const bills = billsBeforePayday(userId, payPeriod, currentStore);
  const recurringCharges = recurringChargesBeforePayday(userId, payPeriod, currentStore);
  const committedBillsCents = sumCents(bills);
  const recurringChargesCents = sumCents(recurringCharges);
  const bufferAmountCents = Number(payPeriod.safetyBufferCents || 0);
  const savingsGoalCents = Number(payPeriod.savingsGoalCents || 0);
  const essentialsAllowanceCents = Number(payPeriod.essentialsAllowanceCents || 0) +
    Number(payPeriod.gasAllowanceCents || 0) +
    Number(payPeriod.groceryAllowanceCents || 0);
  const alreadySpentCents = Number(payPeriod.alreadySpentCents || 0);
  const currentBalanceCents = Number.isFinite(Number(payPeriod.currentBalanceCents)) && payPeriod.currentBalanceCents !== null
    ? Number(payPeriod.currentBalanceCents)
    : estimateCurrentBalanceCents(userId, currentStore);
  const expectedIncomeCents = expectedIncomeForPeriod(userId, payPeriod, currentStore);
  const totalCommittedCents = committedBillsCents + recurringChargesCents + bufferAmountCents + savingsGoalCents + essentialsAllowanceCents + alreadySpentCents;
  const rawSafeToSpendCents = currentBalanceCents - totalCommittedCents;
  const safeToSpendCents = Math.max(0, rawSafeToSpendCents);
  const daysRemaining = Math.max(1, dayDiff(todayIso(), payPeriod.nextPayday));
  const safeToSpendDailyCents = Math.floor(safeToSpendCents / daysRemaining);
  const missingData = missingSafeToSpendData(userId, payPeriod, currentStore);
  const confidenceScore = safeToSpendConfidence(payPeriod, bills, recurringCharges, missingData, currentStore);
  const explanation = safeToSpendExplanation(safeToSpendCents, safeToSpendDailyCents, payPeriod, bills, recurringCharges, missingData);
  const watchOuts = buildSafeToSpendWatchOuts(userId, payPeriod, {
    rawSafeToSpendCents,
    safeToSpendCents,
    safeToSpendDailyCents,
    bills,
    recurringCharges,
    expectedIncomeCents,
    currentBalanceCents,
    missingData
  }, currentStore);
  return {
    id: `safe-${stableHash(`${payPeriod.id}:${totalCommittedCents}:${currentBalanceCents}:${safeToSpendCents}`)}`,
    userId,
    payPeriodId: payPeriod.id,
    generatedAt: nowIso(),
    currentBalanceCents,
    currentBalance: currentBalanceCents,
    nextPayday: payPeriod.nextPayday,
    expectedPaycheckCents: payPeriod.expectedPaycheckCents,
    expectedIncomeCents,
    expectedIncome: expectedIncomeCents,
    committedBillsCents,
    committedBills: committedBillsCents,
    recurringChargesCents,
    recurringCharges: recurringChargesCents,
    bufferAmountCents,
    bufferAmount: bufferAmountCents,
    savingsGoalCents,
    savingsGoal: savingsGoalCents,
    essentialsAllowanceCents,
    essentialsAllowance: essentialsAllowanceCents,
    alreadySpentCents,
    totalCommittedCents,
    billsBeforePaydayCents: committedBillsCents,
    safetyBufferCents: bufferAmountCents,
    projectedLeftoverCents: rawSafeToSpendCents,
    safeToSpendTotalCents: safeToSpendCents,
    safeToSpendTotal: safeToSpendCents,
    safeToSpendCents,
    safeToSpendDailyCents,
    safeToSpendDaily: safeToSpendDailyCents,
    confidenceScore,
    explanation,
    missingData,
    bills,
    recurringChargeItems: recurringCharges,
    watchOuts,
    disclaimer: "Paycheck Pilot is a planning estimate, not financial advice.",
    warning: rawSafeToSpendCents < 0 ? "Your plan is tight before the next paycheck." :
      safeToSpendDailyCents <= 1000 ? "Safe-to-spend is limited each day." : null,
    createdAt: nowIso()
  };
}

function buildPaycheckWatchOuts(userId, payPeriod, currentStore = store) {
  const safe = buildSafeToSpendSnapshot(userId, payPeriod, currentStore);
  return safe.watchOuts;
}

function manualBillsBeforePayday(userId, payPeriod) {
  return (payPeriod.manualBills || [])
    .filter((bill) => !bill.dueDate || bill.dueDate <= payPeriod.nextPayday)
    .map((bill, index) => ({
      id: bill.id || `manual-bill-${stableHash(`${payPeriod.id}:${bill.name}:${bill.dueDate || index}:${bill.amountCents}`)}`,
      userId,
      merchantName: bill.name || "Manual bill",
      amountCents: Number(bill.amountCents || 0),
      expectedDate: bill.dueDate || payPeriod.nextPayday,
      cadence: bill.cadence || "manual",
      confidenceScore: 1,
      accountNickname: "Manual entry",
      category: bill.category || "Manual bill",
      status: "manual"
    }));
}

function recurringChargesBeforePayday(userId, payPeriod, currentStore = store) {
  const excludedCandidates = new Set(payPeriod.excludedCandidateIds || []);
  const excludedSubscriptions = new Set(payPeriod.excludedSubscriptionIds || []);
  const excludedAccounts = new Set(payPeriod.excludedAccountIds || []);
  const confirmed = Object.values(currentStore.confirmedSubscriptions)
    .filter((item) => item.userId === userId)
    .filter((item) => !excludedSubscriptions.has(item.id))
    .filter((item) => !excludedAccounts.has(item.sourceAccountId))
    .filter((item) => item.nextChargeDate && item.nextChargeDate <= payPeriod.nextPayday)
    .filter((item) => !likelyBill(item))
    .map((item) => ({
      id: `confirmed-renewal-${item.id}`,
      userId,
      subscriptionId: item.id,
      merchantName: item.merchantName,
      amountCents: Number(item.amountCents || 0),
      expectedDate: item.nextChargeDate,
      cadence: item.cadence,
      confidenceScore: 1,
      accountNickname: accountNicknameForAccountId(item.sourceAccountId, currentStore),
      category: "Confirmed renewal",
      status: "confirmed"
    }));
  const detected = Object.values(currentStore.subscriptionCandidates)
    .filter((candidate) => candidate.userId === userId && (candidate.status === "pending" || candidate.status === "new"))
    .filter((candidate) => !excludedCandidates.has(candidate.id))
    .filter((candidate) => !excludedAccounts.has(candidate.accountId))
    .filter((candidate) => !candidate.nextChargeDate || candidate.nextChargeDate <= payPeriod.nextPayday)
    .filter((candidate) => candidate.candidateType !== "bill" && !likelyBill(candidate))
    .map((candidate) => ({
      id: `detected-renewal-${candidate.id}`,
      userId,
      candidateId: candidate.id,
      merchantName: candidate.merchantName,
      amountCents: Number(candidate.amountCents || candidate.averageAmountCents || 0),
      expectedDate: candidate.nextChargeDate,
      windowStart: candidate.nextChargeWindowStart,
      windowEnd: candidate.nextChargeWindowEnd,
      cadence: candidate.cadence,
      confidenceScore: candidate.confidenceScore,
      accountNickname: candidate.accountNickname || accountNicknameForAccountId(candidate.accountId, currentStore),
      category: candidate.category || "Detected renewal",
      status: "pending"
    }));
  return [...confirmed, ...detected].sort((a, b) => (a.expectedDate || "").localeCompare(b.expectedDate || ""));
}

function expectedIncomeForPeriod(userId, payPeriod, currentStore = store) {
  const confirmed = Object.values(currentStore.confirmedPaychecks)
    .filter((item) => item.userId === userId)
    .filter((item) => item.payDate === payPeriod.nextPayday)
    .sort((a, b) => b.confirmedAt.localeCompare(a.confirmedAt))[0];
  if (confirmed) return Number(confirmed.amountCents || 0);
  return Number(payPeriod.expectedPaycheckCents || 0);
}

function missingSafeToSpendData(userId, payPeriod, currentStore = store) {
  const missing = [];
  if (!Number.isFinite(Number(payPeriod.currentBalanceCents)) || payPeriod.currentBalanceCents === null) {
    missing.push({ field: "currentBalance", message: "Add a current balance or sync an account balance." });
  }
  if (!payPeriod.nextPayday) missing.push({ field: "nextPayday", message: "Set your next payday." });
  if (!Number(payPeriod.expectedPaycheckCents) && !Object.values(currentStore.incomeStreams).some((item) => item.userId === userId)) {
    missing.push({ field: "expectedIncome", message: "Confirm an income source or enter an expected paycheck." });
  }
  if (!Object.values(currentStore.connectedAccounts).some((account) => account.userId === userId) && !(payPeriod.manualBills || []).length) {
    missing.push({ field: "bills", message: "Connect an account or add bills manually." });
  }
  return missing;
}

function safeToSpendConfidence(payPeriod, bills, recurringCharges, missingData, currentStore = store) {
  let score = 0.95;
  if (missingData.length) score -= missingData.length * 0.18;
  if (!Number.isFinite(Number(payPeriod.currentBalanceCents)) || payPeriod.currentBalanceCents === null) score -= 0.18;
  if (!bills.length && !recurringCharges.length) score -= 0.12;
  const pending = [...bills, ...recurringCharges].filter((item) => item.status === "pending");
  if (pending.length) score -= Math.min(0.18, pending.length * 0.04);
  if (!Object.values(currentStore.confirmedPaychecks).some((item) => item.userId === payPeriod.userId)) score -= 0.04;
  return Math.max(0.2, Math.min(0.98, Number(score.toFixed(2))));
}

function safeToSpendExplanation(safeToSpendCents, safeToSpendDailyCents, payPeriod, bills, recurringCharges, missingData) {
  const paydayLabel = payPeriod.nextPayday || "your next payday";
  const parts = [`You have ${formatCents(safeToSpendCents)} safe to spend until ${paydayLabel}.`];
  parts.push(`${formatCents(safeToSpendDailyCents)}/day until next payday.`);
  const upcomingNames = [...bills, ...recurringCharges].slice(0, 2).map((item) => item.merchantName);
  if (upcomingNames.length) parts.push(`${upcomingNames.join(" and ")} hit before payday.`);
  if (missingData.length) parts.push(`This estimate is missing ${missingData.map((item) => item.field).join(", ")}.`);
  parts.push("This is an estimate, not financial advice.");
  return parts.join(" ");
}

function buildSafeToSpendWatchOuts(userId, payPeriod, context, currentStore = store) {
  const watchOuts = [];
  if (context.rawSafeToSpendCents < 0) {
    watchOuts.push(watchOut("tight-plan", userId, payPeriod.id, "Your plan is tight", "Committed money is higher than the current available amount.", "high"));
  } else if (context.safeToSpendDailyCents <= 1000) {
    watchOuts.push(watchOut("limited-daily", userId, payPeriod.id, "Daily safe-to-spend is limited", `${formatCents(context.safeToSpendDailyCents)}/day until next payday.`, "medium"));
  }
  for (const item of [...context.bills, ...context.recurringCharges].slice(0, 4)) {
    if (item.expectedDate && item.expectedDate >= todayIso()) {
      watchOuts.push(watchOut(`upcoming-${item.id}`, userId, payPeriod.id, `${item.merchantName} hits before payday`, `Expected around ${item.expectedDate} for ${formatCents(item.amountCents)}.`, "medium"));
    }
  }
  const stream = Object.values(currentStore.incomeStreams).find((item) => item.userId === userId && item.predictedNextPayDate === payPeriod.nextPayday);
  if (stream && stream.confidenceScore < 0.7) {
    watchOuts.push(watchOut(`income-${stream.id}`, userId, payPeriod.id, "Paycheck timing is an estimate", "Recent deposits do not have a strong cadence yet.", "low"));
  }
  const recentConfirmed = Object.values(currentStore.confirmedPaychecks)
    .filter((item) => item.userId === userId)
    .sort((a, b) => b.payDate.localeCompare(a.payDate));
  const lastConfirmed = recentConfirmed[0];
  if (lastConfirmed && context.expectedIncomeCents && Number(lastConfirmed.amountCents) < Math.round(context.expectedIncomeCents * 0.85)) {
    watchOuts.push(watchOut("lower-paycheck", userId, payPeriod.id, "Your next paycheck may be lower than usual", "The most recent confirmed paycheck was below the expected amount.", "medium"));
  }
  for (const missing of context.missingData) {
    watchOuts.push(watchOut(`missing-${missing.field}`, userId, payPeriod.id, "More info would improve this estimate", missing.message, "low"));
  }
  return watchOuts;
}

function sumCents(items) {
  return items.reduce((sum, item) => sum + Number(item.amountCents || 0), 0);
}

function watchOut(key, userId, payPeriodId, title, message, severity) {
  return {
    id: `watch-${stableHash(`${userId}:${payPeriodId}:${key}`)}`,
    userId,
    payPeriodId,
    title,
    message,
    severity,
    createdAt: nowIso()
  };
}

function incomeSignalFor(transaction) {
  const sourceName = incomeSourceName(transaction);
  const normalizedSourceName = normalizeIncomeSource(sourceName);
  const text = `${sourceName} ${transaction.category || ""} ${transaction.paymentChannel || ""}`.toLowerCase();
  const isInflow = transaction.amountCents < 0 || /\b(credit|deposit|income)\b/.test(text);
  const payroll = /\b(payroll|paycheck|salary|wages?|paystub|pay\s?roll|dir(?:ect)? dep(?:osit)?|ach credit|ppd id|company payroll)\b/.test(text);
  const gig = /\b(doordash|door dash|uber|ubereats|uber eats|lyft|instacart|grubhub|shipt|spark driver|roadie|fiverr|upwork|taskrabbit)\b/.test(text);
  const governmentBenefit = /\b(social security|ssa|ssi|ssdi|unemployment|ui benefit|treasury|irs treas|va benefit|veterans affairs|snap|ebt|state benefit)\b/.test(text);
  const transferApp = /\b(venmo|cash app|cashapp|zelle|paypal|apple cash|chime transfer|square cash)\b/.test(text);
  const refundOrReversal = /\b(refund|reversal|return|rebate|cashback|cash back|atm|mobile deposit|check deposit)\b/.test(text);
  const employerLike = isEmployerLikeSource(normalizedSourceName);
  return {
    sourceName,
    normalizedSourceName,
    isPossibleIncome: isInflow && !refundOrReversal && (payroll || gig || governmentBenefit || transferApp || employerLike),
    payroll,
    gig,
    governmentBenefit,
    transferApp,
    employerLike,
    reason: payroll ? "payroll wording" :
      gig ? "gig income source" :
      governmentBenefit ? "benefit deposit source" :
      transferApp ? "transfer app deposit" :
      employerLike ? "employer-like deposit source" : "deposit"
  };
}

function incomeSourceName(transaction) {
  return (transaction.merchantName || transaction.originalDescription || "Unknown income source").trim();
}

function normalizeIncomeSource(value) {
  const normalized = normalizeMerchant(value)
    .replace(/\b(payroll|paycheck|direct deposit|dir dep|ach credit|ach|ppd id|payment|deposit|credit|from)\b/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return normalized || normalizeMerchant(value);
}

function isEmployerLikeSource(normalizedSourceName) {
  if (!normalizedSourceName || normalizedSourceName.length < 4) return false;
  if (/\b(bank|transfer|mobile|deposit|refund|cash|atm|check)\b/.test(normalizedSourceName)) return false;
  return /\b(inc|llc|corp|co|company|stores?|market|hospital|school|university|city|county|state|publix|walmart|target|amazon|kroger|ups|fedex)\b/.test(normalizedSourceName);
}

function classifyIncomeCadence(sorted, gaps, signals) {
  if (!gaps.length) return signals.some((signal) => signal.gig) ? "irregular/gig" : "one-time";
  const average = gaps.reduce((sum, gap) => sum + gap, 0) / gaps.length;
  const spread = Math.max(...gaps) - Math.min(...gaps);
  if (average >= 6 && average <= 8 && spread <= 2) return "weekly";
  if (average >= 13 && average <= 16 && spread <= 3) return "biweekly";
  if (looksSemiMonthly(sorted, gaps)) return "semi-monthly";
  if (average >= 25 && average <= 35 && spread <= 7) return "monthly";
  if (signals.some((signal) => signal.gig) && sorted.length >= 3) return "irregular/gig";
  return sorted.length >= 3 ? "irregular/gig" : "unknown";
}

function looksSemiMonthly(sorted, gaps) {
  if (sorted.length < 3) return false;
  const average = gaps.reduce((sum, gap) => sum + gap, 0) / gaps.length;
  const spread = Math.max(...gaps) - Math.min(...gaps);
  const days = sorted.map((transaction) => Number(transaction.date.slice(8, 10)));
  const hasEarlyMonth = days.some((day) => day <= 7);
  const hasMidMonth = days.some((day) => day >= 13 && day <= 18);
  const hasEndMonth = days.some((day) => day >= 27);
  return average >= 13 && average <= 18 && spread <= 6 && ((hasEarlyMonth && hasMidMonth) || (hasMidMonth && hasEndMonth));
}

function incomeDefaultGapDays(cadence) {
  if (cadence === "weekly") return 7;
  if (cadence === "biweekly") return 14;
  if (cadence === "semi-monthly") return 15;
  if (cadence === "monthly") return 30;
  return 14;
}

function maxAmountVarianceCents(sorted) {
  const amounts = sorted.map((transaction) => Math.abs(transaction.amountCents));
  if (!amounts.length) return 0;
  return Math.max(...amounts) - Math.min(...amounts);
}

function hasStableIncomeAmount(sorted) {
  if (sorted.length < 2) return true;
  const amounts = sorted.map((transaction) => Math.abs(transaction.amountCents));
  const average = amounts.reduce((sum, amount) => sum + amount, 0) / amounts.length;
  const variance = Math.max(...amounts) - Math.min(...amounts);
  return variance <= 5000 || variance <= average * 0.12;
}

function incomeConfidence(count, cadence, stableAmount, signals) {
  const strongSource = signals.some((signal) => signal.payroll || signal.employerLike || signal.governmentBenefit);
  const transferOnly = signals.every((signal) => signal.transferApp);
  if (transferOnly && count < 3) return 0.18;
  if (count >= 3 && stableAmount && ["weekly", "biweekly", "semi-monthly", "monthly"].includes(cadence) && strongSource) return 0.92;
  if (count >= 3 && ["weekly", "biweekly", "semi-monthly", "monthly"].includes(cadence)) return 0.82;
  if (count >= 2 && stableAmount && ["weekly", "biweekly", "semi-monthly", "monthly"].includes(cadence) && strongSource) return 0.68;
  if (count >= 3 && cadence === "irregular/gig") return signals.some((signal) => signal.gig) ? 0.66 : 0.48;
  if (count === 1 && strongSource) return 0.25;
  return 0.2;
}

function shouldCreateIncomeCandidate(sorted, cadence, confidence, signals) {
  const transferOnly = signals.every((signal) => signal.transferApp);
  if (transferOnly && sorted.length < 3) return false;
  if (signals.some((signal) => signal.gig) && sorted.length >= 3) return true;
  if (confidence >= 0.45) return true;
  return sorted.length === 1 && confidence >= 0.25;
}

function incomeTypeFor(signals) {
  if (signals.some((signal) => signal.gig)) return "gig";
  if (signals.some((signal) => signal.governmentBenefit)) return "benefit";
  if (signals.every((signal) => signal.transferApp)) return "recurring_transfer";
  return "paycheck";
}

function incomeReasonDetected(count, cadence, stableAmount, signals) {
  const source = signals.find((signal) => signal.payroll || signal.employerLike || signal.gig || signal.governmentBenefit || signal.transferApp);
  if (count >= 3 && stableAmount && cadence !== "irregular/gig") return `Detected ${count} similar deposits on a ${cadence} cadence from ${source?.reason || "the same source"}.`;
  if (count >= 2 && cadence !== "unknown") return `Detected ${count} deposits from the same source with a likely ${cadence} pattern.`;
  if (cadence === "irregular/gig") return `Detected recurring variable deposits that look like ${source?.gig ? "gig income" : "income"}.`;
  return `Detected one income-like deposit from ${source?.reason || "the same source"}; needs review.`;
}

function isIncomeTransaction(transaction) {
  return incomeSignalFor(transaction).isPossibleIncome;
}

function likelyBill(candidate) {
  const text = `${candidate.merchantName} ${candidate.category || ""}`.toLowerCase();
  return ["rent", "utility", "electric", "water", "gas", "phone", "wireless", "insurance", "internet", "telecom"].some((term) => text.includes(term));
}

function estimateCurrentBalanceCents(userId, currentStore = store) {
  const recent = Object.values(currentStore.bankTransactions)
    .filter((transaction) => transaction.userId === userId)
    .sort((a, b) => a.date.localeCompare(b.date))
    .slice(-120);
  return 125000 - recent.reduce((sum, transaction) => sum + transaction.amountCents, 0);
}

function accountIdForPlaidAccount(userId, plaidAccountId, currentStore = store) {
  return Object.values(currentStore.connectedAccounts)
    .find((account) => account.userId === userId && (account.plaidAccountId === plaidAccountId || account.id === plaidAccountId))
    ?.id || plaidAccountId;
}

function accountNicknameForPlaidAccount(userId, plaidAccountId, currentStore = store) {
  const account = Object.values(currentStore.connectedAccounts)
    .find((item) => item.userId === userId && (item.plaidAccountId === plaidAccountId || item.id === plaidAccountId));
  return account?.name || "Connected account";
}

function accountNicknameForAccountId(accountId, currentStore = store) {
  return currentStore.connectedAccounts[accountId]?.name || "Connected account";
}

function markAccountsSynced(userId, institutionId) {
  for (const account of Object.values(store.connectedAccounts)) {
    if (account.userId === userId && account.institutionId === institutionId) {
      account.lastSyncedAt = nowIso();
    }
  }
}

function addSyncLog(userId, institutionId, status, startedAt, finishedAt, message) {
  const id = `sync-${crypto.randomUUID()}`;
  store.syncLogs[id] = { id, userId, institutionId, status, startedAt, finishedAt, message };
}

function audit(userId, action, metadata) {
  const id = `audit-${crypto.randomUUID()}`;
  store.auditLogs[id] = {
    id,
    userId,
    action,
    metadata: JSON.parse(JSON.stringify(metadata || {}, (_, value) => redact(value))),
    createdAt: nowIso()
  };
}

function encryptToken(token) {
  if (!token) return null;
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", tokenKey, iv);
  const ciphertext = Buffer.concat([cipher.update(token, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `${iv.toString("base64")}.${tag.toString("base64")}.${ciphertext.toString("base64")}`;
}

function decryptToken(value) {
  if (!value) throw new Error("missing_encrypted_token");
  const [ivText, tagText, cipherText] = value.split(".");
  const decipher = crypto.createDecipheriv("aes-256-gcm", tokenKey, Buffer.from(ivText, "base64"));
  decipher.setAuthTag(Buffer.from(tagText, "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(cipherText, "base64")),
    decipher.final()
  ]).toString("utf8");
}

function loadEncryptionKey() {
  const configured = process.env.TOKEN_ENCRYPTION_KEY || process.env.PLAID_TOKEN_ENCRYPTION_KEY || "";
  if (configured) {
    const decoded = Buffer.from(configured, "base64");
    if (decoded.length === 32) return decoded;
    const raw = Buffer.from(configured, "utf8");
    if (raw.length === 32) return raw;
    throw new Error("TOKEN_ENCRYPTION_KEY must be 32 bytes or base64-encoded 32 bytes.");
  }
  if ((process.env.PLAID_MOCK_MODE || "true").toLowerCase() !== "true") {
    throw new Error("TOKEN_ENCRYPTION_KEY is required outside mock mode.");
  }
  return crypto.createHash("sha256").update("renewal-radar-local-mock-key").digest();
}

function rateLimit(req, res) {
  const key = req.headers["x-forwarded-for"] || req.socket.remoteAddress || "local";
  const now = Date.now();
  const bucket = rateBuckets.get(key) || { startedAt: now, count: 0 };
  if (now - bucket.startedAt > config.rateLimitWindowMs) {
    bucket.startedAt = now;
    bucket.count = 0;
  }
  bucket.count += 1;
  rateBuckets.set(key, bucket);
  if (bucket.count > config.rateLimitMaxRequests) {
    sendJson(res, 429, { error: "rate_limited" });
    return false;
  }
  return true;
}

function isHttpsRequest(req) {
  return req.headers["x-forwarded-proto"] === "https" || req.socket.encrypted;
}

function getUserId(req) {
  const value = req.headers["x-renewal-user-id"] || req.headers["x-paycheck-pilot-user-id"] || req.headers["x-paycheckpilot-user-id"];
  return typeof value === "string" && value.trim() ? value.trim() : "local-user";
}

function getClientApp(req) {
  const value = req.headers["x-client-app"] || req.headers["x-smithware-app"];
  if (typeof value !== "string") return "renewal-radar";
  return value.trim().toLowerCase();
}

function getAndroidPackageName(req) {
  return getClientApp(req) === "paycheck-pilot"
    ? config.paycheckPilotAndroidPackageName
    : config.plaidAndroidPackageName;
}

function betaStatus(currentStore = store) {
  const activeUsers = countBetaUsers(currentStore);
  return {
    enabled: config.betaMode,
    maxUsers: config.betaMaxUsers,
    activeUsers,
    slotsAvailable: config.betaMode ? Math.max(0, config.betaMaxUsers - activeUsers) : null,
    allowlistEnabled: config.betaAllowedUserIds.length > 0
  };
}

function betaAccessProblem(userId, currentStore = store) {
  if (!config.betaMode) return null;
  const allowlist = config.betaAllowedUserIds;
  if (allowlist.length > 0 && !allowlist.includes(userId)) {
    return {
      error: "beta_not_allowed",
      message: "This Renewal Radar install is not on the Plaid beta allowlist."
    };
  }
  if (currentStore.users[userId]) return null;
  if (countBetaUsers(currentStore) >= config.betaMaxUsers) {
    return {
      error: "beta_full",
      message: "The Renewal Radar Plaid beta is full."
    };
  }
  return null;
}

function countBetaUsers(currentStore = store) {
  return Object.keys(currentStore.users)
    .filter((id) => id !== "local-user")
    .length;
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function centsFromBody(body, keys, fallback) {
  for (const key of keys) {
    if (body[key] !== undefined && body[key] !== null && body[key] !== "") return Number(body[key]);
  }
  return fallback;
}

function normalizeManualBills(value) {
  if (!Array.isArray(value)) return [];
  return value.map((bill, index) => ({
    id: bill.id || `manual-${index}`,
    name: bill.name || bill.merchantName || "Manual bill",
    amountCents: Number(bill.amountCents || bill.amount || 0),
    dueDate: bill.dueDate || bill.expectedDate || null,
    cadence: bill.cadence || bill.repeatType || "manual",
    category: bill.category || "Manual bill"
  })).filter((bill) => bill.amountCents > 0);
}

function normalizeStringList(value) {
  if (!value) return [];
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  return String(value).split(",").map((item) => item.trim()).filter(Boolean);
}

function sendJson(res, status, value) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  });
  res.end(JSON.stringify(value));
}

function ensureStorage() {
  fs.mkdirSync(dataDir, { recursive: true });
  if (!fs.existsSync(storePath)) saveStore(emptyStore());
}

function loadStore() {
  const loaded = JSON.parse(fs.readFileSync(storePath, "utf8"));
  ensureStoreShape(loaded);
  return loaded;
}

function saveStore(value) {
  fs.writeFileSync(storePath, JSON.stringify(value, null, 2));
}

function emptyStore() {
  return {
    users: {},
    connectedInstitutions: {},
    connectedAccounts: {},
    bankTransactions: {},
    recurringStreams: {},
    subscriptionCandidates: {},
    confirmedSubscriptions: {},
    renewalWatchOuts: {},
    incomeStreams: {},
    paycheckCandidates: {},
    confirmedPaychecks: {},
    payPeriods: {},
    billBeforePaydaySnapshots: {},
    safeToSpendSnapshots: {},
    paycheckWatchOuts: {},
    syncLogs: {},
    auditLogs: {}
  };
}

function ensureStoreShape(value) {
  const defaults = emptyStore();
  for (const key of Object.keys(defaults)) {
    value[key] = value[key] || {};
  }
  return value;
}

function splitEnv(value) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function nowIso() {
  return new Date().toISOString();
}

function normalizeMerchant(value) {
  return String(value || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function groupBy(items, keyFor) {
  const groups = new Map();
  for (const item of items) {
    const key = keyFor(item);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  return groups;
}

function stableHash(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex").slice(0, 16);
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function formatCents(amountCents) {
  const sign = amountCents < 0 ? "-" : "";
  const absolute = Math.abs(amountCents);
  return `${sign}$${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, "0")}`;
}

function dayDiff(first, second) {
  const start = Date.parse(`${first}T00:00:00Z`);
  const end = Date.parse(`${second}T00:00:00Z`);
  return Math.round((end - start) / 86_400_000);
}

function addDays(dateText, days) {
  const date = new Date(`${dateText}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function redact(value) {
  if (typeof value !== "string") return value;
  return value
    .replace(/access-[A-Za-z0-9_-]+/g, "[redacted-access-token]")
    .replace(/public-[A-Za-z0-9_-]+/g, "[redacted-public-token]")
    .replace(/secret[^,\s}]*/gi, "secret[redacted]");
}

export {
  addDays,
  billsBeforePayday,
  buildSafeToSpendSnapshot,
  dayDiff,
  detectCandidates,
  detectIncomeStreams,
  detectPaycheckPilotData,
  emptyStore,
  encryptToken,
  betaStatus,
  betaAccessProblem,
  currentPayPeriod,
  normalizeMerchant,
  seedMockData
};
