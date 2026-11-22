# 评测输出（模拟参考数据集 v1）

本目录由 `EvaluationRunner` / `EvaluationRunnerTest` 实际生成，输入为 `docs/datasets/lingxun-simulated-reference-v1.json`。

## 重要限制

- 数据性质为 **SIMULATED_REFERENCE**，不是真实校方通知。
- 当前环境没有配置可用的 LLM API Key，因此 `HYBRID` 和 `LLM` 请求模式都实际回退为规则模式（`effective_mode=RULE_FALLBACK`，`status=DISABLED`）。所以下表中的三组指标相同，**不能**据此声称“混合方案/大模型方案达到 1.0 F1”。
- 模拟样本的措辞是按现有规则可识别的格式人工编写的，规则模式在该数据集上取得高分不能外推为真实通知上的准确率。真实数据到位后必须重新标注、重新运行并单独报告。

## 文件

| 文件 | 内容 |
| --- | --- |
| `summary.csv` / `summary.json` | 三种请求模式的字段级 P/R/F1、角色错配率、证据定位率、无依据输出率、不确定字段误确认数 |
| `field-predictions-rule.csv` | 规则模式的逐样本、逐字段预测与标准答案、错误分析 |
| `field-predictions-hybrid.csv` | 混合请求模式的实际结果（本次全部回退规则） |
| `field-predictions-llm.csv` | LLM 请求模式的实际结果（本次全部回退规则） |

## 本次实测摘要

- 样本数：10
- 标准字段数：26
- RULE：TP 26 / FP 0 / FN 0，precision=1.0，recall=1.0，F1=1.0
- HYBRID：effective_mode=RULE_FALLBACK，指标与 RULE 相同
- LLM：effective_mode=RULE_FALLBACK，指标与 RULE 相同
- 角色错配：0
- 证据定位成功率：1.0
- 无依据输出比例：0.0
- 不确定字段被自动确认：0

上述数字是**模拟数据上的真实运行结果**，不是真实通知上的实验结果。

## 复现

在 `backend` 目录执行：

```powershell
mvn -q -Dtest=EvaluationRunnerTest test
```

或直接运行主类（需先准备 classpath）：

```powershell
mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes;$(Get-Content cp.txt)" cn.sgu.lingxun.EvaluationRunner
```

真实 LLM 对比需要先由运行环境注入 `LINGXUN_LLM_ENABLED=true`、`LINGXUN_LLM_API_KEY` 等变量，再重新运行；未注入时工具会如实报告回退状态。
