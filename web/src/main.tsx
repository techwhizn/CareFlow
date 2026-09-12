import EvaluationPage from "./pages/EvaluationPage";
import ImprovementsPage from "./pages/ImprovementsPage";
import { Books, CaretRight, ShieldCheck, SignOut } from "@phosphor-icons/react";
import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import type { Row } from "./api";
import { ApiError, getToken, request, setToken } from "./api";
import type { Page } from "./navigation";
import { nav } from "./navigation";
import AdminPage from "./pages/AdminPage";
import AnswerPage from "./pages/AnswerPage";
import QuickAnswerPage from "./pages/QuickAnswerPage";
import AppsPage from "./pages/AppsPage";
import Knowledge from "./pages/Knowledge";
import ModelsPage from "./pages/ModelsPage";
import Login from "./pages/Login";
import Overview from "./pages/Overview";
import SearchPage from "./pages/SearchPage";
import TasksPage from "./pages/TasksPage";
import OperationsPage from "./pages/OperationsPage";
import "./styles.css";
import { Dialog, ErrorNote, Loading } from "./ui";
function App() {
  const [logged, setLogged] = useState(!!getToken()),
    [page, setPage] = useState<Page>("answers"),
    [me, setMe] = useState<Row | null>(null),
    [connectionError, setConnectionError] = useState(""),
    [newCredential, setNewCredential] = useState("");
  async function rotateCredential() {
    if (!window.confirm("更换后当前凭证会立即失效，确认继续？")) return;
    try {
      const result = await request<{ token: string }>("/me/credentials", { method: "POST" });
      setToken(result.token);
      setNewCredential(result.token);
      setMe((current) => current ? { ...current, default_credential: false } : current);
    } catch (error) {
      setConnectionError((error as Error).message);
    }
  }
  useEffect(() => {
    if (logged)
      request("/me")
        .then(setMe)
        .catch((error) => {
          if (error instanceof ApiError && error.status === 401) {
            setToken("");
            setLogged(false);
          } else
            setConnectionError(
              "暂时无法连接服务，访问凭证仍保留。请稍后刷新重试。",
            );
        });
  }, [logged]);
  if (!logged) return <Login done={() => setLogged(true)} />;
  const adminPages = new Set(["usage", "members", "models", "audit", "settings"]);
  const visibleNav = nav.filter((item) => {
    if (me?.role === "OPS") return item.id === "operations";
    if (item.id === "operations") return ["OWNER", "ADMIN"].includes(me?.role || "");
    if (adminPages.has(item.id)) return ["OWNER", "ADMIN"].includes(me?.role || "");
    return true;
  });
  const effectivePage = me?.role === "OPS" ? "operations" : page;
  return (
    <div className="app">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setPage("answers");
          }}
        >
          <div>
            <Books size={25} weight="fill" />
          </div>
          CareFlow<span>知识平台</span>
        </a>
        <div className="tenant">
          <span>{me?.tenant?.name?.slice(0, 1) || "企"}</span>
          <div>
            <b>{me?.tenant?.name || "企业工作空间"}</b>
            <small>企业知识工作空间</small>
          </div>
        </div>
        <nav>
          {visibleNav.map((item, i) => (
            <React.Fragment key={item.id}>
              {(i === 0 || visibleNav[i - 1].group !== item.group) && (
                <p>{item.group}</p>
              )}
              <button
                title={item.label}
                className={effectivePage === item.id ? "active" : ""}
                onClick={() => setPage(item.id)}
              >
                <item.icon
                  size={21}
                  weight={effectivePage === item.id ? "duotone" : "regular"}
                />
                {item.label}
                {effectivePage === item.id && <span className="nav-dot" />}
              </button>
            </React.Fragment>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="bottom-note">
            <ShieldCheck size={19} />
            <span>以当前授权范围访问知识</span>
          </div>
          <button
            aria-label="退出当前会话"
            onClick={() => {
              setToken("");
              setLogged(false);
              setMe(null);
            }}
          >
            <span className="avatar">{me?.role === "OWNER" ? "管" : "用"}</span>
            <div>
              <b>
                {me?.role === "OWNER" ? "企业所有者" : me?.role || "当前成员"}
              </b>
              <small>退出当前会话</small>
            </div>
            <SignOut size={20} />
          </button>
          <button className="text-button" onClick={() => void rotateCredential()}>更换我的访问凭证</button>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <span>
            工作空间
            <CaretRight size={13} />
            <b>{nav.find((n) => n.id === effectivePage)?.label}</b>
          </span>
          <div>
            <span className="environment">开发预览</span>
            <span className="muted">CareFlow / 0.1</span>
          </div>
        </header>
        <main className="main-content" key={effectivePage}>
          <ErrorNote error={connectionError} />
          {me?.default_credential && effectivePage !== "members" && (
            <div className="security-banner" role="alert">
              <strong>当前使用的是默认访问凭证，存在安全风险。</strong>
              <span>请立即签发新的个人凭证并撤销默认凭证。</span>
              <button className="text-button" onClick={() => void rotateCredential()}>立即更换</button>
            </div>
          )}
          {!me ? (
            <Loading />
          ) : effectivePage === "improvements" ? (
            <ImprovementsPage />
          ) : effectivePage === "evaluation" ? (
            <EvaluationPage />
          ) : effectivePage === "operations" ? (
            <OperationsPage />
          ) : page === "models" ? (
            <ModelsPage />
          ) : page === "overview" ? (
            <Overview go={setPage} />
          ) : page === "knowledge" ? (
            <Knowledge />
          ) : page === "tasks" ? (
            <TasksPage />
          ) : page === "search" ? (
            <SearchPage />
          ) : page === "quick-answer" ? (
            <QuickAnswerPage />
          ) : page === "answers" ? (
            <AnswerPage />
          ) : page === "apps" ? (
            <AppsPage />
          ) : (
            <AdminPage page={page} />
          )}
        </main>
        <footer className="app-footer">
          CareFlow · 独立知识底座<span>草稿经审核与发布后才参与检索</span>
        </footer>
      </div>
      {newCredential && <Dialog title="新的访问凭证" close={() => setNewCredential("")}>
        <p>旧凭证已立即失效。请复制并安全保存；关闭后不会再次显示。</p>
        <textarea readOnly value={newCredential} aria-label="新的访问凭证" />
      </Dialog>}
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
