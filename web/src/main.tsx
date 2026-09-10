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
import AppsPage from "./pages/AppsPage";
import Knowledge from "./pages/Knowledge";
import ModelsPage from "./pages/ModelsPage";
import Login from "./pages/Login";
import Overview from "./pages/Overview";
import SearchPage from "./pages/SearchPage";
import TasksPage from "./pages/TasksPage";
import OperationsPage from "./pages/OperationsPage";
import "./styles.css";
import { ErrorNote, Loading } from "./ui";
function App() {
  const [logged, setLogged] = useState(!!getToken()),
    [page, setPage] = useState<Page>("overview"),
    [me, setMe] = useState<Row | null>(null),
    [connectionError, setConnectionError] = useState("");
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
  const visibleNav = nav.filter((item) =>
    me?.role === "OPS"
      ? item.id === "operations"
      : item.id !== "operations" || ["OWNER", "ADMIN"].includes(me?.role),
  );
  const effectivePage = me?.role === "OPS" ? "operations" : page;
  return (
    <div className="app">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setPage("overview");
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
          {!me ? (
            <Loading />
          ) : effectivePage === "improvements" ? (
            <ImprovementsPage />
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
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
