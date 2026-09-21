"""Behavior tests for Windows packet parsing, calibration, and settings."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from wobblepad_windows.model import (
    Calibration,
    ControlSettings,
    JoystickMapper,
    Pose,
    load_state,
    parse_packet,
    save_state,
)


def vector(x: float, y: float) -> tuple[float, ...]:
    return (x, y, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)


def samples(value: tuple[float, ...]) -> list[tuple[float, ...]]:
    return [value for _ in range(10)]


def calibration_samples() -> dict[Pose, list[tuple[float, ...]]]:
    return {
        Pose.CENTER: samples(vector(0.0, 0.0)),
        Pose.LEFT: samples(vector(-100.0, 0.0)),
        Pose.RIGHT: samples(vector(100.0, 0.0)),
        Pose.UP: samples(vector(0.0, 100.0)),
        Pose.DOWN: samples(vector(0.0, -100.0)),
    }


class PacketTests(unittest.TestCase):
    def test_observed_packet_decodes_nine_signed_values(self) -> None:
        packet = bytes.fromhex("41 3F 01 43 01 29 01 F1 FF E7 FF FE FF 3A E0 68 E0 39 E0 42")
        self.assertEqual(
            parse_packet(packet),
            (319.0, 323.0, 297.0, -15.0, -25.0, -2.0, -8134.0, -8088.0, -8135.0),
        )

    def test_malformed_packet_is_rejected(self) -> None:
        self.assertIsNone(parse_packet(b"short"))
        self.assertIsNone(parse_packet(bytes(20)))


class MapperTests(unittest.TestCase):
    def test_directional_sensitivity_is_independent(self) -> None:
        mapper = JoystickMapper(
            Calibration.from_samples(calibration_samples()),
            ControlSettings(left=0.5, right=2.0, up=1.5, down=0.75, dead_zone=0.0),
        )
        self.assertAlmostEqual(mapper.update(vector(50.0, 0.0), 1.0).x, 1.0)
        mapper.reset()
        self.assertAlmostEqual(mapper.update(vector(-50.0, 0.0), 1.0).x, -0.25)
        mapper.reset()
        self.assertAlmostEqual(mapper.update(vector(0.0, 50.0), 1.0).y, 0.75)
        mapper.reset()
        self.assertAlmostEqual(mapper.update(vector(0.0, -50.0), 1.0).y, -0.375)

    def test_dead_zone_is_separate_and_rescaled(self) -> None:
        mapper = JoystickMapper(
            Calibration.from_samples(calibration_samples()),
            ControlSettings(dead_zone=0.20),
        )
        self.assertEqual(mapper.update(vector(15.0, 0.0), 1.0).x, 0.0)
        mapper.reset()
        self.assertAlmostEqual(mapper.update(vector(60.0, 0.0), 1.0).x, 0.5)

    def test_collinear_calibration_is_rejected(self) -> None:
        invalid = calibration_samples()
        invalid[Pose.UP] = samples(vector(100.0, 0.0))
        invalid[Pose.DOWN] = samples(vector(-100.0, 0.0))
        with self.assertRaisesRegex(ValueError, "similar"):
            Calibration.from_samples(invalid)


class PersistenceTests(unittest.TestCase):
    def test_settings_and_calibration_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / "settings.json"
            controls = ControlSettings(left=1.2, dead_zone=0.12)
            save_state(path, controls, {"board": calibration_samples()})
            loaded_controls, boards = load_state(path)
            self.assertEqual(loaded_controls, controls)
            self.assertEqual(boards["board"][Pose.RIGHT][0], vector(100.0, 0.0))
            self.assertFalse(path.with_name(".settings.json.tmp").exists())


if __name__ == "__main__":
    unittest.main()
