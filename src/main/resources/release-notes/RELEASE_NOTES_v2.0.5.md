# 百目 JArgus v2.0.5

稳定性与资源管理的一次工程修复版本：修复 PDF 半截文件被缓存命中、深度评审进度 map 无界增长、Gradle 单测超时分支泄漏、一键更新跳过 Spring 关闭钩子等真实 bug；切换数据库后旧连接池不再泄漏；AI 客户端 WebClient 按配置复用，不再每次 LLM 调用都新建连接池。

## 本次更新

### 真实 bug 修复

- **PDF 报告生成异常时不再残留坏文件**：之前字体缺失 / IO 异常会留下截断 PDF，下次调用因「文件新于任务完成时间」缓存命中返回给用户或邮件附件。现在异常路径自动删除半截文件。
- **AI 深度评审进度 map 不再无界增长**：每个跑过深度评审的任务进度对象永久驻留内存，长跑一周后累积。任务结束即从内存态移除，重启后回库查已增强条数。
- **Gradle 单测超时分支泄漏输出线程 + StringBuilder 竞态**：之前超时直接 return，输出线程还在 readLine，主线程同时 `toString()` 可能抛 ArrayIndexOutOfBounds。补齐 interrupt + join，并用线程安全的 StringBuffer。
- **一键更新改用 `System.exit(0)`**：之前用 `halt(0)` 跳过 Spring 关闭钩子，H2 文件锁残留导致下次启动走 crash recovery。现在正常释放 DB 连接与文件锁后再重启。

### 资源管理

- **切换数据库后旧 Hikari 池优雅关闭**：之前每次切换漏一个旧池（≈5 个常驻物理连接），Oracle / 达梦这种连接昂贵的库上很快打满。现在切换成功后在后台优雅关闭旧池（在途连接归还后再关，不影响进行中的查询）。
- **AI 客户端 WebClient 按激活配置复用**：之前每次 LLM chat 都新建 OpenAiCompatibleClient → 新建 WebClient → Reactor Netty 连接池与 EventLoop 不释放。现在按激活配置 id 缓存单例，配置保存 / 删除 / 激活切换时自动失效重建。
- **结果页环境分析缓存并发 miss 不再重复计算**：两个请求同时刷同一快照页会双倍跑 JavaParser 全量分析。现在原子化，只跑一次。

### 死代码清理

- 移除 4 处零引用的私有方法 / 字段：`PageController.nz`、`DataSourceContextHolder.set/clear`（路由已走 replaceDefault）、`ControlPlaneRepository.bool`、`ActiveDialectHolder.family`。

## 升级须知

- 数据结构、加密密钥、Cookie、CI Token 与 v2.0.0 起的版本一致，直接覆盖 jar 即可。
- 一键更新流程本身已修复，从 v2.0.4 升级时直接用一键更新按钮即可（v2.0.4 的 halt(0) 问题会在升级后生效）。
