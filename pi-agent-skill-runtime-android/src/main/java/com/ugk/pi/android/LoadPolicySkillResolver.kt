package com.ugk.pi.android

/**
 * [AndroidSkillResolver] that honors the file-backed load policies:
 *
 * - `always` and `indexed` skills pass unconditionally (their per-run cost is
 *   bounded by design: full text or a fixed stub).
 * - `triggered` file skills and any skill not produced by this runtime
 *   (plugin static skills) fall back to [KeywordAndroidSkillResolver]
 *   semantics, so existing plugin skill behavior is not degraded.
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
        val alwaysLoadedSkillIds = repository.load()
            .filter { it.status == ScannedSkillStatus.VALID }
            .mapNotNull { it.manifest }
            .filter { it.loadPolicy != SkillLoadPolicy.TRIGGERED }
            .map { it.name }
            .toSet()
        if (alwaysLoadedSkillIds.isEmpty()) {
            return keywordResolver.resolve(userMessage, skills, availableToolNames)
        }

        val (unconditional, keywordDriven) = skills.partition { it.id in alwaysLoadedSkillIds }
        return unconditional + keywordResolver.resolve(userMessage, keywordDriven, availableToolNames)
    }
}
