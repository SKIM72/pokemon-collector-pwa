const DB_NAME = "pokebinder";
const DB_VERSION = 1;
const COLLECTION_STORE = "collection";

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(COLLECTION_STORE)) {
        const store = db.createObjectStore(COLLECTION_STORE, { keyPath: "uid" });
        store.createIndex("name", "name", { unique: false });
        store.createIndex("addedAt", "addedAt", { unique: false });
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function requestToPromise(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function getCollection() {
  const db = await openDatabase();
  const transaction = db.transaction(COLLECTION_STORE, "readonly");
  const store = transaction.objectStore(COLLECTION_STORE);
  const cards = await requestToPromise(store.getAll());
  db.close();
  return cards;
}

export async function upsertCard(card) {
  const db = await openDatabase();

  return new Promise((resolve, reject) => {
    const transaction = db.transaction(COLLECTION_STORE, "readwrite");
    const store = transaction.objectStore(COLLECTION_STORE);
    const request = store.get(card.uid);
    let saved = null;

    request.onsuccess = () => {
      const existing = request.result;
      const now = new Date().toISOString();
      saved = existing
        ? {
            ...existing,
            ...card,
            quantity: Number(existing.quantity || 0) + Number(card.quantity || 1),
            updatedAt: now,
            priceHistory: mergePriceHistory(existing.priceHistory, card),
          }
        : {
            ...card,
            quantity: Number(card.quantity || 1),
            addedAt: now,
            updatedAt: now,
            priceHistory: mergePriceHistory([], card),
          };

      store.put(saved);
    };

    request.onerror = () => reject(request.error);
    transaction.oncomplete = () => {
      db.close();
      resolve(saved);
    };
    transaction.onerror = () => {
      db.close();
      reject(transaction.error);
    };
    transaction.onabort = () => {
      db.close();
      reject(transaction.error);
    };
  });
}

export async function patchCard(uid, patch) {
  const db = await openDatabase();

  return new Promise((resolve, reject) => {
    const transaction = db.transaction(COLLECTION_STORE, "readwrite");
    const store = transaction.objectStore(COLLECTION_STORE);
    const request = store.get(uid);
    let saved = null;

    request.onsuccess = () => {
      const existing = request.result;
      if (!existing) return;

      saved = {
        ...existing,
        ...patch,
        updatedAt: new Date().toISOString(),
      };

      if (
        Object.prototype.hasOwnProperty.call(patch, "marketPrice") ||
        Object.prototype.hasOwnProperty.call(patch, "currency") ||
        Object.prototype.hasOwnProperty.call(patch, "priceSource")
      ) {
        saved.priceHistory = mergePriceHistory(existing.priceHistory, saved);
      }

      store.put(saved);
    };

    request.onerror = () => reject(request.error);
    transaction.oncomplete = () => {
      db.close();
      resolve(saved);
    };
    transaction.onerror = () => {
      db.close();
      reject(transaction.error);
    };
    transaction.onabort = () => {
      db.close();
      reject(transaction.error);
    };
  });
}

export async function removeCard(uid) {
  const db = await openDatabase();
  const transaction = db.transaction(COLLECTION_STORE, "readwrite");
  const store = transaction.objectStore(COLLECTION_STORE);
  store.delete(uid);
  await transactionComplete(transaction);
  db.close();
}

export async function replaceCollection(cards) {
  const db = await openDatabase();
  const transaction = db.transaction(COLLECTION_STORE, "readwrite");
  const store = transaction.objectStore(COLLECTION_STORE);
  const clearRequest = store.clear();

  clearRequest.onsuccess = () => {
    cards.forEach((card) => store.put(card));
  };

  await transactionComplete(transaction);
  db.close();
}

function transactionComplete(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

function mergePriceHistory(existingHistory, card) {
  const history = Array.isArray(existingHistory) ? [...existingHistory] : [];
  const value = Number(card.marketPrice || 0);

  if (!value) return history;

  const point = {
    capturedAt: new Date().toISOString(),
    currency: card.currency || "JPY",
    price: value,
    source: card.priceSource || card.source,
  };

  const last = history.at(-1);
  if (
    last &&
    Number(last.price) === Number(point.price) &&
    last.currency === point.currency &&
    last.source === point.source
  ) {
    return history;
  }

  return [...history.slice(-89), point];
}
