import {
  getCollection,
  patchCard,
  removeCard,
  replaceCollection,
  upsertCard,
} from "./db.js";
import { hydrateCard, refreshCardPrice, searchCards } from "./api.js";
import { DISPLAY_CURRENCIES, convertMoney, formatMoney, loadCurrencyRates } from "./currency.js";
import { captureFrame, startCamera, stopCamera } from "./scanner.js";

const DISPLAY_CURRENCY_KEY = "pokebinder.displayCurrency";
const PORTFOLIO_HISTORY_KEY = "pokebinder.portfolioHistory";

const state = {
  view: "search",
  collection: [],
  results: [],
  providerId: "all",
  sort: "added",
  cameraActive: false,
  displayCurrency: localStorage.getItem(DISPLAY_CURRENCY_KEY) || "JPY",
  rates: { USD: 1, JPY: 157, KRW: 1380, EUR: 0.92 },
  ratesFallback: false,
  portfolioHistory: readPortfolioHistory(),
};

const elements = {};
let priceInputTimer = null;

document.addEventListener("DOMContentLoaded", init);

async function init() {
  bindElements();
  bindEvents();
  hydrateControls();
  await loadRates();
  await loadCollection();
  render();
  recordPortfolioSnapshot();
  registerServiceWorker();

  const initialQuery = new URLSearchParams(window.location.search).get("q");
  if (initialQuery) await runSearch(initialQuery);
}

function bindElements() {
  elements.tabs = [...document.querySelectorAll(".tab")];
  elements.views = [...document.querySelectorAll(".view")];
  elements.totalCards = document.querySelector("#totalCards");
  elements.uniqueCards = document.querySelector("#uniqueCards");
  elements.totalValue = document.querySelector("#totalValue");
  elements.totalValueChange = document.querySelector("#totalValueChange");
  elements.syncStatus = document.querySelector("#syncStatus");
  elements.displayCurrencySelect = document.querySelector("#displayCurrencySelect");
  elements.providerSelect = document.querySelector("#providerSelect");
  elements.searchForm = document.querySelector("#searchForm");
  elements.searchInput = document.querySelector("#searchInput");
  elements.searchResults = document.querySelector("#searchResults");
  elements.sortSelect = document.querySelector("#sortSelect");
  elements.refreshPricesButton = document.querySelector("#refreshPricesButton");
  elements.collectionList = document.querySelector("#collectionList");
  elements.cameraPreview = document.querySelector("#cameraPreview");
  elements.captureCanvas = document.querySelector("#captureCanvas");
  elements.capturePreview = document.querySelector("#capturePreview");
  elements.startCameraButton = document.querySelector("#startCameraButton");
  elements.captureButton = document.querySelector("#captureButton");
  elements.stopCameraButton = document.querySelector("#stopCameraButton");
  elements.scanTextInput = document.querySelector("#scanTextInput");
  elements.scanSearchButton = document.querySelector("#scanSearchButton");
  elements.scanStatus = document.querySelector("#scanStatus");
  elements.exportButton = document.querySelector("#exportButton");
  elements.importInput = document.querySelector("#importInput");
  elements.backupStatus = document.querySelector("#backupStatus");
}

