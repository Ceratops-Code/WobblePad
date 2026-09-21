"""Behavior tests for transition-only arrow output and fail-safe release."""

from __future__ import annotations

import unittest

from wobblepad_windows.keyboard import ArrowKeyEmitter


class ArrowKeyEmitterTests(unittest.TestCase):
    def test_emits_only_transitions_and_releases_every_key(self) -> None:
        events: list[tuple[int, bool]] = []
        emitter = ArrowKeyEmitter(lambda key, pressed: events.append((key, pressed)))

        emitter.update(1 | 4)
        emitter.update(1 | 4)
        emitter.update(2)
        emitter.release_all()

        self.assertEqual(
            events,
            [
                (0x25, True),
                (0x26, True),
                (0x25, False),
                (0x26, False),
                (0x27, True),
                (0x27, False),
            ],
        )
        self.assertEqual(emitter.pressed, 0)


if __name__ == "__main__":
    unittest.main()
