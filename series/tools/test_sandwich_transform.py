"""Source contract for the board's CE transform, not a client render test.

Reads production Java expressions instead of copying the intended coordinates.
The independent reference follows the two fixed upstream renderer classes and
Minecraft 1.21.11 TextDisplayRenderer; see board-render-research.md for evidence.
No upstream checkout, JAR, Minecraft runtime, or third-party Python module is used.
"""

import ast
from dataclasses import dataclass
import math
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "series/tavern/src/main/java/net/kaleidoscope/tavern/SandwichBoardDisplay.java"
TEXT_JAVA = JAVA.with_name("SandwichBoardText.java")
WIDTHS = (1, 7, 30, 55)  # Injected font-width results; actual glyph metrics are outside this contract.
ALIGNMENTS = ("LEFT", "CENTER", "RIGHT")
POSITION_TOLERANCE = 1e-5  # Production serializes three coordinates to five decimal places.


def numeric(expression, values):
    """Interpret only the arithmetic subset used by the Java configuration."""
    expression = expression.strip().replace("SandwichBoardText.width(text)", "width")
    expression = expression.replace("SandwichBoardText.MAX_WIDTH", "max_width")
    expression = expression.replace("Math.toRadians", "radians").replace("Math.cos", "cos").replace("Math.sin", "sin")
    expression = expression.replace("Math.PI", "pi")
    expression = re.sub(r"(?<=\d)[fFdD]\b", "", expression)
    functions = {"radians": math.radians, "cos": math.cos, "sin": math.sin}

    def visit(node):
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            return node.value
        if isinstance(node, ast.Name) and node.id in {**values, "pi": math.pi}:
            return {**values, "pi": math.pi}[node.id]
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            return visit(node.operand) * (-1 if isinstance(node.op, ast.USub) else 1)
        if isinstance(node, ast.BinOp):
            left, right = visit(node.left), visit(node.right)
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
            if isinstance(node.op, ast.Mult):
                return left * right
            if isinstance(node.op, ast.Div):
                return left / right
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in functions and len(node.args) == 1 and not node.keywords:
            return functions[node.func.id](visit(node.args[0]))
        raise ValueError(f"Unsupported Java arithmetic; review the render contract: {expression!r}")

    return visit(ast.parse(expression, mode="eval").body)


def only(pattern, source):
    matches = list(re.finditer(pattern, source, re.DOTALL))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one Java source contract match: {pattern}")
    return matches[0]


def split_arguments(expression):
    parts, start, depth = [], 0, 0
    for index, char in enumerate(expression):
        depth += (char == "(") - (char == ")")
        if char == "," and depth == 0:
            parts.append(expression[start:index])
            start = index + 1
    return [*parts, expression[start:]]


@dataclass(frozen=True)
class Configuration:
    position: tuple
    pitch: float
    yaw: float
    scale: tuple
    line_width: float


