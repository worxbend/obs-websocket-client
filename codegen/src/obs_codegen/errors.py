"""Expected user/input failures at the compiler boundary."""


class GenerationError(ValueError):
    """Invalid input or unsafe output; no successful generation is possible."""
