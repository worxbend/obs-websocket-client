"""Small numeric mask grammar with checked dependencies and signed Long semantics.

Accept decimal literals, sibling references, parentheses, <<, & and | only.
Precedence follows Scala (| < & < <<); shifts must be in [0, 63]. No eval.
Retain legacy source only when every Int intermediate has the same Long value
and all references are already initialized; otherwise emit an evaluated Long.
"""

import re
from dataclasses import dataclass
from graphlib import CycleError, TopologicalSorter

from .errors import GenerationError
from .schema import SchemaEnum

_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_TOKEN = re.compile(r"[ \t\n\r\f\v]+|-?(?:0|[1-9][0-9]*)|[A-Za-z_][A-Za-z0-9_]*|<<|[()&|]")
_PRECEDENCE = {"|": 1, "&": 2, "<<": 3}


@dataclass(frozen=True, slots=True)
class Binary:
    operator: str
    left: Expression
    right: Expression


type Expression = int | str | Binary


class Parser:
    def __init__(self, raw: str, owner: str) -> None:
        self.owner = owner
        self.tokens: list[str] = []
        position = 0
        if len(raw) > 4096:
            raise GenerationError(f"{owner} mask expression is too large")
        while position < len(raw):
            match = _TOKEN.match(raw, position)
            if match is None:
                raise GenerationError(f"{owner} invalid mask syntax at {raw[position:]!r}")
            token = match[0]
            if not token.isspace():
                self.tokens.append(token)
            position = match.end()
        if len(self.tokens) > 256:
            raise GenerationError(f"{owner} mask expression has too many tokens")
        self.position = 0

    def parse(self) -> Expression:
        result = self._expression(1)
        if self.position != len(self.tokens):
            raise GenerationError(
                f"{self.owner} unexpected mask token: {self.tokens[self.position]}"
            )
        return result

    def _expression(self, precedence: int) -> Expression:
        left = self._atom()
        while self.position < len(self.tokens):
            operator = self.tokens[self.position]
            level = _PRECEDENCE.get(operator, 0)
            if level < precedence:
                break
            self.position += 1
            left = Binary(operator, left, self._expression(level + 1))
        return left

    def _atom(self) -> Expression:
        if self.position == len(self.tokens):
            raise GenerationError(f"{self.owner} missing mask operand")
        token = self.tokens[self.position]
        self.position += 1
        if token == "(":
            result = self._expression(1)
            if self.position == len(self.tokens) or self.tokens[self.position] != ")":
                raise GenerationError(f"{self.owner} missing closing mask parenthesis")
            self.position += 1
            return result
        if _IDENTIFIER.fullmatch(token):
            return token
        if re.fullmatch(r"-?(?:0|[1-9][0-9]*)", token):
            value = int(token)
            if not -(2**63) <= value < 2**63:
                raise GenerationError(f"{self.owner} value outside signed Long range: {token}")
            return value
        raise GenerationError(f"{self.owner} invalid mask operand: {token}")


def _references(expression: Expression) -> set[str]:
    if isinstance(expression, Binary):
        return _references(expression.left) | _references(expression.right)
    return {expression} if isinstance(expression, str) else set()


@dataclass(frozen=True, slots=True)
class Value:
    number: int
    long_width: bool
    source_safe: bool


def _signed_long(value: int) -> int:
    return (value + 2**63) % 2**64 - 2**63


def _evaluate(expression: Expression, values: dict[str, int], owner: str) -> Value:
    if isinstance(expression, int):
        small = -(2**31) <= expression < 2**31
        return Value(expression, not small, small)
    if isinstance(expression, str):
        return Value(values[expression], True, True)
    left = _evaluate(expression.left, values, owner)
    right = _evaluate(expression.right, values, owner)
    if expression.operator == "<<":
        if not 0 <= right.number < 64:
            raise GenerationError(f"{owner} shift outside [0, 63]: {right.number}")
        number = _signed_long(left.number << right.number)
        long_width = left.long_width
        safe = long_width or (right.number < 32 and -(2**31) <= number < 2**31)
    else:
        number = (
            left.number & right.number if expression.operator == "&" else left.number | right.number
        )
        long_width = left.long_width or right.long_width
        safe = True
    return Value(number, long_width, safe and left.source_safe and right.source_safe)


def numeric_expressions(enumeration: SchemaEnum) -> tuple[str, ...]:
    """Check the complete dependency graph before rendering any ordered constants."""
    name = enumeration.name
    expressions = {
        entry.identifier: Parser(entry.value, f"Enum {name}.{entry.identifier}").parse()
        for entry in enumeration.entries
    }
    graph = {identifier: _references(expression) for identifier, expression in expressions.items()}
    for identifier, references in graph.items():
        unknown = sorted(references - expressions.keys())
        if unknown:
            raise GenerationError(
                f"Enum {name}.{identifier} references identifiers that are not declared enum members: {', '.join(unknown)}"
            )
    values: dict[str, int] = {}
    checked: dict[str, Value] = {}
    try:
        for identifier in TopologicalSorter(graph).static_order():
            value = _evaluate(expressions[identifier], values, f"Enum {name}.{identifier}")
            values[identifier] = value.number
            checked[identifier] = value
    except CycleError as error:
        raise GenerationError(
            f"Enum {name} has cyclic mask dependencies: {error.args[1]}"
        ) from error
    initialized: set[str] = set()
    result = []
    for entry in enumeration.entries:
        value = checked[entry.identifier]
        if value.source_safe and graph[entry.identifier] <= initialized:
            result.append(_IDENTIFIER.sub(lambda match: f"`{match[0]}`.value", entry.value))
        else:
            result.append(f"{value.number}L")
        initialized.add(entry.identifier)
    return tuple(result)
