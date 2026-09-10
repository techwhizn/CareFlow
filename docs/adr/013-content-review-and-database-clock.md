# ADR 013：人工修订对照与统一数据库时间

日期：2026-09-10。状态：接受。

人工切片操作由Java拥有版本CAS、权限复核、操作历史和解析冲突；Python仅提供当前配置下的Token校验。外部校验不持有数据库事务，提交前使用原Authorization重新认证。批量修改原子提交，已发布内容不可变。

新解析保存旧人工内容/启用/标签快照，冲突需显式解决后才可索引和发布；不使用模型猜测覆盖。源内容变更不改写已保存快照。原文完全匹配只提供候选，不自动应用。拆分/合并保留来源范围，但不伪造逐字位置。

浏览器验收发现JVM为Asia/Shanghai、MySQL为UTC时，默认Connector/J假定会话与JVM时区一致，导致TIMESTAMP读取及凭证到期写入的瞬时语义偏差。MySQL连接现固定 `connectionTimeZone=+00:00`、`forceConnectionTimeZoneToSession=true`、`preserveInstants=true`，统一会话时区并保存瞬时。依据[Connector/J时间点说明](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)。H2测试URL不改写，Hikari池参数仍支持常规Spring配置；部署不要另用`spring.datasource.hikari.jdbc-url`绕开统一URL入口。

本机真实验证同时比较公开API时间与MySQL `UNIX_TIMESTAMP`，不能只用写入后读回作证明，因为两个相反偏差可能抵消。数据库生成的历史审计时间无需重写；过去在时区不一致部署中由Java写入的到期时间可能已经偏移，升级时需核对配置与原始意图，重新签发相关应用凭证。无法可靠推断历史写入者时区时不自动批量改时间。DATETIME业务有效期仍按已有显式UTC LocalDateTime契约处理。

内容版本新增创建序号，为同一秒内创建多个版本提供稳定先后；不推测历史同时间记录的真实顺序。
