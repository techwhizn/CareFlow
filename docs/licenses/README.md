# 依赖许可记录

2026-09-11，本地解析与镜像安装环境导出。上层分发说明见[第三方声明](../../THIRD_PARTY.md)。

| 文件 | 范围 |
| --- | --- |
| java-dependencies.txt | Maven license 插件解析的后端119项，包括测试依赖 |
| python-worker.json | 干净Linux Worker镜像44个安装分发包 |
| python-local-models.json | 干净Linux模型服务镜像41个安装分发包 |
| web-dependencies.json | package-lock中的4个生产依赖 |

Python清单由 `scripts/license-inventory.py` 在各自镜像的Python解释器中运行生成；所有记录均有声明或许可分类，不自动推断缺失条款。记录包自身的许可文件路径；操作系统依赖不在此清单内。Java使用 `mvn -f backend/pom.xml org.codehaus.mojo:license-maven-plugin:2.7.0:add-third-party` 生成 `target/generated-sources/license/THIRD-PARTY.txt`。升级依赖后重新生成并检查未知许可及声明变化。

安装前端依赖后运行 `python3 scripts/check-license-files.py`，核对各模块的LICENSE/NOTICE与根文件一致，以及网页分发声明与安装包内完整许可文本一致。Java资源配置将自有声明加入META-INF，依赖JAR的声明仍保留在原包。Python SDK的wheel/sdist显式包含LICENSE和NOTICE。

这些记录不证明镜像全量许可审计完成，不能替代制品内容核验，也不代表已公开发布。
