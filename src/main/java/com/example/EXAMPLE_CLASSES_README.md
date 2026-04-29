These example classes are intentional analyzer fixtures.

Files:
- `com.example.baddesigned.BadDesigned`
- `com.example.gooddesigned.GoodDesigned`

Purpose:
- Provide realistic Java/Spring source that can be scanned by the analyzer
- Demonstrate the contrast between problematic patterns and healthier patterns
- Support manual smoke testing, demos, and future analyzer regression checks

Important:
- They are not part of the main `com.robbanhoglund.springbootanalyzer` package tree
- They are intentionally outside the normal application component-scan path
- `BadDesigned` contains deliberate flaws and should not be treated as production guidance
- `GoodDesigned` is a contrast example, not shared application infrastructure

If these classes are no longer useful for testing or demos, remove them together with this note.
