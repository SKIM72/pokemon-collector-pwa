import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ArrowDown,
  ArrowUp,
  Camera,
  Check,
  ChevronRight,
  Cloud,
  Download,
  Eye,
  EyeOff,
  Grid,
  Heart,
  Info,
  List,
  LogOut,
  Moon,
  RefreshCw,
  Search,
  Settings,
  Shield,
  Sparkles,
  Sun,
  Upload,
  User,
  X,
} from "lucide-react";
import { searchCards, hydrateCard, refreshCardPrice } from "./api.js";
import {
  addCloudCard,
  deleteCloudCard,
  insertPortfolioSnapshot,
  loadCloudCollection,
  loadPortfolioSnapshots,
  loadUserSettings,
  replaceCloudCollection,
  saveUserSettings,
  updateCloudCard,
} from "./cloudDb.js";
import { DISPLAY_CURRENCIES, convertMoney, formatMoney, loadCurrencyRates } from "./currency.js";
import { captureFrame, startCamera, stopCamera } from "./scanner.js";
import { hasSupabaseConfig, supabase } from "./supabaseClient.js";
import "./styles.css";

const THEME_KEY = "pokebinder.theme";
const LAYOUT_KEY = "pokebinder.collectionLayout";
const VIEW_ITEMS = [
  { id: "search", label: "검색", icon: Search },
  { id: "collection", label: "컬렉션", icon: Sparkles },
  { id: "favorites", label: "즐겨찾기", icon: Heart },
  { id: "scan", label: "스캔", icon: Camera },
  { id: "settings", label: "설정", icon: Settings },
];

function App() {
  const [session, setSession] = useState(null);
  const [sessionLoading, setSessionLoading] = useState(true);
  const [passwordRecovery, setPasswordRecovery] = useState(false);

  useEffect(() => {
    if (!hasSupabaseConfig) {
      setSessionLoading(false);
      return undefined;
    }

    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setSessionLoading(false);
    });

    const { data } = supabase.auth.onAuthStateChange((event, nextSession) => {
      if (event === "PASSWORD_RECOVERY") setPasswordRecovery(true);
      setSession(nextSession);
      setSessionLoading(false);
    });

    return () => data.subscription.unsubscribe();
  }, []);

  if (!hasSupabaseConfig) return <SetupMissing />;
  if (sessionLoading) return <Splash />;
  if (!session) return <AuthScreen />;
  if (passwordRecovery) return <PasswordRecoveryScreen onDone={() => setPasswordRecovery(false)} />;

  return <Dashboard session={session} />;
}

