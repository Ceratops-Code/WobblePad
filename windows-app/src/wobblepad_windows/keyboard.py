"""Windows arrow-key output with deterministic release behavior."""

from __future__ import annotations

import ctypes
import os
from ctypes import wintypes
from typing import Callable

KEYEVENTF_KEYUP = 0x0002
INPUT_KEYBOARD = 1
KEYS = {1: 0x25, 2: 0x27, 4: 0x26, 8: 0x28}
ULONG_PTR = wintypes.WPARAM


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class INPUTUNION(ctypes.Union):
    # SendInput validates cbSize against the complete Win32 INPUT union, even
    # when this application only submits keyboard events.
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("value",)
    _fields_ = [("type", wintypes.DWORD), ("value", INPUTUNION)]


def send_key(virtual_key: int, pressed: bool) -> None:
    """Send one Win32 keyboard transition and surface rejected injection."""

    if os.name != "nt":
        raise OSError("Arrow-key injection is supported only on Windows.")
    event = INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(virtual_key, 0, 0 if pressed else KEYEVENTF_KEYUP, 0, 0))
    sent = ctypes.windll.user32.SendInput(1, ctypes.byref(event), ctypes.sizeof(INPUT))  # type: ignore[attr-defined]
    if sent != 1:
        raise ctypes.WinError()


class ArrowKeyEmitter:
    """Emit only key transitions and release every held key on stop or failure."""

    def __init__(self, sender: Callable[[int, bool], None] = send_key) -> None:
        self._sender = sender
        self._pressed = 0

    @property
    def pressed(self) -> int:
        return self._pressed

    def update(self, keys: int) -> None:
        keys &= 0x0F
        for bit, virtual_key in KEYS.items():
            if self._pressed & bit and not keys & bit:
                self._sender(virtual_key, False)
                self._pressed &= ~bit
        for bit, virtual_key in KEYS.items():
            if keys & bit and not self._pressed & bit:
                self._sender(virtual_key, True)
                self._pressed |= bit

    def release_all(self) -> None:
        for bit, virtual_key in KEYS.items():
            if self._pressed & bit:
                try:
                    self._sender(virtual_key, False)
                finally:
                    self._pressed &= ~bit