function bindEvents() {
  elements.tabs.forEach((tab) => {
    tab.addEventListener("click", () => setView(tab.dataset.view));
  });

  elements.displayCurrencySelect.addEventListener("change", () => {
    state.displayCurrency = elements.displayCurrencySelect.value;
    localStorage.setItem(DISPLAY_CURRENCY_KEY, state.displayCurrency);
    render();
  });

  elements.providerSelect.addEventListener("change", () => {
    state.providerId = elements.providerSelect.value;
  });

  elements.searchForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    await runSearch(elements.searchInput.value);
  });

  elements.searchResults.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-add-result]");
    if (!button) return;
    await addSearchResult(Number(button.dataset.addResult));
  });

  elements.sortSelect.addEventListener("change", () => {
    state.sort = elements.sortSelect.value;
    renderCollection();
  });

  elements.refreshPricesButton.addEventListener("click", refreshAllPrices);

  elements.collectionList.addEventListener("click", async (event) => {
    const action = event.target.closest("[data-action]");
    if (!action) return;

    const uid = action.closest("[data-card-uid]")?.dataset.cardUid;
    if (!uid) return;

    if (action.dataset.action === "increment") await changeQuantity(uid, 1);
    if (action.dataset.action === "decrement") await changeQuantity(uid, -1);
    if (action.dataset.action === "remove") await deleteCard(uid);
  });

  elements.collectionList.addEventListener("change", async (event) => {
    const input = event.target.closest("[data-field]");
    if (!input) return;

    const uid = input.closest("[data-card-uid]")?.dataset.cardUid;
    if (!uid) return;

    await updateField(uid, input.dataset.field, input.value);
  });

  elements.collectionList.addEventListener("input", (event) => {
    const input = event.target.closest('[data-field="marketPrice"]');
    if (!input) return;

    const uid = input.closest("[data-card-uid]")?.dataset.cardUid;
    if (!uid) return;

    clearTimeout(priceInputTimer);
    priceInputTimer = setTimeout(async () => {
      await updateField(uid, "marketPrice", input.value);
    }, 450);
  });

  elements.startCameraButton.addEventListener("click", enableCamera);
  elements.captureButton.addEventListener("click", captureScanImage);
  elements.stopCameraButton.addEventListener("click", disableCamera);
  elements.scanSearchButton.addEventListener("click", async () => {
    const query = elements.scanTextInput.value.trim();
    if (!query) return;
    await runSearch(query);
    setView("search");
  });

  elements.exportButton.addEventListener("click", exportCollection);
  elements.importInput.addEventListener("change", importCollection);
}

function hydrateControls() {
  elements.displayCurrencySelect.value = DISPLAY_CURRENCIES.includes(state.displayCurrency)
    ? state.displayCurrency
    : "JPY";
  elements.providerSelect.value = state.providerId;
}

async function loadRates() {
  const payload = await loadCurrencyRates();
  state.rates = payload.rates;
  state.ratesFallback = Boolean(payload.fallback);
}

async function loadCollection() {
  state.collection = await getCollection();
}

function setView(view) {
  state.view = view;
  renderView();
}

async function runSearch(query) {
  const trimmed = query.trim();
  if (!trimmed) return;

  elements.searchInput.value = trimmed;
  elements.searchResults.innerHTML = renderBusy("전체 언어 검색 중");
  elements.syncStatus.textContent = "검색 중";

  try {
    state.results = await searchCards(state.providerId, trimmed);
    renderSearchResults();
    elements.syncStatus.textContent = `${state.results.length}건`;
  } catch (error) {
    state.results = [];
    elements.searchResults.innerHTML = renderEmpty("검색 실패", error.message);
    elements.syncStatus.textContent = "오류";
  }
}

async function addSearchResult(index) {
  const result = state.results[index];
  if (!result) return;

  elements.syncStatus.textContent = "가격 포함 저장 중";

  try {
    const detailed = await hydrateCard(result);
    await upsertCard(detailed);
    await loadCollection();
    recordPortfolioSnapshot();
    render();
    elements.syncStatus.textContent = detailed.marketPrice ? "가격 저장됨" : "저장됨";
  } catch (error) {
    elements.syncStatus.textContent = "저장 실패";
    elements.searchResults.insertAdjacentHTML("afterbegin", renderEmpty("저장 실패", error.message));
  }
}

async function refreshAllPrices() {
  if (!state.collection.length) {
    elements.syncStatus.textContent = "카드 없음";
    return;
  }

  elements.refreshPricesButton.disabled = true;
  elements.syncStatus.textContent = "가격 갱신 시작";

  try {
    await loadRates();

    for (const [index, card] of state.collection.entries()) {
      elements.syncStatus.textContent = `가격 ${index + 1}/${state.collection.length}`;
      const patch = await refreshCardPrice(card);
      if (Number(patch.marketPrice || 0) > 0) {
        await patchCard(card.uid, patch);
      }
    }

    await loadCollection();
    recordPortfolioSnapshot();
    render();
    elements.syncStatus.textContent = "가격 갱신 완료";
  } catch (error) {
    elements.syncStatus.textContent = "가격 갱신 실패";
    elements.backupStatus.textContent = error.message;
  } finally {
    elements.refreshPricesButton.disabled = false;
  }
}

