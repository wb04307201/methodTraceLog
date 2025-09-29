package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

@Data
@ConfigurationProperties(prefix = "method-trace-log")
public class MethodTraceLogProperties {

    private LogProperties log = new LogProperties();

    private FileProperties file = new FileProperties();

    private AiProperties ai = new AiProperties();


    @Data
    public static class LogProperties {
        private Boolean enable = true;

        private List<ServiceCallProperties> serviceCalls = new ArrayList<>();


        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ServiceCallProperties {
            private String name;
            private Boolean enable = true;
        }
    }

    @Data
    public static class FileProperties {

        private Boolean enable = true;

        /**
         * 日志文件根目录
         */
        private String logPath = "./logs";

        /**
         * 允许访问的日志文件扩展名
         */
        private List<String> allowedExtensions = Arrays.asList(".log", ".txt", ".out");

        /**
         * 单次查询最大行数
         */
        private int maxLines = 1000;

        /**
         * 文件最大大小（MB）
         */
        private long maxFileSize = 100;

        /**
         * 日志文件匹配模式
         */
        private String logPattern = "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^\\]]+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s*-\\s*(.*)";
    }

    @Data
    public static class AiProperties {
        private String system = "你是一个专业的代码时间复杂度分析专家。请严格按照JSON格式返回分析结果，不要包含任何其他文本。";
        private String promptTemplate = """
            请分析以下java代码的时间复杂度，并返回严格的JSON格式结果：
            
            代码：
            ```java
            %s
            ```
            
            分析模式：快速模式（基于启发式规则）
            
            请返回以下JSON格式的分析结果（不要包含任何其他文本）：
            
            {
              "overallComplexity": "整体时间复杂度（如O(n²)）",
              "confidence": 分析置信度（0-100的数字）,
              "explanation": "详细的复杂度分析说明",
              "lineAnalysis": [
                {
                  "lineNumber": 行号,
                  "complexity": "该行的时间复杂度",
                  "explanation": "该行复杂度的详细解释",
                  "code": "该行的代码内容"
                }
              ],
              "suggestions": [
                {
                  "type": "优化类型（space-time-tradeoff/algorithm-refactor/data-structure/loop-optimization）",
                  "title": "优化建议标题",
                  "description": "详细的优化建议描述",
                  "codeExample": "优化后的示例代码",
                  "impact": "影响程度（high/medium/low）"
                }
              ],
              "visualData": {
                "chartData": [
                  {"inputSize": 10, "operations": 100, "complexity": "O(n²)"},
                  {"inputSize": 100, "operations": 10000, "complexity": "O(n²)"},
                  {"inputSize": 1000, "operations": 1000000, "complexity": "O(n²)"}
                ],
                "complexityBreakdown": [
                  {"section": "循环部分", "complexity": "O(n²)", "percentage": 80, "color": "#ef4444"},
                  {"section": "初始化部分", "complexity": "O(1)", "percentage": 20, "color": "#22c55e"}
                ]
              }
            }
            
            请确保：
            1. 分析所有重要的代码行，特别是循环、递归和函数调用
            2. 提供具体的优化建议和示例代码
            3. 生成合理的可视化数据
            4. 置信度要基于代码的复杂程度和分析的准确性
            5. 返回的JSON必须是有效的格式，不包含注释或其他文本
            """;

    }
}
