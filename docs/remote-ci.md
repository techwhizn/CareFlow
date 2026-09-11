# 远端CI与分支保护待启用方案

本文件和[保护配置示例](../.github/branch-protection.example.json)供远端核验使用。2026-09-11已将提交`5a4656f`推送到GitHub `main`，push事件按工作流定义触发`verify`；本机只有SSH推送身份，没有GitHub API凭据，应用内浏览器也没有登录会话，因此未能回读作业结论或当前保护规则。没有调用GitHub设置接口，V1-50的远端通过与分支保护验收仍未完成。

仓库已有`verify`工作流，作业为`secrets`、`backend`、`worker`、`milvus-integration`、`web`，Pull Request 额外运行`dependency-review`。基础工作流只读仓库权限，不需要真实模型密钥。Milvus作业只使用自动生成的临时基础设施凭证及合成资料，清理仅限自己的临时项目。

获得远端操作授权后，维护者按以下顺序执行并保存实际证据：

1. 确认目标仓库、默认分支及现有保护规则，先导出当前配置；不要直接覆盖已有更严格限制或访问名单。
2. 通过实际PR运行全部5项作业，保存提交SHA、运行链接、每项状态；核对远端显示的检查名称，不能仅凭本地作业ID假定名称和来源。
3. 审查示例：要求分支最新、所有检查、1名审批者、重新审批新改动、解决讨论，并禁止强推及删分支。**1名审批者方案需要另一位有权限的维护者；只有一位维护者时，应先明确评审安排，不能直接启用后再绕过规则。**
4. 按实际工作流检查来源绑定GitHub Actions；检查规则支持情况和管理员绕过设置。示例没有虚构GitHub App ID，也不自动变更现有规则。
5. 使用临时PR验证失败检查阻止合并、通过后满足门禁，并记录保护规则回读结果。此过程不发布软件包、镜像或Release。

GitHub分支保护的能力与必需检查行为见[官方说明](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)，配置字段见[官方API](https://docs.github.com/en/rest/branches/branch-protection)。本地文件不证明远端保护已经存在；不要将本示例的存在记为远端验收通过。
