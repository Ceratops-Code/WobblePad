"""Behavior tests for transition-only arrow output and fail-safe release."""

from __future__ import annotations

import ctypes
import os
import unittest

from wobblepad_windows.keyboard import INPUT, ArrowKeyEmitter


class ArrowKeyEmitterTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Win32 ABI sizes apply only on Windows")
    def test_input_matches_the_win32_abi_size(self) -> None:
        self.assertEqual(ctypes.sizeof(INPUT), 40 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28)

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
