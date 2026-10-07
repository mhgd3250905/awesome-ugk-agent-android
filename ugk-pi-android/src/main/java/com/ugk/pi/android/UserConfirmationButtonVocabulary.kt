package com.ugk.pi.android

import java.util.Locale

/**
 * What a button id means in a user confirmation answer.
 *
 * [UNRECOGNIZED] is a first-class arm on purpose: an id the SDK does not know says nothing
 * about what the user meant, and reading it as a refusal would block an action the user may
 * well have approved.
 */
enum class UserConfirmationButtonIntent {
    ACCEPTED,
    DECLINED,
    UNRECOGNIZED
}

/**
 * The one normalisation a button id goes through before it is compared with a vocabulary.
 *
 * A button id is authored by the model and echoed back by the host, so it is not under this
 * SDK's control: `Not_Now`, `CANCEL` and `" cancel"` are all things a model writes for a
 * button the host then draws as a Cancel button. Before this existed, the host's visual
 * classifier trimmed-and-lowercased while this module compared the id verbatim, so the
 * button the user pressed was not the answer the protected Tool reported - and two of the
 * host's own readers lowercased with the *device* locale, which splits `DISMISS` from
 * `dismiss` on a Turkish device while the visual classifier still matched it.
 *
 * `Locale.ROOT` is deliberate: the vocabulary is ASCII identifiers, so a locale-sensitive
 * mapping can only ever lose matches.
 */
fun normalizeUserConfirmationButtonId(buttonId: String): String =
    buttonId.trim().lowercase(Locale.ROOT)

/** [ids] with every element put through [normalizeUserConfirmationButtonId]. */
internal fun Set<String>.asConfirmationButtonIds(): Set<String> =
    if (all { it == normalizeUserConfirmationButtonId(it) }) this
    else mapTo(mutableSetOf()) { normalizeUserConfirmationButtonId(it) }

private val DEFAULT_ACCEPTED_BUTTON_IDS = USER_CONFIRMATION_ACCEPTED_BUTTON_IDS.asConfirmationButtonIds()
private val DEFAULT_DECLINED_BUTTON_IDS = USER_CONFIRMATION_DECLINED_BUTTON_IDS.asConfirmationButtonIds()

/**
 * The single classification every reader of a confirmation button id must use.
 *
 * Accepted wins over declined if a host injects vocabularies that overlap, matching the
 * precedence the protected Tool has always had: an id inside the accepted set is checked
 * first, so an overlap cannot silently turn an authorization into a refusal.
 */
fun userConfirmationButtonIntent(buttonId: String): UserConfirmationButtonIntent =
    userConfirmationButtonIntent(
        buttonId = buttonId,
        acceptedButtonIds = USER_CONFIRMATION_ACCEPTED_BUTTON_IDS,
        declinedButtonIds = USER_CONFIRMATION_DECLINED_BUTTON_IDS
    )

fun userConfirmationButtonIntent(
    buttonId: String,
    acceptedButtonIds: Set<String>,
    declinedButtonIds: Set<String>
): UserConfirmationButtonIntent {
    val normalized = normalizeUserConfirmationButtonId(buttonId)
    if (normalized.isEmpty()) return UserConfirmationButtonIntent.UNRECOGNIZED
    return when {
        normalized in acceptedButtonIds.asConfirmationButtonIds() -> UserConfirmationButtonIntent.ACCEPTED
        normalized in declinedButtonIds.asConfirmationButtonIds() -> UserConfirmationButtonIntent.DECLINED
        else -> UserConfirmationButtonIntent.UNRECOGNIZED
    }
}
