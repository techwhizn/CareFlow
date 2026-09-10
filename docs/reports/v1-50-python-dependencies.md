# Python 依赖漏洞修复记录

2026-09-11，pip-audit 2.10.1 审计Worker冻结的全部运行依赖，发现旧Pillow、pypdf和Starlette的已知公告。升级为Pillow12.3.0、pypdf6.18.0、FastAPI0.141.1及Starlette1.6.0，保留其余依赖锁定。依据上游 [Pillow发布说明](https://pillow.readthedocs.io/en/stable/releasenotes/12.3.0.html)、[FastAPI发布说明](https://fastapi.tiangolo.com/release-notes/)与[pypdf包信息](https://pypi.org/project/pypdf/6.18.0/)。

在独立Python3.12环境安装冻结锁文件，133项测试通过、1项可选真实Milvus测试跳过。升级后再次审计，无已知漏洞；Web运行依赖npm audit亦为0项。计数与修复前公告ID见[结构化记录](v1-50-python-dependencies.json)。CI新增同样的Python审计，Dependabot覆盖两种SDK。

这是当时漏洞数据库的结果。Java、容器和可选本地模型服务仍需分别审计；远端CI与仓库保护尚未验证，V1-50不据此标记完成。为保持进行中的评估环境稳定，运行中的Worker仍需在实验结束后受控重启，并执行真实解析、检索和SSE回归。
