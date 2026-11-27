package app.focusfence.dns

/**
 * Which on-screen fields text capture must never read. Android marks password fields (`isPassword`), but a web form's
 * password box loses that mark once "show password" is tapped (seen in a Chromium browser, 2026-11-27): its text is
 * then plain text in an editable field right after a "Password" label. So an editable field is skipped when its own
 * hint, description or id, or the label just before it, names a secret.
 */
object SecretFields {

    private val SECRET = Regex(
        "(?i)pass\\s?(word|code|phrase)|\\bpin\\b|\\bcvv|\\bcvc|security code|card number|one[- ]time code|" +
            "verification code|\\botp\\b|2fa|authenticator code|recovery code"
    )

    fun namesSecret(s: CharSequence?): Boolean = !s.isNullOrBlank() && SECRET.containsMatchIn(s)

    /**
     * True when a field's text must be left out. [label] is the piece of text read just before this field (its
     * visible label in most forms).
     */
    fun skip(isPassword: Boolean, editable: Boolean, hint: CharSequence?, description: CharSequence?, viewId: String?, label: CharSequence?): Boolean =
        isPassword || (editable && (namesSecret(hint) || namesSecret(description) || namesSecret(viewId) || namesSecret(label)))
}