class JavaConfiguration:
    def __init__(self, source):
        # Keep string literals while removing Java comments.
        clean = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/',
                       lambda match: match[0] if match[0].startswith('"') else "", source, flags=re.DOTALL)
        start = only(r"\bconfiguration\s*\([^)]*\)\s*\{", clean).end()
        depth, end = 1, start
        for match in re.finditer(r'"(?:\\.|[^"\\])*"|[{}]', clean[start:]):
            if match[0] == "{":
                depth += 1
            elif match[0] == "}":
                depth -= 1
            if depth == 0:
                end = start + match.start()
                break
        if depth:
            raise ValueError("Unclosed Java configuration method")
        self.body = clean[start:end]
        self.x = only(r"double\s+x\s*=\s*switch\s*\(state\.alignment\(\)\)\s*\{(.*?)\};", self.body)
        self.cases = {alignment: only(rf"case\s+{alignment}\s*->\s*([^;]+);", self.x[1])[1]
                      for alignment in ALIGNMENTS}
        self.locals = list(re.finditer(r"(?:final\s+)?(?:double|float|int)\s+(\w+)\s*=\s*([^;{}]+);", self.body))
        position = only(r'config\.put\("position",\s*String\.format\(java\.util\.Locale\.ROOT,\s*"([^"\n]+)",\s*(.*?)\)\s*\);', self.body)
        self.format, self.position = position[1], split_arguments(position[2])
        if len(self.position) != 3:
            raise ValueError("Expected three Java position arguments")
        self.scale = tuple(float(value) for value in only(r'config\.put\("scale",\s*"([^"\n]+)"\s*\);', self.body)[1].split(","))
        if len(self.scale) != 3:
            raise ValueError("Expected three Java scale coordinates")
        self.scalars = {key: only(rf'config\.put\("{key}",\s*([^;]+)\);', self.body)[1]
                        for key in ("pitch", "yaw", "line_width")}
        if re.search(r'config\.put\("(?:rotation|translation|billboard)"', self.body):
            raise ValueError("New display transform metadata requires a render contract review")
        self.max_width = int(only(r"MAX_WIDTH\s*=\s*(\d+)", TEXT_JAVA.read_text(encoding="utf-8"))[1])

    def at(self, width, line, alignment):
        values = {"width": width, "line": line, "max_width": self.max_width}
        statements = [(match.start(), match[1], match[2]) for match in self.locals]
        statements.append((self.x.start(), "x", self.cases[alignment]))
        for _, name, expression in sorted(statements):
            values[name] = numeric(expression, values)
        position = tuple(float(value) for value in (self.format % tuple(numeric(arg, values) for arg in self.position)).split(","))
        return Configuration(position, numeric(self.scalars["pitch"], values), numeric(self.scalars["yaw"], values),
                             self.scale, numeric(self.scalars["line_width"], values))


def multiply(left, right):
    return tuple(tuple(sum(left[row][k] * right[k][column] for k in range(3)) for column in range(3)) for row in range(3))


def vector(matrix, value):
    return tuple(sum(matrix[row][column] * value[column] for column in range(3)) for row in range(3))


def add(left, right):
    return tuple(a + b for a, b in zip(left, right))


def scale(x, y, z):
    return ((x, 0, 0), (0, y, 0), (0, 0, z))


def rotate_x(angle):
    cosine, sine = math.cos(angle), math.sin(angle)
    return ((1, 0, 0), (0, cosine, -sine), (0, sine, cosine))


def rotate_y(angle):
    cosine, sine = math.cos(angle), math.sin(angle)
    return ((cosine, 0, sine), (0, 1, 0), (-sine, 0, cosine))


def upstream(yaw):
    """Fixed upstream: world rotation, board tilt, YN angle, then font scale."""
    world = rotate_y(-math.radians(yaw))
    matrix = multiply(multiply(multiply(world, rotate_x(math.radians(22.5))), rotate_y(-math.pi)), scale(.01, -.01, .01))
    return world, matrix


def ce_matrix(configuration, yaw):
    # DisplayRenderer FIXED orientation and CE's metadata scale, followed by TextDisplayRenderer.
    outer = multiply(rotate_y(-math.radians(yaw + configuration.yaw)), rotate_x(math.radians(configuration.pitch)))
    return multiply(multiply(multiply(outer, scale(*configuration.scale)), rotate_y(math.pi)), scale(-.025, -.025, -.025))


def baseline_error(configuration, width, line, alignment, yaw):
    world, reference = upstream(yaw)
    source_x = {"LEFT": -55 / 2, "CENTER": -width / 2, "RIGHT": 55 / 2 - width}[alignment]
    expected = add(vector(world, (0, 1.06, -.06)), vector(reference, (source_x, line * 10 - 19, 0)))
    x, y, z = configuration.position
    # FurniturePlacement.rotateOffset/position: local x is inverted, local z is inverted twice.
    origin = vector(world, (-x, y, z))
    # A single native line has height 10 * 1 - 1 = 9, and its one-pixel x padding remains active.
    actual = add(origin, vector(ce_matrix(configuration, yaw), (1 - width / 2, -9, 0)))
    return math.dist(actual, expected)


class SandwichTransformTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = JAVA.read_text(encoding="utf-8")
        cls.java = JavaConfiguration(cls.source)

    def test_java_configuration_matches_fixed_upstream_for_all_directions_lines_and_alignments(self):
        maximum, worst, cases = 0, None, 0
        configurations = {(width, line, alignment): self.java.at(width, line, alignment)
                          for width in WIDTHS for line in range(8) for alignment in ALIGNMENTS}
        for key, configuration in configurations.items():
            self.assertGreaterEqual(configuration.line_width, 55, key)
        for yaw in range(360):
            for (width, line, alignment), configuration in configurations.items():
                error = baseline_error(configuration, width, line, alignment, yaw)
                if error > maximum:
                    maximum, worst = error, (yaw, width, line, alignment)
                cases += 1
        self.assertEqual(34560, cases)
        self.assertLessEqual(maximum, POSITION_TOLERANCE, f"Java CE baseline drift {maximum} blocks at {worst}")

    def test_java_orientation_matches_the_board_plane_instead_of_only_its_origin(self):
        configuration = self.java.at(30, 0, "CENTER")
        for yaw in range(360):
            _, reference = upstream(yaw)
            actual = ce_matrix(configuration, yaw)
            self.assertLessEqual(max(abs(actual[row][column] - reference[row][column]) for row in range(3) for column in range(3)),
                                 1e-12, f"Java glyph axes disagree with the upstream board at yaw {yaw}")

    def test_native_sixteen_rotation_steps_include_the_half_degree_directions(self):
        # Integer-degree coverage alone omits 22.5, 67.5, etc.
        for step in range(16):
            yaw = step * 22.5
            for width in WIDTHS:
                for line in range(8):
                    for alignment in ALIGNMENTS:
                        configuration = self.java.at(width, line, alignment)
                        self.assertLessEqual(baseline_error(configuration, width, line, alignment, yaw), POSITION_TOLERANCE,
                                             (yaw, width, line, alignment))

    def test_checker_detects_the_previous_positive_pitch(self):
        mutated = re.sub(r'(config\.put\("pitch",\s*)[^;]+(\);)', r'\g<1>22.5f\2', self.source)
        configuration = JavaConfiguration(mutated).at(30, 0, "CENTER")
        self.assertGreater(baseline_error(configuration, 30, 0, "CENTER", 0), .01)

    def test_checker_detects_the_previous_reversed_left_and_right(self):
        for alignment, expression in (("LEFT", "(SandwichBoardText.MAX_WIDTH - SandwichBoardText.width(text)) * .005"),
                                      ("RIGHT", "(SandwichBoardText.width(text) - SandwichBoardText.MAX_WIDTH) * .005")):
            mutated = re.sub(rf'(case\s+{alignment}\s*->\s*)[^;]+;', rf'\g<1>{expression};', self.source)
            configuration = JavaConfiguration(mutated).at(30, 0, alignment)
            self.assertGreater(baseline_error(configuration, 30, 0, alignment, 0), .1)

    def test_checker_detects_missing_native_horizontal_padding(self):
        mutated = re.sub(r'(case\s+CENTER\s*->\s*)[^;]+;', r'\g<1>0;', self.source)
        configuration = JavaConfiguration(mutated).at(30, 0, "CENTER")
        self.assertGreater(baseline_error(configuration, 30, 0, "CENTER", 0), .009)

    def test_checker_detects_the_previous_first_line_anchor(self):
        mutated = re.sub(r'double\s+y\s*=\s*[^;]+;', "double y = 1.25 - line * .1 * Math.cos(Math.toRadians(22.5));", self.source)
        mutated = re.sub(r'double\s+z\s*=\s*[^;]+;', "double z = -.06 - line * .1 * Math.sin(Math.toRadians(22.5));", mutated)
        configuration = JavaConfiguration(mutated).at(30, 0, "CENTER")
        self.assertGreater(baseline_error(configuration, 30, 0, "CENTER", 0), .09)


if __name__ == "__main__":
    unittest.main()
