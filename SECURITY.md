# Security Policy

OpenPhoneUse can operate a phone on the user's behalf, so safety bugs matter.

Please report security issues **privately** through GitHub's "Report a vulnerability" (Security tab) rather than a
public issue. Especially relevant:

- a way for screen, web or notification text (prompt injection) to cause an unconfirmed send, payment or deletion;
- a way for the model to type a password or read a one-time code;
- secrets (tokens, API keys) leaving the Keystore-backed store or appearing in logs.
