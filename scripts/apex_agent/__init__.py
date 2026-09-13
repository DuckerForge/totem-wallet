"""Apex Agent Gate — the PC half.

An agent (a bot, a script, an LLM) never holds a key. It builds a transaction,
declares what that transaction does, and hands both to Apex on the Seeker via
`apex://agent/sign`. Apex simulates the real bytes and blocks the signature when
the declaration does not match the simulated effect.

Nothing here is provider-specific: it is a URL plus a JSON object.
"""

__all__ = ["solana", "jupiter", "link", "qr", "state"]
