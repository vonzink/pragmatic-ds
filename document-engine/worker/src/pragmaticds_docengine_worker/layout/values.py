"""What a VALUE looks like on the page — shared by the pair detector (a label's
figure) and the table detector (a header row is labels, never figures).

Shape only, never meaning: an amount, a count, a percentage or a date is
recognised by its characters, and nothing here decides what the number is for.
"""

import re

#: An amount / percentage / separated count: optional sign, currency mark or opening
#: paren, digits, optional decimals, optional %, closing paren or trailing accounting
#: minus — and it must CARRY a mark: a decimal point, a thousands separator, a currency
#: or percent sign. "1,234.56" · "$3,105.87" · "(12.00)" · "3.5%" · "1,000". A bare
#: run of digits is not one: "1040" is a form, "2026" a year, "4471" an id and
#: "123456789012" an account number, and each of those follows a short label.
_MARKED = re.compile(r"^[($€£¥+\-]{0,3}\d[\d,]*(\.\d+)?[)%\-]{0,2}$")
_MARK = re.compile(r"[.,$€£¥%]")
#: A bare integer counts only up to three digits: a count ("Hours 40", "Dependents 2").
_SHORT_COUNT = re.compile(r"^[(+\-]?\d{1,3}[)\-]?$")
#: A calendar date in the numeric shapes forms print: 01/15/2026 · 1-15-26 · 2026-01-15.
_DATE = re.compile(r"^(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}|\d{4}-\d{2}-\d{2})$")
_CURRENCY = re.compile(r"^[$€£¥]$")


def is_value(text: str) -> bool:
    """True for one span whose text is an amount, a percentage, a date, or a count
    of at most three digits."""
    stripped = text.strip()
    return bool(
        (_MARKED.match(stripped) and _MARK.search(stripped))
        or _SHORT_COUNT.match(stripped)
        or _DATE.match(stripped)
    )


def is_currency_symbol(text: str) -> bool:
    return bool(_CURRENCY.match(text.strip()))


def is_value_run(texts: list[str]) -> bool:
    """A run of spans that is one value: every span a value, or a lone currency
    symbol followed by values ("$" "3,105.87" arrives as two spans)."""
    if not texts:
        return False
    if is_currency_symbol(texts[0]):
        texts = texts[1:]
    return bool(texts) and all(is_value(t) for t in texts)
