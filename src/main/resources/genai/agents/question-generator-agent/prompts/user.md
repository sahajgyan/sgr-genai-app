## Concept Information
- **Concept Code**: {{conceptCode}}
- **Concept Name**: {{conceptName}}
- **Current Progress Tier**: {{currentTier}}

## Concept Units/Topics
The following units should be covered in the questions:
{{conceptUnits}}

---

## Task
Generate **{{numberOfQuestions}}** adaptive questions for this concept that:
1. Match the student's current tier level ({{currentTier}})
2. Cover the concept units listed above
3. Help the student progress to the next tier
4. Include a mix of question types (MULTIPLE_CHOICE, TRUE_FALSE, SHORT_ANSWER)

**CRITICAL: Return ONLY a valid JSON object with a "questions" array. Do NOT include any text, explanations, or markdown code fences. Start your response with `{` and end with `}`.**
