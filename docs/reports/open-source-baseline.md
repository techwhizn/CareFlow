# 开源质量基线检查

验证日期：2026-09-10。本次范围是仓库规范、贡献文档和 CI 基线，不是完整架构重构或生产验收。

| 检查 | 结果 |
| --- | --- |
| Maven Spotless check + verify | 通过，17 个测试通过；本机 JDK 25.0.2，编译目标 Java 21 |
| Ruff check / format check | 通过，覆盖 Worker、测试与 Python 脚本 |
| Python pytest | 18 通过，1 跳过；真实 Milvus 集成测试本次未启用 |
| TypeScript 严格检查与 Vite 构建 | 通过 |
| npm 生产依赖 audit | 0 个已报告漏洞，仅代表本次所检查范围 |
| .env 与 .local/owner-token 忽略规则 | 生效 |

修复了 Python 导入顺序与未使用导入等 10 项静态问题。添加 Ruff 锁定依赖、TypeScript 未使用代码与 switch 穿透检查；CI 增加 Java 格式检查、Python 静态与格式检查及最小读取权限。依赖更新配置参考 [uv 官方 Dependabot 指南](https://docs.astral.sh/uv/guides/integration/dependabot/)。

剩余限制：本机输出包含 JDK/Mockito 及 AnyIO 弃用警告；远端 GitHub 工作流尚未运行。Java/Python 全量依赖漏洞审计、完整敏感信息扫描、干净机器安装、浏览器端到端回归、升级和恢复演练未在本次执行。许可证、远端分支保护和私密安全报告渠道待落实。

控制器业务耦合、通用 Map/dict 与大页面拆分计划见 [质量基线](../quality.md)。本次没有更改业务接口或数据库结构。