async function changeQuantity(uid, delta) {
  const card = state.collection.find((item) => item.uid === uid);
  if (!card) return;

  const quantity = Math.max(0, Number(card.quantity || 0) + delta);
  if (quantity === 0) {
    await removeCard(uid);
  } else {
    await patchCard(uid, { quantity });
  }

  await loadCollection();
  recordPortfolioSnapshot();
  render();
}

async function updateField(uid, field, value) {
  const patch = { [field]: normalizeFieldValue(field, value) };
  if (field === "marketPrice" || field === "currency") {
    patch.priceSource = "manual";
    patch.updatedAtMarket = new Date().toISOString();
  }

  await patchCard(uid, patch);
  await loadCollection();
  recordPortfolioSnapshot();
  renderSummary();
  renderCollection();
  elements.syncStatus.textContent = "수정됨";
}

async function deleteCard(uid) {
  await removeCard(uid);
  await loadCollection();
  recordPortfolioSnapshot();
  render();
}

async function enableCamera() {
  try {
    await startCamera(elements.cameraPreview);
    state.cameraActive = true;
    elements.capturePreview.hidden = true;
    elements.captureButton.disabled = false;
    elements.stopCameraButton.disabled = false;
    elements.startCameraButton.disabled = true;
    elements.scanStatus.textContent = "카메라 활성";
  } catch (error) {
    elements.scanStatus.textContent = error.message;
  }
}

function captureScanImage() {
  const image = captureFrame(elements.cameraPreview, elements.captureCanvas);
  elements.capturePreview.style.background = `center / cover no-repeat url(${image})`;
  elements.capturePreview.hidden = false;
  elements.scanStatus.textContent = "촬영 완료";
}

function disableCamera() {
  stopCamera(elements.cameraPreview);
  state.cameraActive = false;
  elements.captureButton.disabled = true;
  elements.stopCameraButton.disabled = true;
  elements.startCameraButton.disabled = false;
  elements.scanStatus.textContent = "대기 중";
}

