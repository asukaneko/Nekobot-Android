# 离线时间词库测试集

五语需求补充题112条，上游入选292条，另有4组边界测试。`original61.jsonl`独立保存61道固定回归题，参考日为2026-10-23；补充题与这61题分别统计。61题含简体中文20题、英文13题、日文14题、韩文14题，繁体中文由补充题覆盖。

61题中的4道“月底”类表达按跨度处理，期望为Unresolved；夹具保留原始日期期望和调整理由，不能强定为该月最后一天。所有原题由`TimeWordOriginal61Test`逐题断言，来源与补充题不混算。

上游固定版本 `da7edcff59f669b2a460ab9d400e36298f0d658e`，来源为 Microsoft Recognizers-Text 的 Chinese/English/Japanese/Korean 下 DateParser.json 和 DateTimeModel.json。共扫描2732条，按单一日期结果和声明支持语法筛选292条，跳过2440条；统计和文件SHA256见selection.json。这里的文件范围与文档列出的全Specs总数不同，不声称完整上游覆盖。

每行保留来源文件和0起始下标。测试生成发生在运行引擎之前，筛选不依赖引擎是否答对。无年日期统一取说话当年，显式星期按自然周（周一开始）。上游的无解哨兵转换成null；斜杠格式仅接MM/DD/YYYY。两条韩文上游期望年份与原文字面不符，按完整年份校正；原始期望和原因保留在upstreamExpected/adjustment中。全部选中用例逐条断言日期，不仅断言汇总通过率。

规则只承诺完整的时间词短语，不承诺从任意长句抽取日期。范围、模糊时间、多日期和非法日期返回Unresolved。运行时完全离线，未引入上游引擎。Microsoft版权与完整MIT许可见主程序assets/third_party_licenses/Recognizers-Text-MIT.txt。
