package com.ugk.pi.android

/** Shared host-to-Agent wording for a session that explicitly skips confirmations. */
object AgentConfirmationPolicy {
    const val FULL_AUTHORIZATION_AGENT_INSTRUCTION =
        "Full authorization is active for this Agent session. Do not call $USER_CONFIRMATION_DIALOG_TOOL_NAME or plan a confirmation round before a protected Tool. Protected tools may execute directly, but all tool-specific validation, permission checks, target validation, and result verification still apply."
}