function Dashboard({ session }) {
  const user = session.user;
  const [activeView, setActiveView] = useState("search");
  const [collection, setCollection] = useState([]);
  const [portfolioSnapshots, setPortfolioSnapshots] = useState([]);
  const [results, setResults] = useState([]);
  const [query, setQuery] = useState("");
  const [providerId, setProviderId] = useState("all");
  const [searching, setSearching] = useState(false);
  const [refreshingPrices, setRefreshingPrices] = useState(false);
  const [sort, setSort] = useState("registered");
  const [collectionLayout, setCollectionLayout] = useState(localStorage.getItem(LAYOUT_KEY) || "list");
  const [selectedCard, setSelectedCard] = useState(null);
  const [toast, setToast] = useState("");
  const [status, setStatus] = useState("Supabase 동기화");
  const [loading, setLoading] = useState(true);
  const [displayCurrency, setDisplayCurrency] = useState("JPY");
  const [rates, setRates] = useState({ USD: 1, JPY: 157, KRW: 1380, EUR: 0.92 });
  const [themeMode, setThemeMode] = useThemeMode();
  const [scanText, setScanText] = useState("");
  const [scanLanguage, setScanLanguage] = useState("ja");
  const [scanOverlayOpen, setScanOverlayOpen] = useState(false);
  const [scanCandidates, setScanCandidates] = useState([]);
  const [scanBusy, setScanBusy] = useState(false);
  const [capturedImage, setCapturedImage] = useState("");
  const [cameraActive, setCameraActive] = useState(false);
  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const captureRef = useRef(null);
  const toastTimerRef = useRef(null);

  useEffect(() => {
    loadInitialData();
  }, []);

  useEffect(() => {
    localStorage.setItem(LAYOUT_KEY, collectionLayout);
  }, [collectionLayout]);

  async function loadInitialData() {
    setLoading(true);
    try {
      const [ratePayload, settings, cards, snapshots] = await Promise.all([
        loadCurrencyRates(),
        loadUserSettings(user.id),
        loadCloudCollection(user.id),
        loadPortfolioSnapshots(user.id),
      ]);

      setRates(ratePayload.rates);
      setDisplayCurrency(settings?.display_currency || "JPY");
      setCollection(cards);
      setPortfolioSnapshots(snapshots);
      setStatus("동기화 완료");
    } catch (error) {
      setStatus(error.message);
    } finally {
      setLoading(false);
    }
  }

  async function reloadCollection() {
    const [cards, snapshots] = await Promise.all([loadCloudCollection(user.id), loadPortfolioSnapshots(user.id)]);
    setCollection(cards);
    setPortfolioSnapshots(snapshots);
    return cards;
  }

  async function handleSearch(event) {
    event?.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;

    setStatus("전체 언어 검색 중");
    setSearching(true);
    setResults([]);
    try {
      const cards = await searchCards(providerId, trimmed);
      setResults(cards);
      setStatus(`${cards.length}건`);
    } catch (error) {
      setStatus("검색 실패");
      setResults([]);
    } finally {
      setSearching(false);
    }
  }

  function showToast(message) {
    setToast(message);
    clearTimeout(toastTimerRef.current);
    toastTimerRef.current = setTimeout(() => setToast(""), 2600);
  }

  async function handleAddCard(card, quantity = 1) {
    const addQuantity = Math.max(1, Number(quantity || 1));
    setStatus("가격 포함 저장 중");
    try {
      const detailed = { ...(await hydrateCard(card)), quantity: addQuantity };
      await addCloudCard(user.id, detailed, collection);
      const cards = await reloadCollection();
      await recordPortfolioSnapshot(cards);
      setStatus(`${addQuantity}장 추가됨`);
      showToast(`${detailed.name} ${addQuantity}장 추가됨`);
    } catch (error) {
      setStatus(error.message);
      showToast(error.message);
    }
  }

  async function handleRefreshPrices() {
    if (!collection.length) {
      setStatus("카드 없음");
      return;
    }

    setStatus("가격 갱신 시작");
    setRefreshingPrices(true);
    try {
      const ratePayload = await loadCurrencyRates();
      setRates(ratePayload.rates);

      for (const [index, card] of collection.entries()) {
        setStatus(`가격 ${index + 1}/${collection.length}`);
        const patch = await refreshCardPrice(card);
        if (Number(patch.marketPrice || 0) > 0) {
          await updateCloudCard(user.id, card.uid, {
            ...patch,
            lastPriceSyncAt: new Date().toISOString(),
          });
        }
      }

      const cards = await reloadCollection();
      await recordPortfolioSnapshot(cards);
      setStatus("가격 갱신 완료");
    } catch (error) {
      setStatus(error.message);
    } finally {
      setRefreshingPrices(false);
    }
  }

  async function handleCardPatch(card, patch) {
    const nextPatch = { ...patch };
    if (Object.prototype.hasOwnProperty.call(patch, "marketPrice") || Object.prototype.hasOwnProperty.call(patch, "currency")) {
      nextPatch.priceSource = "manual";
      nextPatch.updatedAtMarket = new Date().toISOString();
    }

    await updateCloudCard(user.id, card.uid, nextPatch);
    const cards = await reloadCollection();
    setSelectedCard((current) => (current?.uid === card.uid ? cards.find((item) => item.uid === card.uid) || current : current));
    await recordPortfolioSnapshot(cards);
    setStatus("수정됨");
  }

  async function handleQuantity(card, delta) {
    const quantity = Math.max(0, Number(card.quantity || 0) + delta);
    if (quantity === 0) {
      await deleteCloudCard(user.id, card.uid);
      setSelectedCard((current) => (current?.uid === card.uid ? null : current));
    } else {
      await handleCardPatch(card, { quantity });
      return;
    }

    const cards = await reloadCollection();
    await recordPortfolioSnapshot(cards);
    setStatus("수정됨");
  }

  async function handleDisplayCurrency(nextCurrency) {
    setDisplayCurrency(nextCurrency);
    try {
      await saveUserSettings(user.id, { displayCurrency: nextCurrency });
      setStatus("설정 저장됨");
    } catch (error) {
      setStatus(error.message);
    }
  }

  async function recordPortfolioSnapshot(cards = collection) {
    const baseUsd = calculatePortfolioBaseUsd(cards, rates);
    if (!baseUsd) return;

    const totalValue = calculatePortfolioValue(cards, displayCurrency, rates);
    const last = portfolioSnapshots.at(-1);
    if (last && sameLocalDay(new Date(last.capturedAt), new Date()) && Math.abs(last.baseUsd - baseUsd) < 0.01) {
      return;
    }

    const snapshot = {
      capturedAt: new Date().toISOString(),
      baseUsd,
      displayCurrency,
      totalValue,
    };

    await insertPortfolioSnapshot(user.id, snapshot);
    setPortfolioSnapshots((prev) => [...prev.slice(-179), snapshot]);
  }

  async function handleExport() {
    const payload = {
      exportedAt: new Date().toISOString(),
      app: "PokeBinder",
      version: 3,
      displayCurrency,
      cards: collection,
      portfolioSnapshots,
    };

    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `pokebinder-${new Date().toISOString().slice(0, 10)}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
    setStatus("백업 완료");
  }

  async function handleImport(event) {
    const file = event.target.files?.[0];
    if (!file) return;

    try {
      const payload = JSON.parse(await file.text());
      const cards = Array.isArray(payload) ? payload : payload.cards;
      if (!Array.isArray(cards)) throw new Error("cards 배열이 없습니다.");
      await replaceCloudCollection(user.id, cards);
      const nextCards = await reloadCollection();
      await recordPortfolioSnapshot(nextCards);
      setStatus(`${cards.length}장 가져오기 완료`);
    } catch (error) {
      setStatus(error.message);
    } finally {
      event.target.value = "";
    }
  }

  async function handleStartCamera() {
    try {
      await startCamera(videoRef.current);
      setCameraActive(true);
      setStatus("카메라 활성");
      if (captureRef.current) captureRef.current.hidden = true;
    } catch (error) {
      setStatus(error.message);
    }
  }

  function handleCapture() {
    const image = captureFrame(videoRef.current, canvasRef.current);
    setCapturedImage(image);
    if (captureRef.current) {
      captureRef.current.style.background = `center / cover no-repeat url(${image})`;
      captureRef.current.hidden = false;
    }
    setStatus("촬영 완료");
  }

  function handleStopCamera() {
    stopCamera(videoRef.current);
    setCameraActive(false);
    setStatus("대기 중");
  }

  function handleOpenScanner() {
    setCapturedImage("");
    setScanOverlayOpen(true);
    setStatus("스캔 창 준비");
  }

  function handleCloseScanner() {
    handleStopCamera();
    setCapturedImage("");
    setScanOverlayOpen(false);
  }

  function handleRetake() {
    setCapturedImage("");
    if (captureRef.current) captureRef.current.hidden = true;
    setStatus("재촬영 준비");
  }

  async function handleScanSearch() {
    const trimmed = scanText.trim();
    if (!trimmed) {
      const message = capturedImage ? "이미지 매칭 모델 연결 대기" : "카드명 또는 번호를 입력해 주세요";
      setStatus(message);
      showToast(message);
      return;
    }

    setScanBusy(true);
    setQuery(trimmed);
    setStatus("스캔 후보 검색");
    try {
      const source = scanLanguage === "all" ? "all" : `tcgdex-${scanLanguage}`;
      const cards = await searchCards(source, trimmed);
      setScanCandidates(cards);
      setResults(cards);
      setStatus(`${cards.length}개 후보`);
    } catch (error) {
      setScanCandidates([]);
      setStatus(error.message || "후보 검색 실패");
    } finally {
      setScanBusy(false);
    }
  }

  const summary = useMemo(
    () => makeSummary(collection, displayCurrency, rates, portfolioSnapshots),
    [collection, displayCurrency, rates, portfolioSnapshots],
  );

  const sortedCollection = useMemo(() => sortCollection(collection, sort, displayCurrency, rates), [
    collection,
    sort,
    displayCurrency,
    rates,
  ]);
  const favoriteCollection = useMemo(() => sortedCollection.filter((card) => card.isFavorite), [sortedCollection]);

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">P</span>
          <div>
            <strong>PokeBinder</strong>
            <small>Private TCG vault</small>
          </div>
        </div>

        <nav className="nav-list" aria-label="주요 메뉴">
          {VIEW_ITEMS.map((item) => {
            const Icon = item.icon;
            return (
              <button
                className={activeView === item.id ? "nav-item active" : "nav-item"}
                key={item.id}
                type="button"
                aria-label={item.label}
                onClick={() => setActiveView(item.id)}
              >
                <Icon size={18} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>

        <div className="account-mini">
          <div className="avatar">{initialOf(user.email)}</div>
          <div>
            <strong>{user.user_metadata?.username || "Collector"}</strong>
            <small>{user.email}</small>
          </div>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div>
            <h1>{pageTitle(activeView)}</h1>
          </div>
          <div className="top-actions">
            <label className="select-pill">
              <span>통화</span>
              <select value={displayCurrency} onChange={(event) => handleDisplayCurrency(event.target.value)}>
                {DISPLAY_CURRENCIES.map((currency) => (
                  <option key={currency} value={currency}>
                    {currency}
                  </option>
                ))}
              </select>
            </label>
            <StatusPill text={status} />
          </div>
        </header>

        <section className="summary-grid" aria-label="컬렉션 요약">
          <Metric label="보유 카드" value={summary.totalCards.toLocaleString("ko-KR")} />
          <Metric label="종류" value={collection.length.toLocaleString("ko-KR")} />
          <Metric label="총 가치" value={summary.totalValueText} change={summary.changeText} direction={summary.direction} wide />
        </section>

        {loading ? (
          <Splash compact />
        ) : (
          <main className="main-surface">
            {activeView === "search" && (
              <SearchView
                query={query}
                setQuery={setQuery}
                providerId={providerId}
                setProviderId={setProviderId}
                results={results}
                displayCurrency={displayCurrency}
                rates={rates}
                searching={searching}
                onSearch={handleSearch}
                onAdd={handleAddCard}
              />
            )}

            {activeView === "collection" && (
              <CollectionView
                title="내 컬렉션"
                subtitle={`${summary.totalCards.toLocaleString("ko-KR")}장 · ${collection.length.toLocaleString("ko-KR")}종류`}
                cards={sortedCollection}
                sort={sort}
                setSort={setSort}
                layout={collectionLayout}
                setLayout={setCollectionLayout}
                displayCurrency={displayCurrency}
                rates={rates}
                refreshing={refreshingPrices}
                onRefreshPrices={handleRefreshPrices}
                onPatch={handleCardPatch}
                onQuantity={handleQuantity}
                onOpenCard={setSelectedCard}
              />
            )}

            {activeView === "favorites" && (
              <CollectionView
                title="즐겨찾기"
                subtitle={`${favoriteCollection.length.toLocaleString("ko-KR")}종류`}
                cards={favoriteCollection}
                sort={sort}
                setSort={setSort}
                layout={collectionLayout}
                setLayout={setCollectionLayout}
                displayCurrency={displayCurrency}
                rates={rates}
                refreshing={refreshingPrices}
                onRefreshPrices={handleRefreshPrices}
                onPatch={handleCardPatch}
                onQuantity={handleQuantity}
                onOpenCard={setSelectedCard}
                emptyTitle="즐겨찾기 없음"
                emptyText="카드의 하트 버튼을 눌러 자주 보는 카드를 모아보세요."
              />
            )}

            {activeView === "scan" && (
              <ScanView
                scanText={scanText}
                setScanText={setScanText}
                scanLanguage={scanLanguage}
                setScanLanguage={setScanLanguage}
                cameraActive={cameraActive}
                overlayOpen={scanOverlayOpen}
                candidates={scanCandidates}
                busy={scanBusy}
                capturedImage={capturedImage}
                displayCurrency={displayCurrency}
                rates={rates}
                videoRef={videoRef}
                canvasRef={canvasRef}
                captureRef={captureRef}
                onOpen={handleOpenScanner}
                onClose={handleCloseScanner}
                onStart={handleStartCamera}
                onCapture={handleCapture}
                onRetake={handleRetake}
                onStop={handleStopCamera}
                onSearch={handleScanSearch}
                onAdd={handleAddCard}
              />
            )}

            {activeView === "settings" && (
              <SettingsView
                user={user}
                themeMode={themeMode}
                setThemeMode={setThemeMode}
                displayCurrency={displayCurrency}
                setDisplayCurrency={handleDisplayCurrency}
                collection={collection}
                onExport={handleExport}
                onImport={handleImport}
                onStatus={setStatus}
              />
            )}
          </main>
        )}
      </div>

      {selectedCard && (
        <CardDetailModal
          card={selectedCard}
          displayCurrency={displayCurrency}
          rates={rates}
          onClose={() => setSelectedCard(null)}
          onPatch={(patch) => handleCardPatch(selectedCard, patch)}
          onQuantity={(delta) => handleQuantity(selectedCard, delta)}
        />
      )}

      {toast && <Toast text={toast} />}
    </div>
  );
}

function AuthScreen() {
  const [mode, setMode] = useState("login");
  const [email, setEmail] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setBusy(true);
    setMessage("");

    try {
      if (mode === "signup") {
        if (!username.trim()) throw new Error("아이디를 입력해 주세요.");
        if (password !== confirm) throw new Error("비밀번호가 서로 다릅니다.");
        const { error } = await supabase.auth.signUp({
          email,
          password,
          options: { data: { username: username.trim() } },
        });
        if (error) throw error;
        setMessage("회원가입 완료. 이메일 확인이 켜져 있으면 메일함을 확인해 주세요.");
      }

      if (mode === "login") {
        const { error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) throw error;
      }

      if (mode === "reset") {
        const { error } = await supabase.auth.resetPasswordForEmail(email, {
          redirectTo: window.location.origin,
        });
        if (error) throw error;
        setMessage("비밀번호 재설정 메일을 보냈습니다.");
      }
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-page">
      <section className="auth-card">
        <div className="auth-hero">
          <span className="brand-mark">P</span>
          <h1>PokeBinder</h1>
          <p>카드 컬렉션, 가격 기록, 스캔 후보를 한 곳에서 관리하세요.</p>
        </div>

        <div className="auth-panel">
          <div className="auth-heading">
            <h2>{authTitle(mode)}</h2>
            <p>{authSubtitle(mode)}</p>
          </div>

          <form className="form-stack" onSubmit={handleSubmit}>
            {mode === "signup" && (
              <label className="field">
                <span>아이디</span>
                <input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="collector_ksl" />
              </label>
            )}

            <label className="field">
              <span>이메일</span>
              <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
            </label>

            {mode !== "reset" && (
              <label className="field">
                <span>비밀번호</span>
                <div className="password-row">
                  <input
                    type={showPassword ? "text" : "password"}
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="8자 이상 권장"
                  />
                  <button type="button" onClick={() => setShowPassword((value) => !value)} aria-label="비밀번호 보기">
                    {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </label>
            )}

            {mode === "signup" && (
              <label className="field">
                <span>비밀번호 재확인</span>
                <input
                  type={showPassword ? "text" : "password"}
                  value={confirm}
                  onChange={(event) => setConfirm(event.target.value)}
                  placeholder="한 번 더 입력"
                />
              </label>
            )}

            <button className="primary-button" type="submit" disabled={busy}>
              {busy ? "처리 중" : authButtonText(mode)}
              <ChevronRight size={18} />
            </button>
          </form>

          {message && <p className="notice-text">{message}</p>}

          {mode === "login" && (
            <div className="auth-links">
              <button type="button" onClick={() => setMode("reset")}>
                비밀번호를 잊으셨나요?
              </button>
              <div className="divider-line" />
              <button className="outline-link" type="button" onClick={() => setMode("signup")}>
                새 계정 만들기
              </button>
            </div>
          )}

          {mode === "signup" && (
            <div className="auth-links">
              <button type="button" onClick={() => setMode("login")}>
                이미 계정이 있으신가요? 로그인
              </button>
            </div>
          )}

          {mode === "reset" && (
            <div className="auth-links">
              <button type="button" onClick={() => setMode("login")}>
                로그인으로 돌아가기
              </button>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function PasswordRecoveryScreen({ onDone }) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [message, setMessage] = useState("");

  async function handleSubmit(event) {
    event.preventDefault();
    try {
      if (password !== confirm) throw new Error("비밀번호가 서로 다릅니다.");
      const { error } = await supabase.auth.updateUser({ password });
      if (error) throw error;
      setMessage("비밀번호가 변경되었습니다.");
      setTimeout(onDone, 900);
    } catch (error) {
      setMessage(error.message);
    }
  }

  return (
    <div className="auth-page">
      <section className="auth-card narrow">
        <div className="auth-panel">
          <h1>새 비밀번호 설정</h1>
          <form className="form-stack" onSubmit={handleSubmit}>
            <label className="field">
              <span>새 비밀번호</span>
              <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
            </label>
            <label className="field">
              <span>새 비밀번호 재확인</span>
              <input type="password" value={confirm} onChange={(event) => setConfirm(event.target.value)} />
            </label>
            <button className="primary-button" type="submit">
              변경
            </button>
          </form>
          {message && <p className="notice-text">{message}</p>}
        </div>
      </section>
    </div>
  );
}

function SearchView({ query, setQuery, providerId, setProviderId, results, displayCurrency, rates, searching, onSearch, onAdd }) {
  return (
    <section className="content-grid">
      <div className="glass-panel search-panel">
        <div>
          <p className="eyebrow">Multilingual Lookup</p>
          <h2>카드 검색</h2>
        </div>
        <form className="search-form" onSubmit={onSearch}>
          <select value={providerId} onChange={(event) => setProviderId(event.target.value)} aria-label="데이터 소스">
            <option value="all">전체 언어</option>
            <option value="tcgdex-ja">TCGdex 일본어</option>
            <option value="tcgdex-ko">TCGdex 한국어</option>
            <option value="tcgdex-en">TCGdex 영어</option>
            <option value="pokemontcg-en">Pokemon TCG 가격</option>
          </select>
          <div className="search-input-wrap">
            <Search size={19} />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="리자몽, リザードン, Charizard, 205/172"
            />
          </div>
          <button className={searching ? "primary-button is-loading" : "primary-button"} type="submit" disabled={searching}>
            {searching && <span className="button-spinner" />}
            {searching ? "검색 중" : "검색"}
          </button>
        </form>
      </div>

      <div className="card-grid">
        {results.length ? (
          results.map((card) => (
            <SearchCard
              key={`${card.source}-${card.language}-${card.id}`}
              card={card}
              displayCurrency={displayCurrency}
              rates={rates}
              onAdd={(quantity) => onAdd(card, quantity)}
            />
          ))
        ) : (
          <EmptyState title="검색 결과 없음" text="한국어, 일본어, 영어 이름 중 아무거나 입력해보세요." />
        )}
      </div>
    </section>
  );
}

function SearchCard({ card, displayCurrency, rates, onAdd }) {
  const [confirming, setConfirming] = useState(false);
  const [quantity, setQuantity] = useState(1);
  const [busy, setBusy] = useState(false);
  const price = Number(card.marketPrice || 0)
    ? formatMoney(convertMoney(card.marketPrice, card.currency, displayCurrency, rates), displayCurrency)
    : "";

  async function confirmAdd() {
    setBusy(true);
    try {
      await onAdd(quantity);
      setConfirming(false);
      setQuantity(1);
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="result-card">
      <div className="result-image">{card.image ? <img src={card.image} alt={card.name} loading="lazy" /> : <span>No image</span>}</div>
      <div className="result-body">
        <h3>{card.name}</h3>
        <p>{compactMeta(card) || card.providerLabel}</p>
        <p>{localizedLine(card)}</p>
        {price && <strong>{price}</strong>}
        <div className="result-action-slot">
          {confirming ? (
            <div className="add-confirm">
              <div className="add-stepper" aria-label="추가 수량">
                <button type="button" onClick={() => setQuantity((value) => Math.max(1, value - 1))}>
                  -
                </button>
                <strong>{quantity}</strong>
                <button type="button" onClick={() => setQuantity((value) => value + 1)}>
                  +
                </button>
              </div>
              <button className={busy ? "primary-button is-loading" : "primary-button"} type="button" onClick={confirmAdd} disabled={busy}>
                {busy && <span className="button-spinner" />}
                {busy ? "추가 중" : "확정"}
              </button>
            </div>
          ) : (
            <button className="soft-button" type="button" onClick={() => setConfirming(true)}>
              <Check size={17} />
              추가
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

function CollectionView({
  title = "내 컬렉션",
  subtitle,
  cards,
  sort,
  setSort,
  layout,
  setLayout,
  displayCurrency,
  rates,
  refreshing,
  onRefreshPrices,
  onPatch,
  onQuantity,
  onOpenCard,
  emptyTitle = "컬렉션 비어 있음",
  emptyText = "검색 화면에서 첫 카드를 추가해보세요.",
}) {
  return (
    <section className="content-grid">
      <div className="glass-panel panel-row">
        <div>
          <p className="eyebrow">Binder</p>
          <h2>{title}</h2>
          {subtitle && <p className="panel-subtitle">{subtitle}</p>}
        </div>
        <div className="panel-actions">
          <div className="layout-toggle" role="group" aria-label="보기 방식">
            <button className={layout === "grid" ? "active" : ""} type="button" onClick={() => setLayout("grid")}>
              <Grid size={16} />
              블록
            </button>
            <button className={layout === "list" ? "active" : ""} type="button" onClick={() => setLayout("list")}>
              <List size={16} />
              리스트
            </button>
          </div>
          <button className={refreshing ? "primary-button is-loading" : "primary-button"} type="button" onClick={onRefreshPrices} disabled={refreshing}>
            {refreshing ? <span className="button-spinner" /> : <RefreshCw size={18} />}
            {refreshing ? "새로고침 중" : "가격 새로고침"}
          </button>
          <select value={sort} onChange={(event) => setSort(event.target.value)} aria-label="정렬">
            <option value="registered">등록순</option>
            <option value="name-ko">이름순</option>
            <option value="price">가격순</option>
          </select>
        </div>
      </div>

      <div className={`collection-list ${layout === "grid" ? "grid-mode" : "list-mode"}`}>
        {cards.length ? (
          cards.map((card) => (
            <CollectionCard
              key={card.uid}
              card={card}
              layout={layout}
              displayCurrency={displayCurrency}
              rates={rates}
              onPatch={(patch) => onPatch(card, patch)}
              onQuantity={(delta) => onQuantity(card, delta)}
              onOpen={() => onOpenCard(card)}
            />
          ))
        ) : (
          <EmptyState title={emptyTitle} text={emptyText} />
        )}
      </div>
    </section>
  );
}

function CollectionCard({ card, layout, displayCurrency, rates, onPatch, onQuantity, onOpen }) {
  const [draftQuantity, setDraftQuantity] = useState(Number(card.quantity || 0));
  const [savingQuantity, setSavingQuantity] = useState(false);
  const subtotal = Number(card.marketPrice || 0) * Number(card.quantity || 0);
  const displaySubtotal = convertMoney(subtotal, card.currency, displayCurrency, rates);
  const unitDisplay = convertMoney(card.marketPrice, card.currency, displayCurrency, rates);
  const change = cardChange(card, displayCurrency, rates);
  const quantityChanged = draftQuantity !== Number(card.quantity || 0);

  useEffect(() => {
    setDraftQuantity(Number(card.quantity || 0));
  }, [card.quantity]);

  async function saveQuantity() {
    setSavingQuantity(true);
    try {
      await onQuantity(draftQuantity - Number(card.quantity || 0));
    } finally {
      setSavingQuantity(false);
    }
  }

  return (
    <article className={`collection-card ${layout === "grid" ? "grid" : "list"}`}>
      <button className="collection-thumb thumb-button" type="button" onClick={onOpen} aria-label={`${card.name} 상세 보기`}>
        {card.image ? <img src={card.image} alt={card.name} /> : <span>No image</span>}
      </button>
      <div className="collection-info">
        <div className="collection-title-row">
          <div>
            <button className="card-title-button" type="button" onClick={onOpen}>
              <h3>{card.name}</h3>
            </button>
            <p>{compactMeta(card)}</p>
            <p>{localizedLine(card)}</p>
          </div>
          <div className="collection-side">
            <div className="collection-quick-actions">
              <button
                className={card.isFavorite ? "favorite-button active" : "favorite-button"}
                type="button"
                onClick={() => onPatch({ isFavorite: !card.isFavorite })}
                aria-label={card.isFavorite ? "즐겨찾기 해제" : "즐겨찾기 추가"}
              >
                <Heart size={17} />
              </button>
              <button className="icon-button" type="button" onClick={onOpen} aria-label="상세 정보">
                <Info size={17} />
              </button>
            </div>
            <div className="value-box">
              <strong>{subtotal ? formatMoney(displaySubtotal, displayCurrency) : "가격 정보 없음"}</strong>
              <span>{card.marketPrice ? `${formatMoney(unitDisplay, displayCurrency)} / 장` : "가격 조회 대기"}</span>
            </div>
          </div>
        </div>

        <div className="card-controls">
          <label>
            <span>상태</span>
            <select value={card.condition || "NM"} onChange={(event) => onPatch({ condition: event.target.value })}>
              {["NM", "LP", "MP", "HP", "DMG"].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>타입</span>
            <select value={card.finish || "normal"} onChange={(event) => onPatch({ finish: event.target.value })}>
              {["normal", "holo", "reverse", "masterball", "pokeball", "unknown"].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="chart-row">
          <Sparkline card={card} displayCurrency={displayCurrency} rates={rates} />
          <ChangePill change={change} currency={displayCurrency} />
          <small>{priceSourceLabel(card)}</small>
        </div>
      </div>

      <div className="quantity-rail">
        <button type="button" onClick={() => setDraftQuantity((value) => value + 1)}>
          +
        </button>
        <strong>{draftQuantity}</strong>
        <button type="button" onClick={() => setDraftQuantity((value) => Math.max(0, value - 1))}>
          -
        </button>
        <button className={savingQuantity ? "quantity-save is-loading" : "quantity-save"} type="button" onClick={saveQuantity} disabled={!quantityChanged || savingQuantity}>
          {savingQuantity && <span className="button-spinner" />}
          {savingQuantity ? "저장 중" : "저장"}
        </button>
      </div>
    </article>
  );
}

function CardDetailModal({ card, displayCurrency, rates, onClose, onPatch, onQuantity }) {
  const [draftQuantity, setDraftQuantity] = useState(Number(card.quantity || 0));
  const [savingQuantity, setSavingQuantity] = useState(false);
  const subtotal = Number(card.marketPrice || 0) * Number(card.quantity || 0);
  const displaySubtotal = convertMoney(subtotal, card.currency, displayCurrency, rates);
  const unitDisplay = convertMoney(card.marketPrice, card.currency, displayCurrency, rates);
  const change = cardChange(card, displayCurrency, rates);
  const quantityChanged = draftQuantity !== Number(card.quantity || 0);
  const details = [
    ["세트", card.setName || "-"],
    ["번호", card.number || "-"],
    ["레어도", card.rarity || "-"],
    ["언어", languageLabel(card.language)],
    ["상태", card.condition || "NM"],
    ["타입", card.finish || "normal"],
    ["가격 출처", priceSourceLabel(card)],
    ["등록일", card.addedAt ? new Date(card.addedAt).toLocaleDateString("ko-KR") : "-"],
  ];

  useEffect(() => {
    function handleKeyDown(event) {
      if (event.key === "Escape") onClose();
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    setDraftQuantity(Number(card.quantity || 0));
  }, [card.quantity]);

  async function saveQuantity() {
    setSavingQuantity(true);
    try {
      await onQuantity(draftQuantity - Number(card.quantity || 0));
    } finally {
      setSavingQuantity(false);
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <article className="card-modal" role="dialog" aria-modal="true" aria-label={`${card.name} 상세 정보`} onClick={(event) => event.stopPropagation()}>
        <button className="modal-close" type="button" onClick={onClose} aria-label="닫기">
          <X size={20} />
        </button>

        <div className="modal-image">
          {card.imageHigh || card.image ? <img src={card.imageHigh || card.image} alt={card.name} /> : <span>No image</span>}
        </div>

        <div className="modal-body">
          <div className="modal-title-row">
            <div>
              <p className="eyebrow">{card.providerLabel || "Pokemon Card"}</p>
              <h2>{card.name}</h2>
              <p>{localizedLine(card)}</p>
            </div>
            <button
              className={card.isFavorite ? "favorite-button large active" : "favorite-button large"}
              type="button"
              onClick={() => onPatch({ isFavorite: !card.isFavorite })}
              aria-label={card.isFavorite ? "즐겨찾기 해제" : "즐겨찾기 추가"}
            >
              <Heart size={20} />
            </button>
          </div>

          <div className="detail-value-panel">
            <span>총 평가액</span>
            <strong>{subtotal ? formatMoney(displaySubtotal, displayCurrency) : "가격 정보 없음"}</strong>
            <small>{card.marketPrice ? `${formatMoney(unitDisplay, displayCurrency)} / 장 · ${card.quantity}장 보유` : `${card.quantity}장 보유`}</small>
            <ChangePill change={change} currency={displayCurrency} />
          </div>

          <div className="detail-chips">
            {[card.setId, card.rarity, card.condition, card.finish].filter(Boolean).map((value) => (
              <span key={value}>{value}</span>
            ))}
          </div>

          <Sparkline card={card} displayCurrency={displayCurrency} rates={rates} />

          <div className="detail-grid">
            {details.map(([label, value]) => (
              <div key={label}>
                <span>{label}</span>
                <strong>{value}</strong>
              </div>
            ))}
          </div>

          <div className="modal-actions">
            <button className="soft-button" type="button" onClick={() => setDraftQuantity((value) => Math.max(0, value - 1))}>
              -1
            </button>
            <strong>{draftQuantity}장 보유</strong>
            <button className="soft-button" type="button" onClick={() => setDraftQuantity((value) => value + 1)}>
              +1
            </button>
            <button className={savingQuantity ? "primary-button is-loading" : "primary-button"} type="button" onClick={saveQuantity} disabled={!quantityChanged || savingQuantity}>
              {savingQuantity && <span className="button-spinner" />}
              {savingQuantity ? "저장 중" : "수량 저장"}
            </button>
          </div>
        </div>
      </article>
    </div>
  );
}

function ScanView({
  scanText,
  setScanText,
  scanLanguage,
  setScanLanguage,
  cameraActive,
  overlayOpen,
  candidates,
  busy,
  capturedImage,
  displayCurrency,
  rates,
  videoRef,
  canvasRef,
  captureRef,
  onOpen,
  onClose,
  onStart,
  onCapture,
  onRetake,
  onStop,
  onSearch,
  onAdd,
}) {
  return (
    <section className="scan-page">
      <div className="glass-panel scan-panel">
        <p className="eyebrow">Capture</p>
        <h2>카드 스캔</h2>
        <button className="primary-button scan-launch" type="button" onClick={onOpen}>
          <Camera size={20} />
          스캔 창 열기
        </button>
        <div className="scan-preview-panel">
          <div className="scan-phone-frame">
            <Camera size={18} />
          </div>
          <div>
            <strong>풀스크린 인식 모드</strong>
            <p className="subtle-text">후보 카드는 촬영 화면 하단에 표시됩니다.</p>
          </div>
        </div>
      </div>

      {overlayOpen && (
        <ScannerOverlay
          scanText={scanText}
          setScanText={setScanText}
          scanLanguage={scanLanguage}
          setScanLanguage={setScanLanguage}
          cameraActive={cameraActive}
          candidates={candidates}
          busy={busy}
          capturedImage={capturedImage}
          displayCurrency={displayCurrency}
          rates={rates}
          videoRef={videoRef}
          canvasRef={canvasRef}
          captureRef={captureRef}
          onClose={onClose}
          onStart={onStart}
          onCapture={onCapture}
          onRetake={onRetake}
          onStop={onStop}
          onSearch={onSearch}
          onAdd={onAdd}
        />
      )}
    </section>
  );
}

function ScannerOverlay({
  scanText,
  setScanText,
  scanLanguage,
  setScanLanguage,
  cameraActive,
  candidates,
  busy,
  capturedImage,
  displayCurrency,
  rates,
  videoRef,
  canvasRef,
  captureRef,
  onClose,
  onStart,
  onCapture,
  onRetake,
  onStop,
  onSearch,
  onAdd,
}) {
  useEffect(() => {
    onStart();
    return () => onStop();
  }, []);

  return (
    <div className="scanner-overlay" role="dialog" aria-modal="true" aria-label="카드 스캔">
      <video ref={videoRef} playsInline muted />
      <canvas ref={canvasRef} hidden />
      <div ref={captureRef} className="capture-preview" hidden />

      <div className="scanner-topbar">
        <div>
          <p className="eyebrow">Live Scan</p>
          <strong>{cameraActive ? "카메라 활성" : "카메라 준비"}</strong>
        </div>
        <button className="modal-close" type="button" onClick={onClose} aria-label="닫기">
          <X size={20} />
        </button>
      </div>

      <div className="scan-target" aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
      </div>

      <div className="scanner-bottom-sheet">
        <div className="scan-command-row">
          <label className="scan-input scan-language">
            <span>언어</span>
            <select value={scanLanguage} onChange={(event) => setScanLanguage(event.target.value)}>
              <option value="ja">일본판</option>
              <option value="ko">한국판</option>
              <option value="en">영문판</option>
              <option value="all">전체</option>
            </select>
          </label>
          <label className="scan-input">
            <span>인식 텍스트</span>
            <input value={scanText} onChange={(event) => setScanText(event.target.value)} placeholder="카드명 또는 번호" />
          </label>
          <button className="soft-button" type="button" onClick={capturedImage ? onRetake : onCapture} disabled={!cameraActive}>
            {capturedImage ? "다시 촬영" : "촬영"}
          </button>
          <button className={busy ? "primary-button is-loading" : "primary-button"} type="button" onClick={onSearch} disabled={busy}>
            {busy && <span className="button-spinner" />}
            {busy ? "검색 중" : "텍스트 검색"}
          </button>
        </div>

        <ScanCandidateStrip candidates={candidates} displayCurrency={displayCurrency} rates={rates} onAdd={onAdd} />
      </div>
    </div>
  );
}

function ScanCandidateStrip({ candidates, displayCurrency, rates, onAdd }) {
  if (!candidates.length) {
    return (
      <div className="scan-candidate-empty">
        <Sparkles size={18} />
        <span>후보 대기</span>
      </div>
    );
  }

  return (
    <div className="scan-candidate-strip" aria-label="스캔 후보">
      {candidates.map((card) => {
        const price = Number(card.marketPrice || 0)
          ? formatMoney(convertMoney(card.marketPrice, card.currency, displayCurrency, rates), displayCurrency)
          : "가격 확인 중";

        return (
          <article className="scan-candidate-card" key={`${card.source}-${card.language}-${card.id}`}>
            <div className="scan-candidate-image">{card.image ? <img src={card.image} alt={card.name} /> : <span>No image</span>}</div>
            <div>
              <h3>{card.name}</h3>
              <p>{compactMeta(card) || card.providerLabel}</p>
              <strong>{price}</strong>
            </div>
            <button className="soft-button" type="button" onClick={() => onAdd(card)}>
              추가
            </button>
          </article>
        );
      })}
    </div>
  );
}

function SettingsView({ user, themeMode, setThemeMode, displayCurrency, setDisplayCurrency, collection, onExport, onImport, onStatus }) {
  const [username, setUsername] = useState(user.user_metadata?.username || "");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [busy, setBusy] = useState(false);

  async function saveProfile() {
    setBusy(true);
    try {
      const { error } = await supabase.auth.updateUser({ data: { username: username.trim() } });
      if (error) throw error;
      onStatus("프로필 저장됨");
    } catch (error) {
      onStatus(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function updatePassword() {
    setBusy(true);
    try {
      if (newPassword !== confirmPassword) throw new Error("비밀번호가 서로 다릅니다.");
      const { error } = await supabase.auth.updateUser({ password: newPassword });
      if (error) throw error;
      setNewPassword("");
      setConfirmPassword("");
      onStatus("비밀번호 변경됨");
    } catch (error) {
      onStatus(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    await supabase.auth.signOut();
  }

  return (
    <section className="settings-grid">
      <div className="glass-panel">
        <p className="eyebrow">My Page</p>
        <h2>마이페이지</h2>
        <div className="profile-card">
          <div className="avatar large">{initialOf(user.email)}</div>
          <div>
            <strong>{user.email}</strong>
            <small>{user.id}</small>
          </div>
        </div>
        <label className="field">
          <span>아이디</span>
          <input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="표시 이름" />
        </label>
        <button className="primary-button" type="button" disabled={busy} onClick={saveProfile}>
          <User size={18} />
          프로필 저장
        </button>
      </div>

      <div className="glass-panel">
        <p className="eyebrow">Appearance</p>
        <h2>테마</h2>
        <div className="theme-options">
          <ThemeButton active={themeMode === "light"} icon={Sun} label="라이트" onClick={() => setThemeMode("light")} />
          <ThemeButton active={themeMode === "dark"} icon={Moon} label="다크" onClick={() => setThemeMode("dark")} />
          <ThemeButton active={themeMode === "system"} icon={Sparkles} label="기기 설정" onClick={() => setThemeMode("system")} />
        </div>
        <label className="field">
          <span>기본 표기 통화</span>
          <select value={displayCurrency} onChange={(event) => setDisplayCurrency(event.target.value)}>
            {DISPLAY_CURRENCIES.map((currency) => (
              <option key={currency} value={currency}>
                {currency}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="glass-panel">
        <p className="eyebrow">Security</p>
        <h2>비밀번호 변경</h2>
        <label className="field">
          <span>새 비밀번호</span>
          <input type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
        </label>
        <label className="field">
          <span>새 비밀번호 재확인</span>
          <input type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} />
        </label>
        <button className="primary-button" type="button" disabled={busy || !newPassword} onClick={updatePassword}>
          <Shield size={18} />
          비밀번호 변경
        </button>
      </div>

      <div className="glass-panel">
        <p className="eyebrow">Data</p>
        <h2>백업과 동기화</h2>
        <div className="backup-actions">
          <button className="soft-button" type="button" onClick={onExport}>
            <Download size={18} />
            JSON 내보내기
          </button>
          <label className="file-button">
            <Upload size={18} />
            JSON 가져오기
            <input type="file" accept="application/json" onChange={onImport} />
          </label>
        </div>
        <p className="subtle-text">Supabase에 {collection.length.toLocaleString("ko-KR")}종류가 저장되어 있습니다.</p>
      </div>

      <div className="glass-panel danger-zone">
        <p className="eyebrow">Session</p>
        <h2>로그아웃</h2>
        <button className="danger-button" type="button" onClick={logout}>
          <LogOut size={18} />
          로그아웃
        </button>
      </div>
    </section>
  );
}

function ThemeButton({ active, icon: Icon, label, onClick }) {
  return (
    <button className={active ? "theme-button active" : "theme-button"} type="button" onClick={onClick}>
      <Icon size={18} />
      {label}
    </button>
  );
}

function Sparkline({ card, displayCurrency, rates }) {
  const points = chartPoints(card, displayCurrency, rates);
  const width = 240;
  const height = 70;

  if (!points.length) return <div className="sparkline-empty">가격 기록 없음</div>;

  const values = points.map((point) => point.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const spread = max - min || 1;
  const coordinates = points.map((point, index) => {
    const x = points.length === 1 ? width / 2 : (index / (points.length - 1)) * width;
    const y = height - ((point.value - min) / spread) * (height - 14) - 7;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  return (
    <svg className="sparkline" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="가격 차트">
      <line x1="0" y1={height - 7} x2={width} y2={height - 7} />
      <polyline points={coordinates.join(" ")} />
    </svg>
  );
}

function ChangePill({ change, currency }) {
  if (!change) return <span className="change-pill flat">변동 데이터 없음</span>;
  const Icon = change.direction === "up" ? ArrowUp : change.direction === "down" ? ArrowDown : Cloud;
  return (
    <span className={`change-pill ${change.direction}`}>
      <Icon size={14} />
      {change.direction === "up" ? "상승" : change.direction === "down" ? "하락" : "변동 없음"}
      {formatMoney(Math.abs(change.diff), currency)}
      {formatPercent(change.percent)}
    </span>
  );
}

function Metric({ label, value, change, direction, wide }) {
  return (
    <div className={wide ? "metric wide" : "metric"}>
      <span>{label}</span>
      <strong>{value}</strong>
      {change && <small className={direction}>{change}</small>}
    </div>
  );
}

function EmptyState({ title, text }) {
  return (
    <div className="empty-state">
      <Sparkles size={24} />
      <strong>{title}</strong>
      <span>{text}</span>
    </div>
  );
}

function StatusPill({ text }) {
  return (
    <span className="status-pill">
      <Cloud size={15} />
      {text}
    </span>
  );
}

function Toast({ text }) {
  return (
    <div className="toast" role="status" aria-live="polite">
      <Check size={17} />
      {text}
    </div>
  );
}

function Splash({ compact }) {
  return (
    <div className={compact ? "splash compact" : "splash"}>
      <span className="brand-mark">P</span>
      <strong>PokeBinder</strong>
    </div>
  );
}

function SetupMissing() {
  return (
    <div className="auth-page">
      <section className="auth-card narrow">
        <div className="auth-panel">
          <h1>Supabase 설정 필요</h1>
          <p className="subtle-text">`.env.local`에 `VITE_SUPABASE_URL`과 `VITE_SUPABASE_ANON_KEY`를 넣어주세요.</p>
        </div>
      </section>
    </div>
  );
}

function useThemeMode() {
  const [mode, setMode] = useState(localStorage.getItem(THEME_KEY) || "system");

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const resolved = mode === "system" ? (media.matches ? "dark" : "light") : mode;
      document.documentElement.dataset.theme = resolved;
      document.documentElement.dataset.themeMode = mode;
      localStorage.setItem(THEME_KEY, mode);
    };

    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [mode]);

  return [mode, setMode];
}

function makeSummary(cards, displayCurrency, rates, snapshots) {
  const totalCards = cards.reduce((sum, card) => sum + Number(card.quantity || 0), 0);
  const totalValue = calculatePortfolioValue(cards, displayCurrency, rates);
  const previous = findPreviousSnapshot(snapshots);
  let changeText = "전일 데이터 없음";
  let direction = "flat";

  if (previous?.baseUsd) {
    const nowBase = calculatePortfolioBaseUsd(cards, rates);
    const diffBase = nowBase - previous.baseUsd;
    const diff = convertMoney(diffBase, "USD", displayCurrency, rates);
    const percent = previous.baseUsd ? (diffBase / previous.baseUsd) * 100 : 0;
    direction = diff > 0 ? "up" : diff < 0 ? "down" : "flat";
    changeText = `${direction === "up" ? "상승" : direction === "down" ? "하락" : "변동 없음"} ${formatMoney(Math.abs(diff), displayCurrency)} (${formatPercent(percent)})`;
  }

  return {
    totalCards,
    totalValueText: totalValue ? formatMoney(totalValue, displayCurrency) : "-",
    changeText,
    direction,
  };
}

function calculatePortfolioValue(cards, currency, rates) {
  return cards.reduce(
    (sum, card) => sum + convertMoney(Number(card.marketPrice || 0) * Number(card.quantity || 0), card.currency, currency, rates),
    0,
  );
}

function calculatePortfolioBaseUsd(cards, rates) {
  return cards.reduce(
    (sum, card) => sum + convertMoney(Number(card.marketPrice || 0) * Number(card.quantity || 0), card.currency, "USD", rates),
    0,
  );
}

function findPreviousSnapshot(snapshots) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return [...snapshots].reverse().find((snapshot) => new Date(snapshot.capturedAt) < today);
}

function sortCollection(cards, sort, displayCurrency, rates) {
  return [...cards].sort((a, b) => {
    if (sort === "name-ko") return preferredKoreanName(a).localeCompare(preferredKoreanName(b), "ko-KR", { numeric: true });
    if (sort === "price") {
      const bValue = convertMoney(Number(b.marketPrice || 0), b.currency, displayCurrency, rates);
      const aValue = convertMoney(Number(a.marketPrice || 0), a.currency, displayCurrency, rates);
      return bValue - aValue;
    }
    return new Date(b.addedAt || b.updatedAt || 0) - new Date(a.addedAt || a.updatedAt || 0);
  });
}

function chartPoints(card, displayCurrency, rates) {
  const history = Array.isArray(card.priceHistory) ? card.priceHistory : [];
  const points = history
    .map((point) => ({
      capturedAt: point.capturedAt,
      value: convertMoney(point.price, point.currency || card.currency, displayCurrency, rates),
    }))
    .filter((point) => Number(point.value || 0) > 0)
    .sort((a, b) => new Date(a.capturedAt) - new Date(b.capturedAt));

  if (!points.length && Number(card.marketPrice || 0) > 0) {
    points.push({
      capturedAt: card.updatedAtMarket || card.updatedAt || new Date().toISOString(),
      value: convertMoney(card.marketPrice, card.currency, displayCurrency, rates),
    });
  }

  return points;
}

function cardChange(card, displayCurrency, rates) {
  const points = chartPoints(card, displayCurrency, rates);
  if (points.length < 2) return null;
  const current = points.at(-1);
  const previous = points.at(-2);
  const diff = current.value - previous.value;
  return {
    diff,
    percent: previous.value ? (diff / previous.value) * 100 : 0,
    direction: diff > 0 ? "up" : diff < 0 ? "down" : "flat",
  };
}

function compactMeta(card) {
  return [card.setName, card.number ? `#${card.number}` : "", card.setId].filter(Boolean).join(" · ");
}

function localizedLine(card) {
  const names = card.localizedNames || {};
  return [names.ja && `JA ${names.ja}`, names.ko && `KO ${names.ko}`, names.en && `EN ${names.en}`]
    .filter(Boolean)
    .join(" · ");
}

function preferredKoreanName(card) {
  return card.localizedNames?.ko || card.localizedNames?.ja || card.localizedNames?.en || card.name || "";
}

function priceSourceLabel(card) {
  if (!Number(card.marketPrice || 0)) return "가격 정보 없음";
  const source =
    card.priceSource === "tcgplayer"
      ? "TCGplayer"
      : card.priceSource === "cardmarket"
        ? "Cardmarket"
        : card.priceSource === "yuyutei"
          ? "yuyu-tei"
          : card.priceSource === "tcgbox"
            ? "TCGBOX"
            : "Manual";
  const updated = card.updatedAtMarket ? ` · ${card.updatedAtMarket}` : "";
  return `${source}${updated}`;
}

function languageLabel(language) {
  if (language === "ja") return "일본어";
  if (language === "ko") return "한국어";
  if (language === "en") return "영어";
  return language || "-";
}

function pageTitle(view) {
  if (view === "collection") return "내 컬렉션";
  if (view === "favorites") return "즐겨찾기";
  if (view === "scan") return "카드 스캔";
  if (view === "settings") return "설정";
  return "카드 검색";
}

function authButtonText(mode) {
  if (mode === "signup") return "계정 만들기";
  if (mode === "reset") return "재설정 메일 보내기";
  return "로그인";
}

function authTitle(mode) {
  if (mode === "signup") return "계정 만들기";
  if (mode === "reset") return "비밀번호 재설정";
  return "로그인";
}

function authSubtitle(mode) {
  if (mode === "signup") return "컬렉션을 클라우드에 안전하게 동기화하세요.";
  if (mode === "reset") return "가입한 이메일로 재설정 링크를 보내드립니다.";
  return "내 카드 바인더로 계속하기";
}

function initialOf(email) {
  return String(email || "P").slice(0, 1).toUpperCase();
}

function formatPercent(value) {
  const sign = value > 0 ? "+" : "";
  return `${sign}${Number(value || 0).toFixed(2)}%`;
}

function sameLocalDay(a, b) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

installPwaMetadata();

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

registerServiceWorker();

function registerServiceWorker() {
  if (import.meta.env.DEV) {
    navigator.serviceWorker?.getRegistrations?.().then((registrations) => registrations.forEach((registration) => registration.unregister()));
    return;
  }

  if (!("serviceWorker" in navigator)) return;

  window.addEventListener("load", () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: import.meta.env.BASE_URL }).catch(() => {});
  });
}

function installPwaMetadata() {
  upsertHeadLink("manifest", `${import.meta.env.BASE_URL}manifest.webmanifest`);
  upsertHeadLink("icon", `${import.meta.env.BASE_URL}icons/icon.svg`, "image/svg+xml");
}

function upsertHeadLink(rel, href, type) {
  const link = document.querySelector(`link[rel="${rel}"]`) || document.createElement("link");
  link.rel = rel;
  link.href = href;
  if (type) link.type = type;
  if (!link.parentNode) document.head.append(link);
}
