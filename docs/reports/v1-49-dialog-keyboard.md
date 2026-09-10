# 通用弹窗键盘回归

2026-09-11，真实Chrome在独立浏览器上下文中复现：点击创建知识库后焦点仍在背景，Escape不关闭弹窗。改用原生modal dialog，并处理React卸载后的焦点归还及遮罩按下的默认焦点动作。

新增`web/e2e/dialog.test.mjs`使用生产Dialog组件，不连接业务API或读取凭证。真实Chromium验证打开时焦点进入、Tab不进入背景按钮、Escape关闭、返回原按钮、遮罩关闭、390px窄屏无横向溢出及零页面异常。React StrictMode也包含在回归中。本地Chrome通过，15个Web行为测试、TypeScript生产构建和生产依赖审计通过。运行器为本机Node26；CI配置使用Node22并安装Chromium，远端CI未执行。

另在恢复后的实际Web5175使用真实个人凭证登录，390px工作台无横向溢出，页面异常0；此记录不代替所有页面的窄屏验收。V1-49继续跟踪其他加载/错误/空态与主流程回归。