function exportCollection() {
  const payload = {
    exportedAt: new Date().toISOString(),
    app: "PokeBinder",
    version: 2,
    displayCurrency: state.displayCurrency,
    portfolioHistory: state.portfolioHistory,
    cards: state.collection,
  };

  const blob = new Blob([JSON.stringify(payload, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `pokebinder-${new Date().toISOString().slice(0, 10)}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
  elements.backupStatus.textContent = `내보내기 완료\n${payload.cards.length} records`;
}

async function importCollection(event) {
  const file = event.target.files?.[0];
  if (!file) return;

  try {
    const text = await file.text();
    const payload = JSON.parse(text);
    const cards = Array.isArray(payload) ? payload : payload.cards;
    if (!Array.isArray(cards)) throw new Error("cards 배열이 없습니다.");

    if (Array.isArray(payload.portfolioHistory)) {
      state.portfolioHistory = payload.portfolioHistory;
      writePortfolioHistory();
    }

    await replaceCollection(cards);
    await loadCollection();
    recordPortfolioSnapshot();
    render();
    elements.backupStatus.textContent = `가져오기 완료\n${cards.length} records`;
  } catch (error) {
    elements.backupStatus.textContent = `가져오기 실패\n${error.message}`;
  } finally {
    event.target.value = "";
  }
}

function render() {
  renderView();
  renderSummary();
  renderSearchResults();
  renderCollection();
}

function renderView() {
  elements.tabs.forEach((tab) => {
    tab.classList.toggle("is-active", tab.dataset.view === state.view);
  });

  elements.views.forEach((view) => {
    view.classList.toggle("is-active", view.id === `view-${state.view}`);
  });
}

function renderSummary() {
  const totalCards = state.collection.reduce((sum, card) => sum + Number(card.quantity || 0), 0);
  const totalValue = calculatePortfolioValue(state.collection, state.displayCurrency);
  const totalChange = calculatePortfolioChange(totalValue);

  elements.totalCards.textContent = totalCards.toLocaleString("ko-KR");
  elements.uniqueCards.textContent = state.collection.length.toLocaleString("ko-KR");
  elements.totalValue.textContent = totalValue ? formatMoney(totalValue, state.displayCurrency) : "-";
  elements.totalValueChange.className = `metric-change ${totalChange?.direction || ""}`;
  elements.totalValueChange.textContent = totalChange
    ? `${totalChange.direction === "up" ? "상승" : totalChange.direction === "down" ? "하락" : "변동 없음"} ${formatMoney(Math.abs(totalChange.diff), state.displayCurrency)} (${formatPercent(totalChange.percent)})`
    : "전일 데이터 없음";
}

function renderSearchResults() {
  if (!state.results.length) {
    elements.searchResults.innerHTML = renderEmpty("검색 결과 없음", "리자몽, リザードン, Charizard 모두 검색 가능");
    return;
  }

  elements.searchResults.innerHTML = state.results
    .map(
      (card, index) => `
        <article class="card-result">
          <div class="card-image">
            ${renderImage(card.image, card.name)}
          </div>
          <div class="card-body">
            <h3 class="card-title">${escapeHtml(card.name)}</h3>
            <div class="card-meta">
              <span>${escapeHtml(compactMeta(card))}</span>
              <span>${escapeHtml(card.providerLabel || card.source)}</span>
              ${renderLocalizedNames(card)}
              ${card.marketPrice ? `<span>${escapeHtml(formatDisplayPrice(card.marketPrice, card.currency))}</span>` : ""}
            </div>
            <button type="button" data-add-result="${index}">추가</button>
          </div>
        </article>
      `,
    )
    .join("");
}

function renderCollection() {
  if (!state.collection.length) {
    elements.collectionList.innerHTML = renderEmpty("컬렉션 비어 있음", "검색에서 카드 추가");
    return;
  }

  const sorted = [...state.collection].sort(sortCards);
  elements.collectionList.innerHTML = sorted.map(renderCollectionCard).join("");
}

function renderCollectionCard(card) {
  const unitPrice = Number(card.marketPrice || 0);
  const quantity = Number(card.quantity || 0);
  const sourceSubtotal = unitPrice * quantity;
  const displaySubtotal = convertMoney(sourceSubtotal, card.currency, state.displayCurrency, state.rates);
  const unitDisplay = convertMoney(unitPrice, card.currency, state.displayCurrency, state.rates);
  const change = calculateCardChange(card);

  return `
    <article class="collection-card" data-card-uid="${escapeAttribute(card.uid)}">
      <div class="thumb">
        ${renderImage(card.image || card.imageHigh, card.name)}
      </div>
      <div class="collection-main">
        <div>
          <h3>${escapeHtml(card.name)}</h3>
          <div class="card-meta">
            <span>${escapeHtml(compactMeta(card))}</span>
            <span>${escapeHtml(card.rarity || card.providerLabel || card.source)}</span>
            ${renderLocalizedNames(card)}
          </div>
          <div class="collection-fields">
            <label class="field">
              <span>상태</span>
              <select data-field="condition">
                ${renderOptions(["NM", "LP", "MP", "HP", "DMG"], card.condition || "NM")}
              </select>
            </label>
            <label class="field">
              <span>타입</span>
              <select data-field="finish">
                ${renderOptions(["normal", "holo", "reverse", "masterball", "pokeball", "unknown"], card.finish || "normal")}
              </select>
            </label>
            <label class="field">
              <span>원가격 통화</span>
              <select data-field="currency">
                ${renderOptions(["JPY", "KRW", "USD", "EUR"], card.currency || "JPY")}
              </select>
            </label>
            <label class="field">
              <span>원가격</span>
              <input data-field="marketPrice" type="number" inputmode="decimal" min="0" step="0.01" value="${Number(card.marketPrice || 0)}" />
            </label>
          </div>
          <div class="price-chart">
            ${renderSparkline(card)}
            ${renderCardChange(change)}
          </div>
        </div>
        <div class="value-block">
          <strong>${sourceSubtotal ? formatMoney(displaySubtotal, state.displayCurrency) : "가격 미입력"}</strong>
          <small>${unitPrice ? `${formatMoney(unitDisplay, state.displayCurrency)} / 장` : "실시간 가격 없음"}</small>
          <small>${escapeHtml(priceSourceLabel(card))}</small>
          <div class="quantity-control" aria-label="수량 조절">
            <button type="button" class="secondary" data-action="decrement">-</button>
            <span>${quantity}</span>
            <button type="button" class="secondary" data-action="increment">+</button>
          </div>
          <button type="button" class="danger" data-action="remove">삭제</button>
        </div>
      </div>
    </article>
  `;
}

function sortCards(a, b) {
  if (state.sort === "name") return a.name.localeCompare(b.name, "ko");
  if (state.sort === "quantity") return Number(b.quantity || 0) - Number(a.quantity || 0);
  if (state.sort === "value") {
    return cardDisplayValue(b) - cardDisplayValue(a);
  }
  return new Date(b.addedAt || 0) - new Date(a.addedAt || 0);
}

function calculatePortfolioValue(cards, currency) {
  return cards.reduce((sum, card) => sum + cardDisplayValue(card, currency), 0);
}

function calculatePortfolioBaseUsd(cards = state.collection) {
  return cards.reduce((sum, card) => {
    const value = Number(card.marketPrice || 0) * Number(card.quantity || 0);
    return sum + convertMoney(value, card.currency, "USD", state.rates);
  }, 0);
}

function calculatePortfolioChange(currentDisplayValue) {
  const currentBaseUsd = calculatePortfolioBaseUsd();
  const previous = findPreviousPortfolioPoint();
  if (!previous || !previous.baseUsd) return null;

  const diffBase = currentBaseUsd - Number(previous.baseUsd || 0);
  const diff = convertMoney(diffBase, "USD", state.displayCurrency, state.rates);
  const percent = previous.baseUsd ? (diffBase / previous.baseUsd) * 100 : 0;

  if (!currentDisplayValue && !diff) return null;

  return {
    diff,
    percent,
    direction: diff > 0 ? "up" : diff < 0 ? "down" : "flat",
  };
}

function calculateCardChange(card) {
  const points = getChartPoints(card);
  if (points.length < 2) return null;

  const current = points.at(-1);
  const previous = points.at(-2);
  const diff = current.value - previous.value;
  const percent = previous.value ? (diff / previous.value) * 100 : 0;

  return {
    diff,
    percent,
    direction: diff > 0 ? "up" : diff < 0 ? "down" : "flat",
  };
}

function cardDisplayValue(card, currency = state.displayCurrency) {
  const sourceValue = Number(card.marketPrice || 0) * Number(card.quantity || 0);
  return convertMoney(sourceValue, card.currency, currency, state.rates);
}

function recordPortfolioSnapshot() {
  const baseUsd = calculatePortfolioBaseUsd();
  if (!baseUsd) return;

  const now = new Date();
  const last = state.portfolioHistory.at(-1);
  if (
    last &&
    sameLocalDay(new Date(last.capturedAt), now) &&
    Math.abs(Number(last.baseUsd || 0) - baseUsd) < 0.01
  ) {
    return;
  }

  state.portfolioHistory = [
    ...state.portfolioHistory.slice(-179),
    {
      capturedAt: now.toISOString(),
      baseUsd,
    },
  ];
  writePortfolioHistory();
}

function findPreviousPortfolioPoint() {
  const startToday = new Date();
  startToday.setHours(0, 0, 0, 0);

  return [...state.portfolioHistory]
    .reverse()
    .find((point) => new Date(point.capturedAt).getTime() < startToday.getTime());
}

function getChartPoints(card) {
  const history = Array.isArray(card.priceHistory) ? card.priceHistory : [];
  const points = history
    .map((point) => ({
      capturedAt: point.capturedAt,
      value: convertMoney(point.price, point.currency || card.currency, state.displayCurrency, state.rates),
    }))
    .filter((point) => Number(point.value || 0) > 0)
    .sort((a, b) => new Date(a.capturedAt) - new Date(b.capturedAt));

  if (!points.length && Number(card.marketPrice || 0) > 0) {
    points.push({
      capturedAt: card.updatedAtMarket || card.updatedAt || new Date().toISOString(),
      value: convertMoney(card.marketPrice, card.currency, state.displayCurrency, state.rates),
    });
  }

  return points;
}

function renderSparkline(card) {
  const points = getChartPoints(card);
  const width = 220;
  const height = 64;

  if (!points.length) {
    return `<div class="sparkline-empty">가격 기록 없음</div>`;
  }

  const values = points.map((point) => point.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const spread = max - min || 1;
  const coordinates = points.map((point, index) => {
    const x = points.length === 1 ? width / 2 : (index / (points.length - 1)) * width;
    const y = height - ((point.value - min) / spread) * (height - 12) - 6;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  return `
    <svg class="sparkline" viewBox="0 0 ${width} ${height}" role="img" aria-label="가격 차트">
      <line x1="0" y1="${height - 6}" x2="${width}" y2="${height - 6}" />
      <polyline points="${coordinates.join(" ")}" />
    </svg>
  `;
}

function renderCardChange(change) {
  if (!change) return `<span class="change-pill flat">변동 데이터 없음</span>`;
  return `
    <span class="change-pill ${change.direction}">
      ${change.direction === "up" ? "상승" : change.direction === "down" ? "하락" : "변동 없음"}
      ${formatMoney(Math.abs(change.diff), state.displayCurrency)}
      ${formatPercent(change.percent)}
    </span>
  `;
}

function normalizeFieldValue(field, value) {
  if (field === "marketPrice") return Number(value || 0);
  return value;
}

function compactMeta(card) {
  return [card.setName, card.number ? `#${card.number}` : "", card.setId].filter(Boolean).join(" · ");
}

function renderLocalizedNames(card) {
  const names = card.localizedNames || {};
  const display = [
    names.ja && `JA ${names.ja}`,
    names.ko && `KO ${names.ko}`,
    names.en && `EN ${names.en}`,
  ]
    .filter(Boolean)
    .join(" · ");

  return display ? `<span>${escapeHtml(display)}</span>` : "";
}

function priceSourceLabel(card) {
  if (!Number(card.marketPrice || 0)) return "가격 미입력";
  const source = card.priceSource === "tcgplayer" ? "TCGplayer" : card.priceSource === "cardmarket" ? "Cardmarket" : "Manual";
  const updated = card.updatedAtMarket ? ` · ${card.updatedAtMarket}` : "";
  return `${source}${updated}`;
}

function formatDisplayPrice(value, currency) {
  const converted = convertMoney(value, currency, state.displayCurrency, state.rates);
  return formatMoney(converted, state.displayCurrency);
}

function renderImage(src, alt) {
  if (!src) return `<span class="status-pill">No image</span>`;
  return `<img src="${escapeAttribute(src)}" alt="${escapeAttribute(alt)}" loading="lazy" />`;
}

function renderOptions(options, selected) {
  return options
    .map(
      (option) =>
        `<option value="${escapeAttribute(option)}" ${option === selected ? "selected" : ""}>${escapeHtml(option)}</option>`,
    )
    .join("");
}

function renderEmpty(title, subtitle) {
  return `
    <div class="empty-state">
      <strong>${escapeHtml(title)}</strong>
      <span>${escapeHtml(subtitle)}</span>
    </div>
  `;
}

function renderBusy(title) {
  return `
    <div class="empty-state">
      <strong>${escapeHtml(title)}</strong>
      <span>TCGdex + Pokemon TCG API</span>
    </div>
  `;
}

function formatPercent(value) {
  const sign = value > 0 ? "+" : "";
  return `${sign}${Number(value || 0).toFixed(2)}%`;
}

function readPortfolioHistory() {
  try {
    return JSON.parse(localStorage.getItem(PORTFOLIO_HISTORY_KEY) || "[]");
  } catch {
    return [];
  }
}

function writePortfolioHistory() {
  localStorage.setItem(PORTFOLIO_HISTORY_KEY, JSON.stringify(state.portfolioHistory));
}

function sameLocalDay(a, b) {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function escapeAttribute(value) {
  return escapeHtml(value).replace(/`/g, "&#096;");
}

function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;

  navigator.serviceWorker.register("./sw.js").catch(() => {
    elements.syncStatus.textContent = "PWA 보류";
  });
}
