## Current Session Results
{{evaluationResult}}

## Current State
- **Current Tier**: {{currentTier}}
- **Concept Code**: {{conceptCode}}

## Historical Performance
{{#if historicalPerformance}}
{{historicalPerformance}}
{{else}}
This is the first session for this concept.
{{/if}}

---

## Task
Based on the evaluation results and historical performance:
1. Calculate the new mastery percentage
2. Determine the appropriate progress tier
3. Decide if the student should advance
4. Provide recommendations for the next learning session
5. Generate progress summary and congratulatory message (if appropriate)

Apply the tier progression rules consistently and fairly.

**CRITICAL: Return ONLY a valid JSON object. Do NOT include any text, explanations, or markdown code fences before or after the JSON. Start your response with `{` and end with `}`.**

