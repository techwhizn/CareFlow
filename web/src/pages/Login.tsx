import { ArrowRight, Books } from "@phosphor-icons/react";
import React, { useState } from "react";
import { request, setToken } from "../api";
import { ErrorNote } from "../ui";
export default function Login({ done }: { done: () => void }) {
  const [value, setValue] = useState(""),
    [name, setName] = useState("我的企业"),
    [bootstrap, setBootstrap] = useState(false),
    [provision, setProvision] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [issued, setIssued] = useState("");
  async function login(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (bootstrap) {
        const result = await request(provision ? "/enterprises" : "/bootstrap", {
          method: "POST",
          headers: { "X-Bootstrap-Token": value },
          body: JSON.stringify(provision ? {name, owner_name: "企业所有者"} : { name, description: "" }),
        });
        setIssued(result.token);
        setValue(result.token);
        setBootstrap(false);
      } else {
        setToken(value);
        await request("/me");
        done();
      }
    } catch (e) {
      setError((e as Error).message);
      if (!bootstrap) setToken("");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login">
      <div className="login-story">
        <div className="brand">
          <Books size={28} weight="fill" />
          CareFlow
        </div>
        <div>
          <p className="eyebrow">KNOWLEDGE, CONNECTED.</p>
          <h1>
            让组织的知识，
            <br />
            成为可靠的答案。
          </h1>
          <p>
            从文档整理、知识发布到引用问答，
            <br />
            建立可核验、可追溯的企业知识工作空间。
          </p>
          <div className="login-flow">
            <span>原始资料</span>
            <ArrowRight />
            <span>知识切片</span>
            <ArrowRight />
            <span>有据可依</span>
          </div>
        </div>
        <small>独立知识底座 · 可扩展应用</small>
      </div>
      <main>
        <form onSubmit={login}>
          <span className="eyebrow">WORKSPACE ACCESS</span>
          <h2>{bootstrap ? "初始化企业" : "进入工作空间"}</h2>
          <p className="muted">
            {bootstrap
              ? "由部署管理员使用开通密钥；普通用户请使用个人访问凭证。"
              : "使用企业管理员提供的个人访问凭证。"}
          </p>
          <ErrorNote error={error} />
          {issued && (
            <div className="notice">
              企业已创建。请安全保存此凭证，仅显示一次：
              <textarea readOnly value={issued} aria-label="新凭证" />
            </div>
          )}
          {bootstrap && <label><input type="checkbox" checked={provision} onChange={event=>setProvision(event.target.checked)}/>开通另一企业（部署管理员）</label>}
          {bootstrap && (
            <label>
              企业名称
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </label>
          )}
          <label>
            {bootstrap ? "初始化密钥" : "访问凭证"}
            <input
              type="password"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              autoComplete="off"
              required
              placeholder="输入访问凭证"
            />
          </label>
          <button className="primary wide" disabled={busy}>
            {busy ? "连接中…" : bootstrap ? "创建企业" : "进入工作空间"}
            <ArrowRight />
          </button>
          <button
            type="button"
            className="text-button"
            onClick={() => {
              setBootstrap(!bootstrap);
              setError("");
            }}
          >
            {bootstrap ? "已有访问凭证" : "首次部署？初始化企业"}
          </button>
          <p className="footnote">
            凭证仅保存在当前浏览器会话中。关闭会话后需重新登录。
          </p>
        </form>
      </main>
    </div>
  );
}
