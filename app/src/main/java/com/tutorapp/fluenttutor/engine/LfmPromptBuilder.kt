package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.Scenario

/** 組裝 LFM3-Audio 的 system prompt：角色設定 + 目標條件 + 結構化輸出協議 */
object LfmPromptBuilder {

    fun build(scenario: Scenario, goals: List<MissionGoal>): String {
        val goalLines = goals.sortedBy { it.orderIndex }.joinToString("\n") { g ->
            "${g.orderIndex}. [${g.id}] ${g.title} — ${g.criteria} (status: ${if (g.isCompleted) "DONE" else "PENDING"})"
        }
        return """
            ${scenario.systemPrompt}

            This is a spoken English practice mission. Stay in character and speak 1-2 short natural sentences.
            The learner's goals, in order:
            $goalLines

            Judging rules:
            - List a goal id in "goals" only if the learner clearly satisfied its criteria in this utterance. Negations or refusals do not satisfy a goal.
            - Never list a DONE goal, and never list a goal whose earlier goals are still PENDING.
            - "off_topic" is true only if the utterance has nothing to do with this scenario.
            - If you could not understand the learner, set "transcript" to "" and ask them to repeat.
            - "relevance" and "fluency" are integers 0-100 for this utterance.
            - "emotion" specifies your facial emotion for this turn, choose exactly one from:
              * "WELCOMING" (initial friendly greeting)
              * "HAPPY_ENCOURAGING" (normal progress, positive engagement)
              * "EXCITED_PROUD" (target goal achieved or outstanding performance)
              * "CONFUSED_WARNING" (off-topic utterance or clarification needed)
              * "DISAPPOINTED_FAILED" (repeated failures or mission termination)

            After your spoken reply, output exactly one final block:
            <verdict>{"transcript":"<what the learner said>","off_topic":false,"goals":[],"relevance":0,"fluency":0,"emotion":"HAPPY_ENCOURAGING","feedback":"<one short tip in English>"}</verdict>
        """.trimIndent()
    }
}
