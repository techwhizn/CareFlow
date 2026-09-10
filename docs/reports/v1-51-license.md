# V1-51 许可证进展

2026-09-10，项目所有者明确选择Apache-2.0。

已从Apache官方许可证文本添加根LICENSE，同步README、贡献指南、质量标准、Java模块、Python Worker/SDK、本地模型工具及Web元数据。Python SDK源码包与wheel构建通过，wheel中实际包含Apache-2.0许可表达式与完整LICENSE；依赖锁文件检查通过。第三方模型保留其原MIT许可，项目许可证不改写依赖许可。

来源：https://www.apache.org/licenses/LICENSE-2.0.txt 。未替项目所有者猜测版权主体、个人姓名或安全邮箱。

项目所有者已确认维护者为良枫，私密安全邮箱为techwhiz@126.com；已更新MAINTAINERS.md和SECURITY.md并明确0.1开发版本支持范围。未发送验证邮件，也未启用GitHub远端设置。此项仍需完成第三方分发声明；完整依赖许可证审计随V1-50执行。没有公开发布包、镜像或Release。

2026-09-11：新增根NOTICE、第三方声明及实际依赖清单（Java119项含测试依赖、Linux Worker44包、Linux模型服务41包、Web4个生产包）。前端完整MIT文本随静态目录分发；Java SDK JAR与Python SDK wheel实际核验包含与根文件一致的LICENSE和NOTICE。CI新增声明一致性检查，Docker构建上下文补充自有许可文件。基础系统全量许可与最终镜像内容核验仍属于V1-50/V1-53；不将应用依赖清单称为完整镜像审计。
