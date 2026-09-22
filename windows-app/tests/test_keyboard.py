"""Behavior tests for repeated arrow pulses and fail-safe release."""

from __future__ import annotations

import ctypes
import os
import unittest

from wobblepad_windows.keyboard import INPUT, ArrowKeyEmitter


class ArrowKeyEmitterTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Win32 ABI sizes apply only on Windows")
    def test_input_matches_the_win32_abi_size(self) -> None:
        self.assertEqual(ctypes.sizeof(INPUT), 40 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28)

    def test_repeats_held_keys_without_returning_to_center(self) -> None:
        events: list[tuple[int, bool]] = []
        now = [0.0]
        emitter = ArrowKeyEmitter(
            lambda key, pressed: events.append((key, pressed)),
            repeat_interval=0.333,
            clock=lambda: now[0],
        )

        emitter.update(1 | 4)
        now[0] = 0.060
        emitter.update(1 | 4)
        now[0] = 0.332
        emitter.update(1 | 4)
        now[0] = 0.333
        emitter.update(1 | 4)
        now[0] = 0.393
        emitter.update(1 | 4)
        emitter.release_all()

        self.assertEqual(
            events,
            [
                (0x25, True),
                (0x26, True),
                (0x25, False),
                (0x26, False),
                (0x25, True),
                (0x26, True),
                (0x25, False),
                (0x26, False),
            ],
        )
        self.assertEqual(emitter.pressed, 0)

    def test_changed_repeat_interval_controls_the_next_pulse(self) -> None:
        events: list[tuple[int, bool]] = []
        now = [0.0]
        emitter = ArrowKeyEmitter(
            lambda key, pressed: events.append((key, pressed)),
            repeat_interval=0.5,
            clock=lambda: now[0],
        )

        emitter.update(1)
        now[0] = 0.060
        emitter.update(1)
        emitter.set_repeat_interval(0.2)
        now[0] = 0.259
        emitter.update(1)
        now[0] = 0.260
        emitter.update(1)

        self.assertEqual(events, [(0x25, True), (0x25, False), (0x25, True)])

    def test_release_attempts_every_held_key_after_sender_failure(self) -> None:
        events: list[tuple[int, bool]] = []

        def sender(key: int, pressed: bool) -> None:
            events.append((key, pressed))
            if key == 0x25 and not pressed:
                raise OSError("release failed")

        emitter = ArrowKeyEmitter(sender)
        emitter.update(1 | 2)

        with self.assertRaisesRegex(OSError, "release failed"):
            emitter.release_all()

        self.assertEqual(
            events,
            [(0x25, True), (0x27, True), (0x25, False), (0x27, False)],
        )
        self.assertEqual(emitter.pressed, 0)


if __name__ == "__main__":
    unittest.main()
