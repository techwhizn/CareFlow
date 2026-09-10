# 容器安全修复与剩余扫描项

2026-09-11使用Trivy0.74.0实际扫描本地构建镜像。扫描身份、完整发现列表和原始报告摘要见[JSON记录](v1-50-container-scan.json)。没有设置漏洞忽略规则。

- 后端：移除基础镜像中未被入口使用、无dpkg所属包的Go程序`/usr/bin/pebble`，原8个高危项不再存在。仍有65个低/中危系统项。
- Web：升级系统包仍无法更新上游锁定的nginx1.28.3，最终更换为官方nginx1.30.4-alpine并升级系统包。新镜像扫描0项；修复依据[上游安全公告](https://nginx.org/en/security_advisories.html)。
- Worker：构建校验SHA256的Tesseract5.5.3，去除训练工具及归档/网络功能；运行镜像不保留编译工具和uv。输入先转换为无原始元数据的RGB PNG。GLib/libxml2等不再通过完整OCR工具链引入，剩余3严重、53高危、64中危、86低危、6未知项主要来自Debian基础包，尚未全部完成可达性核查，不宣称扫描通过。

Worker剩余严重项被扫描器归于perl-base，包含32位正则问题和Archive::Tar问题。当前是amd64且解析器不运行Perl，但仍需按具体包内容和调用路径完成审查；没有仅凭这一观察豁免全部发现。libtiff和SQLite等解析相关项也必须单独核对。

实际回归：Worker143测试通过、1个可选Milvus测试跳过；容器以非root、2CPU/2GiB、128进程上限、只读根目录验证PNG/JPEG/扫描PDF/中文OCR及页数、像素、解压、超时、内存限制。首次离线验证因没有tiktoken缓存失败；允许获取固定词表后通过。内存探针采用独立10秒CPU上限，CPU探针仍为1秒，避免把CPU耗尽误报成内存限制。

镜像构建成功不代表全产品验收完成。基础设施及可选模型镜像的扫描、剩余OS发现处理仍在V1-50中跟踪；没有发布镜像。

后续加固：Compose对Worker API和消费者默认启用只读根目录、cap_drop ALL和no-new-privileges，临时目录与词表缓存使用限额tmpfs。实际新上传PNG经消费者OCR、解析、BGE索引和发布后，由Worker API完成公共搜索，全部通过；[容器实际配置及任务记录](v1-50-worker-sandbox-real.json)。这不是剩余OS漏洞的豁免依据。
