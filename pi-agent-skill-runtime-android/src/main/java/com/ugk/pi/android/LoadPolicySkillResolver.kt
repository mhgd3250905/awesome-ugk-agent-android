package com.ugk.pi.android

/**
 * [AndroidSkillResolver] that honors the file-backed load policies:
 *
 * - `FILE_BACKED` `always` and `indexed` skills pass unconditionally (their
 *   per-run cost is bounded by design: full text or a fixed stub).
 * - All non-`FILE_BACKED` skills, including generic dynamic, custom, and
 *   plugin-declared skills, plus `FILE_BACKED` `triggered` skills, fall back to
 *   [KeywordAndroidSkillResolver]
 *   semantics, so existing plugin skill behavior is not degraded.
 *
 * File policy is applied only to ids explicitly supplied through
 * [AndroidSkillResolutionContext.fileBackedSkillIds]. The resolver never
 * infers a skill's source from its id, description, or instructions; a
 * custom skill may therefore reuse an id present in the repository without
 * being treated as file-backed. Direct calls to the legacy three-argument
 * overload have no file-backed ids and use keyword semantics for all skills.
 */
class LoadPolicySkillResolver(
    private val repository: SkillRepository,
    private val keywordResolver: AndroidSkillResolver = KeywordAndroidSkillResolver()
) : AndroidSkillResolver {

    override fun resolve(
        userMessage: String,
        skills: List<AndroidSkill>,
        availableToolNames: Set<String>
    ): List<AndroidSkill> {
        return resolve(
            userMessage = userMessage,
            skills = skills,
            availableToolNames = availableToolNames,
            resolutionContext = AndroidSkillResolutionContext()
        )
    }

    override fun resolve(
        userMessage: String,
        skills: List<AndroidSkill>,
        availableToolNames: Set<String>,
        resolutionContext: AndroidSkillResolutionContext
    ): List<AndroidSkill> {
        if (resolutionContext.fileBackedSkillIds.isEmpty()) {
            return keywordResolver.resolve(userMessage, skills, availableToolNames)
        }

        val alwaysLoadedSkillIds = repository.load()
            .filter { it.status == ScannedSkillStatus.VALID }
            .mapNotNull { it.manifest }
            .filter { it.loadPolicy != SkillLoadPolicy.TRIGGERED }
            .map { it.name }
            .filter { it in resolutionContext.fileBackedSkillIds }
            .toSet()
        if (alwaysLoadedSkillIds.isEmpty()) {
            return keywordResolver.resolve(userMessage, skills, availableToolNames)
        }

        val (unconditional, keywordDriven) = skills.partition { it.id in alwaysLoadedSkillIds }
        return unconditional + keywordResolver.resolve(userMessage, keywordDriven, availableToolNames)
    }
}
